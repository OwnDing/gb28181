package com.ownding.video.gb28181;

public record GbAlarmEvent(
        long id,
        String deviceId,
        String channelId,
        String alarmMethod,
        String alarmType,
        String alarmPriority,
        String alarmTime,
        String longitude,
        String latitude,
        String description,
        String rawXml,
        String createdAt
) {
}
