package com.ownding.video.media;

import com.ownding.video.common.ApiException;
import com.ownding.video.config.AppProperties;
import com.ownding.video.device.Device;
import com.ownding.video.device.DeviceChannel;
import com.ownding.video.device.DeviceService;
import com.ownding.video.gb28181.SipSignalService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class PreviewService {

    private final Map<String, SessionHolder> sessionByKey = new ConcurrentHashMap<>();
    private final Map<String, SessionHolder> sessionById = new ConcurrentHashMap<>();

    private final DeviceService deviceService;
    private final ZlmClient zlmClient;
    private final SipSignalService sipSignalService;
    private final AppProperties appProperties;

    public PreviewService(DeviceService deviceService, ZlmClient zlmClient, SipSignalService sipSignalService, AppProperties appProperties) {
        this.deviceService = deviceService;
        this.zlmClient = zlmClient;
        this.sipSignalService = sipSignalService;
        this.appProperties = appProperties;
    }

    public StartPreviewResult startPreview(StartPreviewCommand command) {
        Device device = deviceService.getDevice(command.devicePk());
        if (!device.online()) {
            throw new ApiException(400, "设备离线，无法预览");
        }
        DeviceChannel channel = deviceService.resolveChannel(device.id(), command.channelId());
        String codec = channel.codec().toUpperCase();
        validateCodecSupport(codec, command.browserSupportsH265());

        String sessionKey = buildSessionKey(device.id(), channel.channelId());
        SessionHolder existing = sessionByKey.get(sessionKey);
        if (existing != null) {
            int viewers = existing.viewerCount.incrementAndGet();
            existing.updatedAt = Instant.now().toString();
            return toStartResult(existing, viewers, false, "复用已有会话");
        }

        synchronized (this) {
            SessionHolder current = sessionByKey.get(sessionKey);
            if (current != null) {
                int viewers = current.viewerCount.incrementAndGet();
                current.updatedAt = Instant.now().toString();
                return toStartResult(current, viewers, false, "复用已有会话");
            }

            String streamId = buildStreamId(device.deviceId(), channel.channelNo());
            String app = appProperties.getZlm().getDefaultApp();
            Integer rtpPort = zlmClient.openRtpServer(streamId, "TCP".equalsIgnoreCase(device.transport()));
            if (rtpPort == null || rtpPort <= 0) {
                throw new ApiException(502, "无法在ZLMediaKit创建RTP端口");
            }

            String ssrc = sipSignalService.generateSsrc();
            SipSignalService.InviteResult inviteResult = sipSignalService.invite(new SipSignalService.InviteCommand(
                    device.deviceId(),
                    device.ip(),
                    device.port(),
                    channel.channelId(),
                    device.transport().toUpperCase(),
                    rtpPort,
                    ssrc
            ));
            if (!inviteResult.success()) {
                zlmClient.closeRtpServer(streamId);
                throw new ApiException(502, "GB28181 INVITE失败: " + inviteResult.reason());
            }

            PlayUrls urls = zlmClient.buildPlayUrls(app, streamId);

            String protocol = resolveProtocol(command.protocol());
            String playUrl = switch (protocol) {
                case "HLS" -> urls.hlsUrl();
                case "HTTP_FLV" -> urls.httpFlvUrl();
                default -> urls.webrtcPlayerUrl();
            };

            String now = Instant.now().toString();
            SessionHolder created = new SessionHolder(
                    randomSessionId(),
                    sessionKey,
                    device.id(),
                    device.deviceId(),
                    channel.channelId(),
                    channel.codec().toUpperCase(),
                    app,
                    streamId,
                    protocol,
                    playUrl,
                    urls,
                    ssrc,
                    inviteResult.callId(),
                    rtpPort,
                    new AtomicInteger(1),
                    now,
                    now
            );

            sessionByKey.put(sessionKey, created);
            sessionById.put(created.sessionId, created);
            String message = rtpPort == null
                    ? "预览会话已创建，请确认设备向ZLM推流"
                    : "预览会话已创建";
            return toStartResult(created, 1, true, message);
        }
    }

    public void stopPreview(String sessionId) {
        SessionHolder holder = sessionById.get(sessionId);
        if (holder == null) {
            return;
        }
        int viewers = holder.viewerCount.decrementAndGet();
        if (viewers > 0) {
            holder.updatedAt = Instant.now().toString();
            return;
        }

        synchronized (this) {
            SessionHolder check = sessionById.get(sessionId);
            if (check == null) {
                return;
            }
            if (check.viewerCount.get() > 0) {
                return;
            }
            sessionById.remove(sessionId);
            sessionByKey.remove(check.sessionKey);
            sipSignalService.bye(check.sipCallId);
            zlmClient.closeRtpServer(check.streamId);
        }
    }

    public List<SessionStatus> listSessions() {
        return sessionById.values().stream()
                .map(holder -> new SessionStatus(
                        holder.sessionId,
                        holder.devicePk,
                        holder.deviceId,
                        holder.channelId,
                        holder.codec,
                        holder.protocol,
                        holder.playUrl,
                        holder.viewerCount.get(),
                        holder.startedAt,
                        holder.updatedAt
                ))
                .sorted((a, b) -> b.startedAt().compareTo(a.startedAt()))
                .toList();
    }

    private StartPreviewResult toStartResult(SessionHolder holder, int viewers, boolean created, String message) {
        return new StartPreviewResult(
                holder.sessionId,
                holder.devicePk,
                holder.deviceId,
                holder.channelId,
                holder.codec,
                holder.protocol,
                holder.playUrl,
                holder.urls.webrtcPlayerUrl,
                holder.urls.hlsUrl,
                holder.urls.httpFlvUrl,
                holder.urls.rtspUrl,
                holder.urls.rtmpUrl,
                holder.ssrc,
                holder.sipCallId,
                viewers,
                holder.rtpPort,
                created,
                message
        );
    }

    private String buildSessionKey(long devicePk, String channelId) {
        return devicePk + ":" + channelId;
    }

    private String buildStreamId(String deviceId, int channelNo) {
        return (deviceId + "_" + String.format("%03d", channelNo)).replaceAll("[^A-Za-z0-9_\\-]", "");
    }

    private String randomSessionId() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    private String resolveProtocol(String protocol) {
        if (protocol == null || protocol.isBlank()) {
            return "WEBRTC";
        }
        String normalized = protocol.toUpperCase();
        if ("WEBRTC".equals(normalized) || "HLS".equals(normalized) || "HTTP_FLV".equals(normalized)) {
            return normalized;
        }
        throw new ApiException(400, "不支持的预览协议: " + protocol);
    }

    private void validateCodecSupport(String codec, boolean browserSupportsH265) {
        if (!"H264".equals(codec) && !"H265".equals(codec)) {
            throw new ApiException(400, "不支持的编码类型: " + codec);
        }
        if ("H265".equals(codec) && !browserSupportsH265) {
            if (appProperties.getPreview().isEnableH265TranscodeFallback()) {
                return;
            }
            if (!appProperties.getPreview().isAllowH265DirectPlay()) {
                throw new ApiException(409, "当前浏览器不支持 H.265，请切换到支持 HEVC 的浏览器");
            }
        }
    }

    public record StartPreviewCommand(
            long devicePk,
            String channelId,
            String protocol,
            boolean browserSupportsH265
    ) {
    }

    public record StartPreviewResult(
            String sessionId,
            long devicePk,
            String deviceId,
            String channelId,
            String codec,
            String protocol,
            String playUrl,
            String webrtcPlayerUrl,
            String hlsUrl,
            String httpFlvUrl,
            String rtspUrl,
            String rtmpUrl,
            String ssrc,
            String sipCallId,
            int viewerCount,
            Integer rtpPort,
            boolean created,
            String message
    ) {
    }

    public record SessionStatus(
            String sessionId,
            long devicePk,
            String deviceId,
            String channelId,
            String codec,
            String protocol,
            String playUrl,
            int viewerCount,
            String startedAt,
            String updatedAt
    ) {
    }

    public record PlayUrls(
            String webrtcPlayerUrl,
            String hlsUrl,
            String httpFlvUrl,
            String rtspUrl,
            String rtmpUrl
    ) {
    }

    private static class SessionHolder {
        private final String sessionId;
        private final String sessionKey;
        private final long devicePk;
        private final String deviceId;
        private final String channelId;
        private final String codec;
        private final String app;
        private final String streamId;
        private final String protocol;
        private final String playUrl;
        private final PlayUrls urls;
        private final String ssrc;
        private final String sipCallId;
        private final Integer rtpPort;
        private final AtomicInteger viewerCount;
        private final String startedAt;
        private volatile String updatedAt;

        private SessionHolder(
                String sessionId,
                String sessionKey,
                long devicePk,
                String deviceId,
                String channelId,
                String codec,
                String app,
                String streamId,
                String protocol,
                String playUrl,
                PlayUrls urls,
                String ssrc,
                String sipCallId,
                Integer rtpPort,
                AtomicInteger viewerCount,
                String startedAt,
                String updatedAt
        ) {
            this.sessionId = sessionId;
            this.sessionKey = sessionKey;
            this.devicePk = devicePk;
            this.deviceId = deviceId;
            this.channelId = channelId;
            this.codec = codec;
            this.app = app;
            this.streamId = streamId;
            this.protocol = protocol;
            this.playUrl = playUrl;
            this.urls = urls;
            this.ssrc = ssrc;
            this.sipCallId = sipCallId;
            this.rtpPort = rtpPort;
            this.viewerCount = viewerCount;
            this.startedAt = startedAt;
            this.updatedAt = updatedAt;
        }
    }
}
