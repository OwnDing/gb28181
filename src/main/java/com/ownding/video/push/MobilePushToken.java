package com.ownding.video.push;

public record MobilePushToken(
        long id,
        long userId,
        String username,
        String token,
        String tokenType,
        String platform,
        String deviceName,
        String appVersion,
        String permissionStatus,
        String projectId,
        String createdAt,
        String updatedAt,
        String lastSeenAt) {
}
