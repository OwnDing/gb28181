package com.ownding.video.storage;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Repository
public class StorageRepository {

    private static final String POSIX_DEFAULT_RECORD_PATH = "./data/records";
    private static final String WINDOWS_DEFAULT_RECORD_PATH = "C:\\record";

    private final JdbcClient jdbcClient;

    public StorageRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public StoragePolicy getPolicy() {
        String defaultRecordPath = resolveDefaultRecordPath();
        Optional<StoragePolicy> policy = jdbcClient.sql("""
                SELECT retention_days, max_storage_gb, auto_overwrite, record_enabled, record_path, updated_at
                FROM storage_policy
                WHERE id = 1
                """)
                .query((rs, rowNum) -> new StoragePolicy(
                        rs.getInt("retention_days"),
                        rs.getInt("max_storage_gb"),
                        rs.getInt("auto_overwrite") == 1,
                        rs.getInt("record_enabled") == 1,
                        rs.getString("record_path"),
                        rs.getString("updated_at")))
                .optional();

        StoragePolicy existing = policy.orElseGet(() -> createDefaultPolicy(defaultRecordPath));
        return normalizeDefaultRecordPath(existing, defaultRecordPath);
    }

    public StoragePolicy updatePolicy(int retentionDays, int maxStorageGb, boolean autoOverwrite, boolean recordEnabled,
            String recordPath) {
        String now = Instant.now().toString();
        jdbcClient.sql("""
                UPDATE storage_policy
                SET retention_days = :retentionDays,
                    max_storage_gb = :maxStorageGb,
                    auto_overwrite = :autoOverwrite,
                    record_enabled = :recordEnabled,
                    record_path = :recordPath,
                    updated_at = :updatedAt
                WHERE id = 1
                """)
                .param("retentionDays", retentionDays)
                .param("maxStorageGb", maxStorageGb)
                .param("autoOverwrite", autoOverwrite ? 1 : 0)
                .param("recordEnabled", recordEnabled ? 1 : 0)
                .param("recordPath", recordPath)
                .param("updatedAt", now)
                .update();
        return getPolicy();
    }

    public List<RecordFileItem> listRecordFiles() {
        return jdbcClient.sql("""
                SELECT id, device_id, channel_id, file_path, file_size_bytes, start_time, end_time, created_at
                FROM record_file
                ORDER BY created_at DESC
                """)
                .query((rs, rowNum) -> new RecordFileItem(
                        rs.getLong("id"),
                        rs.getString("device_id"),
                        rs.getString("channel_id"),
                        rs.getString("file_path"),
                        rs.getLong("file_size_bytes"),
                        rs.getString("start_time"),
                        rs.getString("end_time"),
                        rs.getString("created_at")))
                .list();
    }

    public Optional<RecordFileItem> findRecordFileById(long id) {
        return jdbcClient.sql("""
                SELECT id, device_id, channel_id, file_path, file_size_bytes, start_time, end_time, created_at
                FROM record_file
                WHERE id = :id
                LIMIT 1
                """)
                .param("id", id)
                .query((rs, rowNum) -> new RecordFileItem(
                        rs.getLong("id"),
                        rs.getString("device_id"),
                        rs.getString("channel_id"),
                        rs.getString("file_path"),
                        rs.getLong("file_size_bytes"),
                        rs.getString("start_time"),
                        rs.getString("end_time"),
                        rs.getString("created_at")))
                .optional();
    }

    public void deleteRecordById(long id) {
        jdbcClient.sql("DELETE FROM record_file WHERE id = :id")
                .param("id", id)
                .update();
    }

    @Transactional
    public void refreshRecordFiles(List<RecordSnapshot> snapshots) {
        jdbcClient.sql("DELETE FROM record_file").update();
        for (RecordSnapshot snapshot : snapshots) {
            jdbcClient
                    .sql("""
                            INSERT INTO record_file (device_id, channel_id, file_path, file_size_bytes, start_time, end_time, created_at)
                            VALUES (:deviceId, :channelId, :filePath, :fileSizeBytes, :startTime, :endTime, :createdAt)
                            """)
                    .param("deviceId", snapshot.deviceId())
                    .param("channelId", snapshot.channelId())
                    .param("filePath", snapshot.filePath())
                    .param("fileSizeBytes", snapshot.fileSizeBytes())
                    .param("startTime", snapshot.startTime())
                    .param("endTime", snapshot.endTime())
                    .param("createdAt", snapshot.createdAt())
                    .update();
        }
    }

    // ── Playback queries ──────────────────────────────────────────

    public List<StorageService.PlaybackChannel> listDistinctChannels() {
        return jdbcClient.sql("""
                SELECT channel_id, COUNT(*) AS file_count
                FROM record_file
                WHERE channel_id IS NOT NULL AND channel_id != ''
                GROUP BY channel_id
                ORDER BY channel_id
                """)
                .query((rs, rowNum) -> new StorageService.PlaybackChannel(
                        rs.getString("channel_id"),
                        rs.getInt("file_count")))
                .list();
    }

    public List<RecordFileItem> findRecordsByChannelAndTimeRange(String channelId, String startTime, String endTime) {
        return jdbcClient.sql("""
                SELECT id, device_id, channel_id, file_path, file_size_bytes, start_time, end_time, created_at
                FROM record_file
                WHERE channel_id = :channelId
                  AND start_time >= :startTime
                  AND start_time <= :endTime
                ORDER BY start_time ASC
                """)
                .param("channelId", channelId)
                .param("startTime", startTime)
                .param("endTime", endTime)
                .query((rs, rowNum) -> new RecordFileItem(
                        rs.getLong("id"),
                        rs.getString("device_id"),
                        rs.getString("channel_id"),
                        rs.getString("file_path"),
                        rs.getLong("file_size_bytes"),
                        rs.getString("start_time"),
                        rs.getString("end_time"),
                        rs.getString("created_at")))
                .list();
    }

    private StoragePolicy createDefaultPolicy(String defaultRecordPath) {
        String now = Instant.now().toString();
        jdbcClient.sql("""
                INSERT OR REPLACE INTO storage_policy (
                    id, retention_days, max_storage_gb, auto_overwrite, record_enabled, record_path, updated_at
                ) VALUES (1, 7, 100, 1, 1, :recordPath, :now)
                """)
                .param("recordPath", defaultRecordPath)
                .param("now", now)
                .update();
        return new StoragePolicy(7, 100, true, true, defaultRecordPath, now);
    }

    private StoragePolicy normalizeDefaultRecordPath(StoragePolicy policy, String defaultRecordPath) {
        boolean shouldReplace = shouldReplaceDefaultRecordPath(policy, defaultRecordPath);
        if (!shouldReplace) {
            return policy;
        }

        String now = Instant.now().toString();
        jdbcClient.sql("""
                UPDATE storage_policy
                SET record_path = :recordPath,
                    updated_at = :updatedAt
                WHERE id = 1
                """)
                .param("recordPath", defaultRecordPath)
                .param("updatedAt", now)
                .update();

        return new StoragePolicy(
                policy.retentionDays(),
                policy.maxStorageGb(),
                policy.autoOverwrite(),
                policy.recordEnabled(),
                defaultRecordPath,
                now);
    }

    private String resolveDefaultRecordPath() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            return WINDOWS_DEFAULT_RECORD_PATH;
        }
        return POSIX_DEFAULT_RECORD_PATH;
    }

    private boolean shouldReplaceDefaultRecordPath(StoragePolicy policy, String defaultRecordPath) {
        String recordPath = policy.recordPath();
        if (recordPath == null || recordPath.isBlank()) {
            return true;
        }

        // One-time migration for rows seeded by historical SQL init values on Windows.
        return WINDOWS_DEFAULT_RECORD_PATH.equals(defaultRecordPath)
                && POSIX_DEFAULT_RECORD_PATH.equals(recordPath)
                && looksLikeLegacySqliteTimestamp(policy.updatedAt());
    }

    private boolean looksLikeLegacySqliteTimestamp(String updatedAt) {
        if (updatedAt == null || updatedAt.isBlank()) {
            return true;
        }
        return updatedAt.contains(" ") && !updatedAt.contains("T");
    }

    public record RecordSnapshot(
            String deviceId,
            String channelId,
            String filePath,
            long fileSizeBytes,
            String startTime,
            String endTime,
            String createdAt) {
    }
}
