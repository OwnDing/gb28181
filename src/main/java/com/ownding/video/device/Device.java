package com.ownding.video.device;

public record Device(
        long id,
        String name,
        String deviceId,
        String ip,
        int port,
        String transport,
        String username,
        String password,
        String manufacturer,
        int channelCount,
        String preferredCodec,
        boolean online,
        String lastSeenAt,
        String createdAt,
        String updatedAt
) {
}
