package com.ownding.video.gb28181;

public record GbMobilePosition(
        long id,
        String deviceId,
        String channelId,
        String time,
        String longitude,
        String latitude,
        String speed,
        String direction,
        String altitude,
        String rawXml,
        String createdAt
) {
}
