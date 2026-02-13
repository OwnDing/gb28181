package com.ownding.video.gb28181;

public record GbRecordItem(
        long id,
        String deviceId,
        String channelId,
        String recordId,
        String name,
        String address,
        String startTime,
        String endTime,
        String secrecy,
        String type,
        String recorderId,
        String filePath,
        String rawXml,
        String updatedAt
) {
}
