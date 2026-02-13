package com.ownding.video.gb28181;

public record GbSubscription(
        long id,
        String deviceId,
        String eventType,
        String callId,
        int expires,
        String status,
        String createdAt,
        String updatedAt
) {
}
