package com.ownding.video.storage;

public record StoragePolicy(
        int retentionDays,
        int maxStorageGb,
        boolean autoOverwrite,
        boolean recordEnabled,
        String recordPath,
        String updatedAt
) {
}
