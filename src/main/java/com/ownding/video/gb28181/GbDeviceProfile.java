package com.ownding.video.gb28181;

public record GbDeviceProfile(
        String deviceId,
        String name,
        String manufacturer,
        String model,
        String firmware,
        String status,
        String rawXml,
        String updatedAt
) {
}
