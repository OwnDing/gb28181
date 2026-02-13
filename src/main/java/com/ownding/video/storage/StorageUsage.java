package com.ownding.video.storage;

public record StorageUsage(
        long fileCount,
        long usedBytes,
        double usedGb,
        int maxStorageGb,
        double usagePercent,
        String oldestFileTime,
        String newestFileTime
) {
}
