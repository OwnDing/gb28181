package com.ownding.video.gb28181;

public record GbPlaybackSession(
        long id,
        String sessionId,
        String deviceId,
        String channelId,
        String streamId,
        String app,
        String ssrc,
        String callId,
        Integer rtpPort,
        String protocol,
        double speed,
        String status,
        String startTime,
        String endTime,
        String createdAt,
        String updatedAt
) {
}
