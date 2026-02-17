package com.ownding.video.storage;

import com.ownding.video.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mp4", "mkv", "ts", "flv", "ps", "h264", "h265", "hevc");

    /** Matches ZLM filename pattern: 2026-02-17-17-31-13-0.mp4 */
    private static final Pattern FILENAME_TIME_PATTERN = Pattern.compile(
            "(\\d{4})-(\\d{2})-(\\d{2})-(\\d{2})-(\\d{2})-(\\d{2}).*\\.mp4$");

    /** Matches channel directory: ch34020000001320000001 */
    private static final Pattern CHANNEL_DIR_PATTERN = Pattern.compile("^ch(\\d+)$");

    /** Each MP4 segment is 60 seconds */
    private static final int SEGMENT_DURATION_SECONDS = 60;

    private final StorageRepository storageRepository;

    public StorageService(StorageRepository storageRepository) {
        this.storageRepository = storageRepository;
    }

    public StoragePolicy getPolicy() {
        return storageRepository.getPolicy();
    }

    public StoragePolicy updatePolicy(UpdatePolicyCommand command) {
        if (command.retentionDays() < 1) {
            throw new ApiException(400, "保留天数必须大于0");
        }
        if (command.maxStorageGb() < 1) {
            throw new ApiException(400, "存储上限必须大于0");
        }
        if (command.recordPath() == null || command.recordPath().isBlank()) {
            throw new ApiException(400, "录像路径不能为空");
        }

        StoragePolicy policy = storageRepository.updatePolicy(
                command.retentionDays(),
                command.maxStorageGb(),
                command.autoOverwrite(),
                command.recordEnabled(),
                command.recordPath());
        ensureRecordPath(policy.recordPath());
        return policy;
    }

    public StorageUsage getUsage() {
        StoragePolicy policy = getPolicy();
        List<RecordFile> files = listVideoFiles(ensureRecordPath(policy.recordPath()));
        refreshRecordIndex(files);
        return buildUsage(files, policy.maxStorageGb());
    }

    public List<RecordFileItem> listRecords() {
        StoragePolicy policy = getPolicy();
        List<RecordFile> files = listVideoFiles(ensureRecordPath(policy.recordPath()));
        refreshRecordIndex(files);
        return storageRepository.listRecordFiles();
    }

    public void deleteRecord(long id) {
        RecordFileItem item = storageRepository.findRecordFileById(id)
                .orElseThrow(() -> new ApiException(404, "录像不存在"));
        try {
            Files.deleteIfExists(Path.of(item.filePath()));
        } catch (IOException ex) {
            throw new ApiException(500, "删除文件失败: " + ex.getMessage());
        }
        storageRepository.deleteRecordById(id);
    }

    public StorageUsage executeCleanup() {
        StoragePolicy policy = getPolicy();
        Path recordPath = ensureRecordPath(policy.recordPath());
        List<RecordFile> files = listVideoFiles(recordPath);

        if (policy.retentionDays() > 0) {
            Instant expireBefore = Instant.now().minus(policy.retentionDays(), ChronoUnit.DAYS);
            for (RecordFile file : files) {
                if (file.modifiedAt().isBefore(expireBefore)) {
                    safeDelete(file.path());
                }
            }
        }

        files = listVideoFiles(recordPath);
        if (policy.autoOverwrite()) {
            long maxBytes = policy.maxStorageGb() * 1024L * 1024L * 1024L;
            long totalBytes = files.stream().mapToLong(RecordFile::size).sum();
            if (totalBytes > maxBytes) {
                List<RecordFile> sorted = new ArrayList<>(files);
                sorted.sort(Comparator.comparing(RecordFile::modifiedAt));
                for (RecordFile file : sorted) {
                    if (totalBytes <= maxBytes) {
                        break;
                    }
                    if (safeDelete(file.path())) {
                        totalBytes -= file.size();
                    }
                }
            }
        }

        files = listVideoFiles(recordPath);
        refreshRecordIndex(files);
        return buildUsage(files, policy.maxStorageGb());
    }

    // ── Playback API support ──────────────────────────────────────

    /** Returns the resolved record path for serving files. */
    public Path getRecordPath() {
        return ensureRecordPath(getPolicy().recordPath());
    }

    /**
     * Lists distinct channel IDs that have recordings. Triggers a refresh first.
     */
    public List<PlaybackChannel> listPlaybackChannels() {
        StoragePolicy policy = getPolicy();
        List<RecordFile> files = listVideoFiles(ensureRecordPath(policy.recordPath()));
        refreshRecordIndex(files);
        return storageRepository.listDistinctChannels();
    }

    /** Queries recordings for a given channel on a given date. */
    public List<RecordFileItem> queryPlaybackRecords(String channelId, String date) {
        if (channelId == null || channelId.isBlank()) {
            throw new ApiException(400, "通道ID不能为空");
        }
        if (date == null || date.isBlank()) {
            throw new ApiException(400, "日期不能为空");
        }
        // Refresh index first
        StoragePolicy policy = getPolicy();
        List<RecordFile> files = listVideoFiles(ensureRecordPath(policy.recordPath()));
        refreshRecordIndex(files);

        // date is yyyy-MM-dd, construct start/end of day as ISO instants
        String startTime = date + "T00:00:00";
        String endTime = date + "T23:59:59";
        return storageRepository.findRecordsByChannelAndTimeRange(channelId, startTime, endTime);
    }

    // ── Private helpers ───────────────────────────────────────────

    private Path ensureRecordPath(String rawPath) {
        try {
            Path path = Path.of(rawPath);
            if (!Files.exists(path)) {
                Files.createDirectories(path);
            }
            return path;
        } catch (IOException ex) {
            throw new ApiException(500, "无法创建录像目录: " + ex.getMessage());
        }
    }

    private List<RecordFile> listVideoFiles(Path path) {
        List<RecordFile> result = new ArrayList<>();
        if (!Files.exists(path)) {
            return result;
        }
        try (var stream = Files.walk(path)) {
            stream.filter(Files::isRegularFile).forEach(file -> {
                if (!isVideoFile(file)) {
                    return;
                }
                try {
                    long size = Files.size(file);
                    Instant modifiedAt = Files.getLastModifiedTime(file).toInstant();
                    result.add(new RecordFile(file.toAbsolutePath().normalize(), size, modifiedAt));
                } catch (IOException ex) {
                    log.warn("读取文件失败: {}", file);
                }
            });
        } catch (IOException ex) {
            throw new ApiException(500, "扫描录像目录失败: " + ex.getMessage());
        }
        return result;
    }

    private boolean isVideoFile(Path file) {
        String name = file.getFileName().toString();
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == name.length() - 1) {
            return false;
        }
        String ext = name.substring(dotIndex + 1).toLowerCase();
        return VIDEO_EXTENSIONS.contains(ext);
    }

    private void refreshRecordIndex(List<RecordFile> files) {
        List<StorageRepository.RecordSnapshot> snapshots = files.stream()
                .map(file -> {
                    String channelId = parseChannelIdFromPath(file.path());
                    String startTime = parseStartTimeFromFilename(file.path());
                    String endTime = null;
                    if (startTime != null) {
                        try {
                            LocalDateTime start = LocalDateTime.parse(startTime);
                            endTime = start.plusSeconds(SEGMENT_DURATION_SECONDS).toString();
                        } catch (DateTimeParseException ex) {
                            endTime = startTime;
                        }
                    }
                    if (startTime == null) {
                        startTime = file.modifiedAt().toString();
                    }
                    if (endTime == null) {
                        endTime = file.modifiedAt().toString();
                    }
                    return new StorageRepository.RecordSnapshot(
                            null,
                            channelId,
                            file.path().toString(),
                            file.size(),
                            startTime,
                            endTime,
                            file.modifiedAt().toString());
                })
                .toList();
        storageRepository.refreshRecordFiles(snapshots);
    }

    /**
     * Parses channelId from file path. Looks for a parent directory matching
     * "ch{channelId}".
     * Example path: .../record/rtp/ch34020000001320000001/2026-02-17/xxx.mp4
     */
    private String parseChannelIdFromPath(Path filePath) {
        Path parent = filePath.getParent();
        while (parent != null) {
            String dirName = parent.getFileName() != null ? parent.getFileName().toString() : "";
            Matcher matcher = CHANNEL_DIR_PATTERN.matcher(dirName);
            if (matcher.matches()) {
                return matcher.group(1);
            }
            parent = parent.getParent();
        }
        return null;
    }

    /**
     * Parses start time from filename. Expected format: 2026-02-17-17-31-13-0.mp4
     * Returns ISO LocalDateTime string: 2026-02-17T17:31:13
     */
    private String parseStartTimeFromFilename(Path filePath) {
        String filename = filePath.getFileName().toString();
        Matcher matcher = FILENAME_TIME_PATTERN.matcher(filename);
        if (matcher.matches()) {
            String year = matcher.group(1);
            String month = matcher.group(2);
            String day = matcher.group(3);
            String hour = matcher.group(4);
            String minute = matcher.group(5);
            String second = matcher.group(6);
            return "%s-%s-%sT%s:%s:%s".formatted(year, month, day, hour, minute, second);
        }
        return null;
    }

    private StorageUsage buildUsage(List<RecordFile> files, int maxStorageGb) {
        long totalBytes = files.stream().mapToLong(RecordFile::size).sum();
        double usedGb = totalBytes / 1024d / 1024d / 1024d;
        double usagePercent = maxStorageGb <= 0 ? 0d : Math.min(100d, usedGb * 100d / maxStorageGb);

        String oldest = files.stream()
                .map(RecordFile::modifiedAt)
                .min(Comparator.naturalOrder())
                .map(Instant::toString)
                .orElse(null);
        String newest = files.stream()
                .map(RecordFile::modifiedAt)
                .max(Comparator.naturalOrder())
                .map(Instant::toString)
                .orElse(null);

        return new StorageUsage(
                files.size(),
                totalBytes,
                usedGb,
                maxStorageGb,
                usagePercent,
                oldest,
                newest);
    }

    private boolean safeDelete(Path path) {
        try {
            return Files.deleteIfExists(path);
        } catch (IOException ex) {
            log.warn("删除文件失败: {}, {}", path, ex.getMessage());
            return false;
        }
    }

    public record UpdatePolicyCommand(
            int retentionDays,
            int maxStorageGb,
            boolean autoOverwrite,
            boolean recordEnabled,
            String recordPath) {
    }

    public record PlaybackChannel(String channelId, int fileCount) {
    }

    private record RecordFile(Path path, long size, Instant modifiedAt) {
    }
}
