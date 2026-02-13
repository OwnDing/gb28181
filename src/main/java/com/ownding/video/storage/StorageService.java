package com.ownding.video.storage;

import com.ownding.video.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mp4", "mkv", "ts", "flv", "ps", "h264", "h265", "hevc"
    );

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
                command.recordPath()
        );
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
                .map(file -> new StorageRepository.RecordSnapshot(
                        null,
                        null,
                        file.path().toString(),
                        file.size(),
                        file.modifiedAt().toString(),
                        file.modifiedAt().toString(),
                        file.modifiedAt().toString()
                ))
                .toList();
        storageRepository.refreshRecordFiles(snapshots);
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
                newest
        );
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
            String recordPath
    ) {
    }

    private record RecordFile(Path path, long size, Instant modifiedAt) {
    }
}
