package com.ownding.video.media;

import com.ownding.video.common.ApiException;
import com.ownding.video.config.AppProperties;
import com.ownding.video.device.Device;
import com.ownding.video.device.DeviceChannel;
import com.ownding.video.device.DeviceService;
import com.ownding.video.gb28181.SipSignalService;
import com.ownding.video.storage.StoragePolicy;
import com.ownding.video.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class PreviewService {

    private static final Logger log = LoggerFactory.getLogger(PreviewService.class);

    private final Map<String, SessionHolder> sessionByKey = new ConcurrentHashMap<>();
    private final Map<String, SessionHolder> sessionById = new ConcurrentHashMap<>();

    private final DeviceService deviceService;
    private final ZlmClient zlmClient;
    private final SipSignalService sipSignalService;
    private final StorageService storageService;
    private final AppProperties appProperties;

    public PreviewService(DeviceService deviceService, ZlmClient zlmClient, SipSignalService sipSignalService,
            StorageService storageService, AppProperties appProperties) {
        this.deviceService = deviceService;
        this.zlmClient = zlmClient;
        this.sipSignalService = sipSignalService;
        this.storageService = storageService;
        this.appProperties = appProperties;
    }

    public StartPreviewResult startPreview(StartPreviewCommand command) {
        Device device = deviceService.getDevice(command.devicePk());
        if (!device.online()) {
            throw new ApiException(400, "设备离线，无法预览");
        }
        DeviceChannel channel = deviceService.resolveChannel(device.id(), command.channelId());
        String codec = normalizeCodec(channel.codec());
        StoragePolicy storagePolicy = storageService.getPolicy();
        RecordingConfig recordingConfig = resolveRecordingConfig(storagePolicy, device, channel);

        String sessionKey = buildSessionKey(device.id(), channel.channelId());
        String app = appProperties.getZlm().getDefaultApp();
        String sdpIp = resolveSdpIpv4(appProperties.getGb28181().getMediaIp());
        if (isLoopbackIp(sdpIp) && !isLoopbackIp(device.ip())) {
            throw new ApiException(400, "请先配置流媒体 SDP 收流地址（app.gb28181.media-ip），不能使用 127.0.0.1");
        }
        warnIfMediaIpNotReachableForDevice(device.ip(), sdpIp);

        synchronized (this) {
            SessionHolder current = sessionByKey.get(sessionKey);
            if (current != null) {
                boolean streamAlive = zlmClient.isStreamReady(current.app, current.streamId);
                if (streamAlive) {
                    int viewers = current.viewerCount.incrementAndGet();
                    current.updatedAt = Instant.now().toString();
                    return toStartResult(current, viewers, false, "复用已有会话");
                }
                log.warn("stale preview session detected, cleanup and recreate. sessionId={}, streamId={}",
                        current.sessionId, current.streamId);
                sessionById.remove(current.sessionId);
                sessionByKey.remove(current.sessionKey);
                if (current.recordingEnabled) {
                    zlmClient.stopMp4Record(current.app, current.streamId);
                }
                sipSignalService.bye(current.sipCallId);
                zlmClient.closeRtpServer(current.streamId);
            }

            String streamId = buildStreamId(channel.channelId());
            String ssrc = sipSignalService.generateSsrc();
            int streamMode = resolveStreamMode(device.transport());
            Integer rtpPort = zlmClient.openRtpServer(streamId, streamMode);
            if (rtpPort == null || rtpPort <= 0) {
                throw new ApiException(502, "无法在ZLMediaKit创建RTP端口");
            }
            log.info(
                    "preview openRtpServer success. deviceId={}, channelId={}, streamId={}, rtpPort={}, streamMode={}, ssrc={}",
                    device.deviceId(), channel.channelId(), streamId, rtpPort, streamMode, ssrc);

            String inviteChannelId = channel.channelId();
            SipSignalService.InviteResult inviteResult = invite(device, inviteChannelId, streamMode, rtpPort, ssrc,
                    sdpIp, streamId);
            if (!inviteResult.success()) {
                zlmClient.closeRtpServer(streamId);
                throw new ApiException(502, "GB28181 INVITE失败: " + inviteResult.reason());
            }
            log.info("preview invite accepted. deviceId={}, inviteChannelId={}, callId={}, streamId={}, rtpPort={}",
                    device.deviceId(), inviteChannelId, inviteResult.callId(), streamId, rtpPort);

            boolean streamReady = zlmClient.waitStreamReady(app, streamId, Duration.ofSeconds(15));
            if (!streamReady && !device.deviceId().equals(inviteChannelId)) {
                // Some single-channel simulators use deviceId itself as the valid channelId.
                sipSignalService.bye(inviteResult.callId());
                log.warn("stream not ready by channel {}, retry invite with deviceId {}. streamId={}",
                        inviteChannelId, device.deviceId(), streamId);
                inviteChannelId = device.deviceId();
                inviteResult = invite(device, inviteChannelId, streamMode, rtpPort, ssrc, sdpIp, streamId);
                if (!inviteResult.success()) {
                    zlmClient.closeRtpServer(streamId);
                    throw new ApiException(502, "GB28181 INVITE失败: " + inviteResult.reason());
                }
                streamReady = zlmClient.waitStreamReady(app, streamId, Duration.ofSeconds(15));
            }

            if (!streamReady && streamMode == 0) {
                log.warn("stream not ready via UDP, retry by TCP passive mode. deviceId={}, streamId={}",
                        device.deviceId(), streamId);
                sipSignalService.bye(inviteResult.callId());
                zlmClient.closeRtpServer(streamId);

                streamMode = 1;
                Integer tcpRtpPort = zlmClient.openRtpServer(streamId, streamMode);
                if (tcpRtpPort == null || tcpRtpPort <= 0) {
                    throw new ApiException(502, "UDP失败后，无法在ZLMediaKit创建TCP模式RTP端口");
                }
                rtpPort = tcpRtpPort;
                log.info(
                        "preview openRtpServer success after udp failed. deviceId={}, channelId={}, streamId={}, rtpPort={}, streamMode={}, ssrc={}",
                        device.deviceId(), channel.channelId(), streamId, rtpPort, streamMode, ssrc);
                inviteChannelId = channel.channelId();
                inviteResult = invite(device, inviteChannelId, streamMode, rtpPort, ssrc, sdpIp, streamId);
                if (!inviteResult.success()) {
                    zlmClient.closeRtpServer(streamId);
                    throw new ApiException(502, "GB28181 TCP INVITE失败: " + inviteResult.reason());
                }
                streamReady = zlmClient.waitStreamReady(app, streamId, Duration.ofSeconds(15));
                if (!streamReady && !device.deviceId().equals(inviteChannelId)) {
                    sipSignalService.bye(inviteResult.callId());
                    inviteChannelId = device.deviceId();
                    inviteResult = invite(device, inviteChannelId, streamMode, rtpPort, ssrc, sdpIp, streamId);
                    if (!inviteResult.success()) {
                        zlmClient.closeRtpServer(streamId);
                        throw new ApiException(502, "GB28181 TCP INVITE失败: " + inviteResult.reason());
                    }
                    streamReady = zlmClient.waitStreamReady(app, streamId, Duration.ofSeconds(15));
                }
            }

            if (!streamReady) {
                log.warn("preview stream not ready in timeout. deviceId={}, channelId={}, streamId={}, rtpPort={}",
                        device.deviceId(), inviteChannelId, streamId, rtpPort);
                sipSignalService.bye(inviteResult.callId());
                zlmClient.closeRtpServer(streamId);
                throw new ApiException(504, "设备已应答但未推流，请检查通道ID、RTP端口映射或设备编码设置");
            }
            String detectedCodec = normalizeCodec(zlmClient.detectStreamCodec(app, streamId));
            String finalCodec = detectedCodec == null ? codec : detectedCodec;
            if (!finalCodec.equals(codec)) {
                log.info("channel codec corrected by stream probe. deviceId={}, channelId={}, dbCodec={}, streamCodec={}",
                        device.deviceId(), channel.channelId(), codec, finalCodec);
                deviceService.updateChannelCodec(device.id(), channel.channelId(), finalCodec);
            }
            if (device.channelCount() <= 1 && !normalizeCodec(device.preferredCodec()).equals(finalCodec)) {
                deviceService.updateDevicePreferredCodec(device.id(), finalCodec);
            }
            try {
                validateCodecSupport(finalCodec, command.browserSupportsH265());
            } catch (ApiException ex) {
                sipSignalService.bye(inviteResult.callId());
                zlmClient.closeRtpServer(streamId);
                throw ex;
            }
            log.info("preview stream ready. deviceId={}, channelId={}, streamId={}, codec={}",
                    device.deviceId(), inviteChannelId, streamId, finalCodec);

            if (recordingConfig.enabled()) {
                boolean recordingStarted = zlmClient.startMp4Record(app, streamId, recordingConfig.zlmRecordPath());
                boolean recordingActive = recordingStarted && waitRecordingStarted(app, streamId, Duration.ofSeconds(3));
                if (!recordingActive) {
                    sipSignalService.bye(inviteResult.callId());
                    zlmClient.closeRtpServer(streamId);
                    throw new ApiException(502, "录像已开启，但启动录像失败，请检查 ZLMediaKit 录制配置");
                }
                log.info("preview recording started. deviceId={}, channelId={}, streamId={}, zlmPath={}, localPath={}",
                        device.deviceId(), channel.channelId(), streamId,
                        recordingConfig.zlmRecordPath(), recordingConfig.localRecordPath());
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
                    finalCodec,
                    app,
                    streamId,
                    protocol,
                    playUrl,
                    urls,
                    ssrc,
                    inviteResult.callId(),
                    rtpPort,
                    recordingConfig.enabled(),
                    recordingConfig.zlmRecordPath(),
                    new AtomicInteger(1),
                    now,
                    now);

            sessionByKey.put(sessionKey, created);
            sessionById.put(created.sessionId, created);
            return toStartResult(created, 1, true, "预览会话已创建");
        }
    }

    public void ensureBackgroundRecording(long devicePk, String channelId) {
        String sessionKey = buildSessionKey(devicePk, channelId);
        SessionHolder existing = sessionByKey.get(sessionKey);
        if (existing != null && zlmClient.isStreamReady(existing.app, existing.streamId)) {
            if (!existing.recordingEnabled) {
                Device device = deviceService.getDevice(devicePk);
                DeviceChannel channel = deviceService.resolveChannel(devicePk, channelId);
                RecordingConfig recordingConfig = resolveRecordingConfig(storageService.getPolicy(), device, channel);
                if (recordingConfig.enabled()) {
                    boolean started = zlmClient.startMp4Record(existing.app, existing.streamId, recordingConfig.zlmRecordPath());
                    boolean active = started && waitRecordingStarted(existing.app, existing.streamId, Duration.ofSeconds(3));
                    if (!active) {
                        throw new ApiException(502, "后台补开录像失败");
                    }
                    existing.recordingEnabled = true;
                    existing.recordPath = recordingConfig.zlmRecordPath();
                }
            }
            existing.backgroundPinned = true;
            existing.updatedAt = Instant.now().toString();
            return;
        }

        StartPreviewResult result = startPreview(new StartPreviewCommand(
                devicePk,
                channelId,
                "WEBRTC",
                true
        ));
        synchronized (this) {
            SessionHolder holder = sessionById.get(result.sessionId());
            if (holder == null) {
                return;
            }
            holder.backgroundPinned = true;
            holder.viewerCount.updateAndGet(value -> value > 0 ? value - 1 : 0);
            holder.updatedAt = Instant.now().toString();
        }
    }

    public void releaseBackgroundRecording(long devicePk, String channelId) {
        String sessionKey = buildSessionKey(devicePk, channelId);
        synchronized (this) {
            SessionHolder holder = sessionByKey.get(sessionKey);
            if (holder == null) {
                return;
            }
            holder.backgroundPinned = false;
            if (holder.viewerCount.get() > 0) {
                holder.updatedAt = Instant.now().toString();
                return;
            }
            closeSession(holder);
        }
    }

    public void stopPreview(String sessionId) {
        SessionHolder holder = sessionById.get(sessionId);
        if (holder == null) {
            return;
        }
        int viewers = holder.viewerCount.updateAndGet(value -> value > 0 ? value - 1 : 0);
        if (viewers > 0 || holder.backgroundPinned) {
            holder.updatedAt = Instant.now().toString();
            return;
        }

        synchronized (this) {
            SessionHolder check = sessionById.get(sessionId);
            if (check == null) {
                return;
            }
            if (check.viewerCount.get() > 0 || check.backgroundPinned) {
                return;
            }
            closeSession(check);
        }
    }

    public List<SessionStatus> listSessions() {
        return sessionById.values().stream()
                .filter(holder -> holder.viewerCount.get() > 0)
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
                        holder.updatedAt))
                .sorted((a, b) -> b.startedAt().compareTo(a.startedAt()))
                .toList();
    }

    public Optional<ChannelRuntime> findChannelRuntime(long devicePk, String channelId) {
        SessionHolder holder = sessionByKey.get(buildSessionKey(devicePk, channelId));
        if (holder == null) {
            return Optional.empty();
        }
        boolean streamReady = zlmClient.isStreamReady(holder.app, holder.streamId);
        boolean recording = holder.recordingEnabled && zlmClient.isMp4Recording(holder.app, holder.streamId);
        return Optional.of(new ChannelRuntime(
                holder.devicePk,
                holder.deviceId,
                holder.channelId,
                holder.app,
                holder.streamId,
                holder.recordingEnabled,
                recording,
                streamReady,
                holder.backgroundPinned,
                holder.viewerCount.get(),
                holder.startedAt,
                holder.updatedAt
        ));
    }

    public WebRtcAnswer playWebRtc(String sessionId, String offerSdp) {
        SessionHolder holder = sessionById.get(sessionId);
        if (holder == null) {
            throw new ApiException(404, "预览会话不存在或已结束");
        }
        if (!zlmClient.isStreamReady(holder.app, holder.streamId)) {
            throw new ApiException(409, "流未就绪，请重新发起预览");
        }
        WebRtcAnswer answer = zlmClient.playWebRtc(holder.app, holder.streamId, offerSdp);
        holder.updatedAt = Instant.now().toString();
        return answer;
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
                message);
    }

    private String buildSessionKey(long devicePk, String channelId) {
        return devicePk + ":" + channelId;
    }

    private String buildStreamId(String channelId) {
        return ("ch" + channelId).replaceAll("[^A-Za-z0-9_\\-]", "");
    }

    private RecordingConfig resolveRecordingConfig(StoragePolicy policy, Device device, DeviceChannel channel) {
        if (policy == null || !policy.recordEnabled()) {
            return new RecordingConfig(false, null, null);
        }
        if (policy.recordPath() == null || policy.recordPath().isBlank()) {
            throw new ApiException(400, "录像目录未配置");
        }

        String deviceSegment = safePathSegment(device.deviceId());
        String channelSegment = safePathSegment(channel.channelId());

        Path basePath = Path.of(policy.recordPath().trim()).toAbsolutePath().normalize();
        Path targetPath = basePath
                .resolve(deviceSegment)
                .resolve(channelSegment);
        try {
            Files.createDirectories(targetPath);
        } catch (IOException ex) {
            throw new ApiException(500, "无法创建录像目录: " + ex.getMessage());
        }
        String localPath = targetPath.toString().replace('\\', '/');
        String zlmPath = buildZlmRecordPath(localPath, deviceSegment, channelSegment);
        return new RecordingConfig(true, localPath, zlmPath);
    }

    private String safePathSegment(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
    }

    private String buildZlmRecordPath(String fallbackLocalPath, String deviceSegment, String channelSegment) {
        String configured = appProperties.getStorage().getZlmRecordPath();
        if (configured == null || configured.isBlank()) {
            return fallbackLocalPath;
        }
        String normalizedBase = configured.trim().replace('\\', '/').replaceAll("/+$", "");
        if (normalizedBase.isBlank()) {
            return fallbackLocalPath;
        }
        return normalizedBase + "/" + deviceSegment + "/" + channelSegment;
    }

    private String randomSessionId() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    private void closeSession(SessionHolder holder) {
        sessionById.remove(holder.sessionId);
        sessionByKey.remove(holder.sessionKey);
        if (holder.recordingEnabled) {
            zlmClient.stopMp4Record(holder.app, holder.streamId);
        }
        sipSignalService.bye(holder.sipCallId);
        zlmClient.closeRtpServer(holder.streamId);
    }

    private boolean waitRecordingStarted(String app, String streamId, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (zlmClient.isMp4Recording(app, streamId)) {
                return true;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return zlmClient.isMp4Recording(app, streamId);
    }

    private int resolveStreamMode(String transport) {
        if ("TCP_ACTIVE".equalsIgnoreCase(transport)) {
            return 2;
        }
        if ("TCP".equalsIgnoreCase(transport)) {
            return 1;
        }
        return 0;
    }

    private String resolveSdpIpv4(String ipOrHost) {
        if (ipOrHost == null || ipOrHost.isBlank()) {
            throw new ApiException(400, "未配置流媒体 SDP 收流地址（app.gb28181.media-ip）");
        }
        String trimmed = ipOrHost.trim();
        if (trimmed.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            return trimmed;
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(trimmed);
            for (InetAddress address : addresses) {
                if (address instanceof Inet4Address ipv4) {
                    return ipv4.getHostAddress();
                }
            }
        } catch (Exception ex) {
            throw new ApiException(400, "无法解析 media-ip 域名: " + trimmed);
        }
        throw new ApiException(400, "media-ip 未解析到 IPv4 地址: " + trimmed);
    }

    private SipSignalService.InviteResult invite(Device device, String channelId, int streamMode, int rtpPort,
            String ssrc, String announcedMediaIp, String streamId) {
        return sipSignalService.invite(new SipSignalService.InviteCommand(
                device.deviceId(),
                device.ip(),
                device.port(),
                channelId,
                device.transport().toUpperCase(),
                streamMode,
                rtpPort,
                ssrc,
                announcedMediaIp,
                streamId));
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

    private void warnIfMediaIpNotReachableForDevice(String deviceIp) {
        warnIfMediaIpNotReachableForDevice(deviceIp, appProperties.getGb28181().getMediaIp());
    }

    private void warnIfMediaIpNotReachableForDevice(String deviceIp, String mediaIp) {
        if (mediaIp == null || mediaIp.isBlank()) {
            return;
        }
        String localIp = appProperties.getGb28181().getLocalIp();
        if (deviceIp != null && localIp != null && deviceIp.trim().equals(localIp.trim())) {
            return;
        }
        boolean mediaLoopback = isLoopbackIp(mediaIp);
        boolean deviceLoopback = isLoopbackIp(deviceIp);
        if (mediaLoopback && !deviceLoopback) {
            log.warn(
                    "gb28181.media-ip={} is loopback, but device ip={} is not loopback. Device may send RTP to wrong host.",
                    mediaIp, deviceIp);
        }
    }

    private boolean isLoopbackIp(String ip) {
        if (ip == null) {
            return false;
        }
        return "127.0.0.1".equals(ip.trim()) || "localhost".equalsIgnoreCase(ip.trim());
    }

    private boolean shouldRetryWithLocalMediaIp(String deviceIp, String currentMediaIp) {
        if (!isLoopbackIp(currentMediaIp)) {
            return false;
        }
        if (isLoopbackIp(deviceIp)) {
            return false;
        }
        String localIp = appProperties.getGb28181().getLocalIp();
        return localIp != null && !localIp.isBlank() && !localIp.trim().equals(currentMediaIp.trim());
    }

    private String resolvePreferredMediaIp(String deviceIp, String configuredMediaIp, String contactHost) {
        List<String> localIps = listLocalIpv4Addresses();

        // When a device simulator runs on the same Windows host, it may bind RTP sockets to the
        // Contact IP (e.g. VPN adapter). In this case, sending RTP to another local subnet can fail.
        if (contactHost != null && !contactHost.isBlank()) {
            String trimmedContactHost = contactHost.trim();
            if (localIps.contains(trimmedContactHost)) {
                log.info("use mediaIp={} because device Contact host is a local address. deviceIp={}, configuredMediaIp={}",
                        trimmedContactHost, deviceIp, configuredMediaIp);
                return trimmedContactHost;
            }
        }

        if (configuredMediaIp == null || configuredMediaIp.isBlank()) {
            String deviceSubnetPrefix = ipv4SubnetPrefix(deviceIp);
            for (String localIp : localIps) {
                if (localIp != null && deviceSubnetPrefix != null && localIp.startsWith(deviceSubnetPrefix)) {
                    log.info("use mediaIp={} based on device ip subnet {}. deviceIp={}, configuredMediaIp is blank",
                            localIp, deviceSubnetPrefix, deviceIp);
                    return localIp;
                }
            }
            return localIps.isEmpty() ? configuredMediaIp : localIps.get(0);
        }

        // If configured IP is loopback (for local testing), force use it.
        if (isLoopbackIp(configuredMediaIp)) {
            return configuredMediaIp;
        }

        String deviceSubnetPrefix = ipv4SubnetPrefix(deviceIp);
        if (deviceSubnetPrefix == null) {
            return configuredMediaIp;
        }
        if (configuredMediaIp.trim().startsWith(deviceSubnetPrefix)) {
            return configuredMediaIp;
        }

        for (String localIp : localIps) {
            if (localIp != null && localIp.startsWith(deviceSubnetPrefix)) {
                log.info("use mediaIp={} based on device ip subnet {}. deviceIp={}, configuredMediaIp={}",
                        localIp, deviceSubnetPrefix, deviceIp, configuredMediaIp);
                return localIp;
            }
        }
        return configuredMediaIp;
    }

    private boolean matchesAnyPrefix(String ip, String... prefixes) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        boolean hasPrefix = false;
        for (String prefix : prefixes) {
            if (prefix == null || prefix.isBlank()) {
                continue;
            }
            hasPrefix = true;
            if (ip.startsWith(prefix)) {
                return true;
            }
        }
        // If no prefixes were provided, don't filter.
        return !hasPrefix;
    }

    private String ipv4SubnetPrefix(String ip) {
        if (ip == null) {
            return null;
        }
        String trimmed = ip.trim();
        if (!trimmed.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            return null;
        }
        int lastDot = trimmed.lastIndexOf('.');
        if (lastDot <= 0) {
            return null;
        }
        return trimmed.substring(0, lastDot + 1);
    }

    private List<String> listLocalIpv4Addresses() {
        List<String> ips = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return List.of();
            }
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface == null || !networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address ipv4 && !ipv4.isLoopbackAddress()) {
                        ips.add(ipv4.getHostAddress());
                    }
                }
            }
        } catch (Exception ex) {
            log.debug("listLocalIpv4Addresses failed: {}", ex.getMessage());
        }
        return ips.stream()
                .filter(ip -> ip != null && !ip.isBlank())
                .distinct()
                .toList();
    }

    private String normalizeCodec(String codec) {
        if (codec == null || codec.isBlank()) {
            return "H264";
        }
        String upper = codec.toUpperCase();
        if (upper.contains("265") || upper.contains("HEVC")) {
            return "H265";
        }
        if (upper.contains("264") || upper.contains("AVC")) {
            return "H264";
        }
        return "H264";
    }

    public record StartPreviewCommand(
            long devicePk,
            String channelId,
            String protocol,
            boolean browserSupportsH265) {
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
            String message) {
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
            String updatedAt) {
    }

    public record ChannelRuntime(
            long devicePk,
            String deviceId,
            String channelId,
            String app,
            String streamId,
            boolean recordingEnabled,
            boolean recording,
            boolean streamReady,
            boolean backgroundPinned,
            int viewerCount,
            String startedAt,
            String updatedAt) {
    }

    public record WebRtcAnswer(
            String type,
            String sdp) {
    }

    public record PlayUrls(
            String webrtcPlayerUrl,
            String hlsUrl,
            String httpFlvUrl,
            String rtspUrl,
            String rtmpUrl) {
    }

    private record RecordingConfig(
            boolean enabled,
            String localRecordPath,
            String zlmRecordPath) {
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
        private volatile boolean recordingEnabled;
        private volatile String recordPath;
        private final AtomicInteger viewerCount;
        private final String startedAt;
        private volatile String updatedAt;
        private volatile boolean backgroundPinned;

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
                boolean recordingEnabled,
                String recordPath,
                AtomicInteger viewerCount,
                String startedAt,
                String updatedAt) {
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
            this.recordingEnabled = recordingEnabled;
            this.recordPath = recordPath;
            this.viewerCount = viewerCount;
            this.startedAt = startedAt;
            this.updatedAt = updatedAt;
            this.backgroundPinned = false;
        }
    }
}
