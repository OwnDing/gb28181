package com.ownding.video.storage;

public record RecordFileItem(
        long id,
        String deviceId,
        String channelId,
        String filePath,
        long fileSizeBytes,
        String startTime,
        String endTime,
        String createdAt
) {
}
