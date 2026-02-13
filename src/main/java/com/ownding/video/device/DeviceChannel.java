package com.ownding.video.device;

public record DeviceChannel(
        long id,
        long devicePk,
        int channelNo,
        String channelId,
        String name,
        String codec,
        String status,
        String createdAt,
        String updatedAt
) {
}
