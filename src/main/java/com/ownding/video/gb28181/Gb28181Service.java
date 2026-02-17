package com.ownding.video.gb28181;

import com.ownding.video.common.ApiException;
import com.ownding.video.config.AppProperties;
import com.ownding.video.device.Device;
import com.ownding.video.device.DeviceService;
import com.ownding.video.media.PreviewService;
import com.ownding.video.media.ZlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class Gb28181Service {

    private static final Logger log = LoggerFactory.getLogger(Gb28181Service.class);
    private static final DateTimeFormatter GB_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
            .withZone(ZoneOffset.UTC);

    private final DeviceService deviceService;
    private final Gb28181Repository repository;
    private final SipSignalService sipSignalService;
    private final ZlmClient zlmClient;
    private final AppProperties appProperties;
    private final AtomicLong snGenerator = new AtomicLong(System.currentTimeMillis() % 1000000L);

    public Gb28181Service(DeviceService deviceService, Gb28181Repository repository,
            SipSignalService sipSignalService, ZlmClient zlmClient,
            AppProperties appProperties) {
        this.deviceService = deviceService;
        this.repository = repository;
        this.sipSignalService = sipSignalService;
        this.zlmClient = zlmClient;
        this.appProperties = appProperties;
    }

    // ===== Query / Subscription (existing) =====

    public SipSignalService.SipCommandResult queryDeviceInfo(String deviceId) {
        ensureDeviceExists(deviceId);
        String xml = buildQueryXml("DeviceInfo", deviceId, null);
        return sipSignalService.sendMessage(deviceId, xml);
    }

    public SipSignalService.SipCommandResult queryCatalog(String deviceId) {
        ensureDeviceExists(deviceId);
        String xml = buildQueryXml("Catalog", deviceId, null);
        return sipSignalService.sendMessage(deviceId, xml);
    }

    public SipSignalService.SipCommandResult queryRecordInfo(String deviceId, RecordQueryCommand command) {
        ensureDeviceExists(deviceId);
        String startTime = command.startTime() == null || command.startTime().isBlank()
                ? GB_TIME_FORMATTER.format(Instant.now().minusSeconds(24 * 3600))
                : command.startTime();
        String endTime = command.endTime() == null || command.endTime().isBlank()
                ? GB_TIME_FORMATTER.format(Instant.now())
                : command.endTime();
        String channelId = command.channelId() == null || command.channelId().isBlank() ? deviceId
                : command.channelId();

        StringBuilder extra = new StringBuilder(128);
        extra.append("<DeviceID>").append(channelId).append("</DeviceID>\n");
        extra.append("<StartTime>").append(startTime).append("</StartTime>\n");
        extra.append("<EndTime>").append(endTime).append("</EndTime>\n");
        if (command.secrecy() != null && !command.secrecy().isBlank()) {
            extra.append("<Secrecy>").append(command.secrecy()).append("</Secrecy>\n");
        }
        if (command.type() != null && !command.type().isBlank()) {
            extra.append("<Type>").append(command.type()).append("</Type>\n");
        }

        String xml = buildQueryXml("RecordInfo", deviceId, extra.toString());
        return sipSignalService.sendMessage(deviceId, xml);
    }

    public Optional<GbDeviceProfile> getProfile(String deviceId) {
        ensureDeviceExists(deviceId);
        return repository.getDeviceProfile(deviceId);
    }

    public List<GbCatalogItem> listCatalog(String deviceId) {
        ensureDeviceExists(deviceId);
        return repository.listCatalog(deviceId);
    }

    public List<GbRecordItem> listRecordItems(String deviceId, String channelId, int limit) {
        ensureDeviceExists(deviceId);
        return repository.listRecordItems(deviceId, blankToNull(channelId), normalizeLimit(limit));
    }

    public List<GbAlarmEvent> listAlarms(String deviceId, int limit) {
        if (deviceId != null && !deviceId.isBlank()) {
            ensureDeviceExists(deviceId);
            return repository.listAlarmsByDevice(deviceId, normalizeLimit(limit));
        }
        return repository.listAlarms(normalizeLimit(limit));
    }

    public List<GbMobilePosition> listMobilePositions(String deviceId, int limit) {
        if (deviceId != null && !deviceId.isBlank()) {
            ensureDeviceExists(deviceId);
        }
        return repository.listMobilePositions(blankToNull(deviceId), normalizeLimit(limit));
    }

    public List<GbSubscription> listSubscriptions(String deviceId) {
        if (deviceId != null && !deviceId.isBlank()) {
            ensureDeviceExists(deviceId);
        }
        return repository.listSubscriptions(blankToNull(deviceId));
    }

    public List<GbPlaybackSession> listPlaybackSessions() {
        return repository.listPlaybackSessions();
    }

    public SubscriptionResult subscribe(String deviceId, SubscribeCommand command) {
        ensureDeviceExists(deviceId);
        String eventType = normalizeEventType(command.eventType());
        int expires = normalizeExpires(command.expires());

        String xml = buildNotifyXml(eventType, deviceId);
        SipSignalService.SipCommandResult sipResult = sipSignalService.sendSubscribe(deviceId, eventType, expires, xml);
        if (!sipResult.success()) {
            throw new ApiException(502, "订阅失败: " + sipResult.reason());
        }
        GbSubscription subscription = repository.createSubscription(deviceId, eventType, sipResult.callId(), expires);
        return new SubscriptionResult(subscription, sipResult);
    }

    public SipSignalService.SipCommandResult unsubscribe(long subscriptionId) {
        GbSubscription subscription = repository.findSubscriptionById(subscriptionId)
                .orElseThrow(() -> new ApiException(404, "订阅不存在"));
        String xml = buildNotifyXml(subscription.eventType(), subscription.deviceId());
        SipSignalService.SipCommandResult sipResult = sipSignalService.sendSubscribe(
                subscription.deviceId(),
                subscription.eventType(),
                0,
                xml);
        if (!sipResult.success()) {
            throw new ApiException(502, "取消订阅失败: " + sipResult.reason());
        }
        repository.markSubscriptionInactive(subscriptionId);
        return sipResult;
    }

    // ===== Playback (new) =====

    /**
     * Start a GB28181 device playback session.
     */
    public PlaybackStartResult startPlayback(String deviceId, PlaybackCommand command) {
        Device device = deviceService.findDeviceByCode(deviceId)
                .orElseThrow(() -> new ApiException(404, "设备不存在: " + deviceId));
        if (!device.online()) {
            throw new ApiException(400, "设备离线，无法回放");
        }
        String channelId = command.channelId() == null || command.channelId().isBlank()
                ? deviceId
                : command.channelId();
        if (command.startTime() == null || command.startTime().isBlank()) {
            throw new ApiException(400, "startTime 不能为空");
        }
        if (command.endTime() == null || command.endTime().isBlank()) {
            throw new ApiException(400, "endTime 不能为空");
        }

        String sessionId = UUID.randomUUID().toString().replace("-", "");
        String streamId = "playback_" + channelId + "_" + sessionId.substring(0, 8);
        String ssrc = sipSignalService.generatePlaybackSsrc();
        int streamMode = resolveStreamMode(device.transport());
        String app = appProperties.getZlm().getDefaultApp();

        Integer rtpPort = zlmClient.openRtpServer(streamId, streamMode);
        if (rtpPort == null || rtpPort <= 0) {
            throw new ApiException(502, "无法在ZLMediaKit创建RTP端口");
        }
        log.info("playback openRtpServer: deviceId={}, channelId={}, streamId={}, rtpPort={}, ssrc={}",
                deviceId, channelId, streamId, rtpPort, ssrc);

        String sdpIp = appProperties.getGb28181().getMediaIp();
        SipSignalService.InviteResult inviteResult = sipSignalService.invite(
                new SipSignalService.InviteCommand(
                        device.deviceId(), device.ip(), device.port(), channelId,
                        device.transport().toUpperCase(), streamMode, rtpPort, ssrc,
                        sdpIp, streamId, command.startTime(), command.endTime()));
        if (!inviteResult.success()) {
            zlmClient.closeRtpServer(streamId);
            throw new ApiException(502, "回放 INVITE 失败: " + inviteResult.reason());
        }
        log.info("playback invite accepted: callId={}, streamId={}", inviteResult.callId(), streamId);

        boolean streamReady = zlmClient.waitStreamReady(app, streamId, Duration.ofSeconds(15));
        if (!streamReady) {
            log.warn("playback stream not ready in 15s, session saved anyway. streamId={}", streamId);
        }

        GbPlaybackSession session = repository.savePlaybackSession(
                new Gb28181Repository.UpsertPlaybackSessionCommand(
                        sessionId, deviceId, channelId, streamId, app, ssrc,
                        inviteResult.callId(), rtpPort, device.transport(),
                        1.0, streamReady ? "PLAYING" : "PENDING",
                        command.startTime(), command.endTime()));

        Map<String, String> playUrls = toUrlMap(zlmClient.buildPlayUrls(app, streamId));
        return new PlaybackStartResult(session, playUrls, streamReady);
    }

    /**
     * Control an active playback session (pause/resume/speed/seek/teardown).
     */
    public SipSignalService.SipCommandResult controlPlayback(String sessionId, PlaybackControlCommand command) {
        GbPlaybackSession session = repository.findPlaybackSessionBySessionId(sessionId)
                .orElseThrow(() -> new ApiException(404, "回放会话不存在: " + sessionId));
        if ("CLOSED".equals(session.status())) {
            throw new ApiException(400, "回放会话已关闭");
        }
        String callId = session.callId();
        String action = command.action().toUpperCase();
        String mansrtspBody;
        double newSpeed = session.speed();
        String newStatus = session.status();

        switch (action) {
            case "PAUSE" -> {
                mansrtspBody = sipSignalService.buildMansrtspPause();
                newStatus = "PAUSED";
            }
            case "RESUME" -> {
                mansrtspBody = sipSignalService.buildMansrtspPlay(session.speed());
                newStatus = "PLAYING";
            }
            case "SPEED" -> {
                double speed = command.speed() != null ? command.speed() : 1.0;
                if (speed <= 0)
                    speed = 1.0;
                mansrtspBody = sipSignalService.buildMansrtspPlay(speed);
                newSpeed = speed;
                newStatus = "PLAYING";
            }
            case "SEEK" -> {
                long seekSeconds = command.seekSeconds() != null ? command.seekSeconds() : 0;
                mansrtspBody = sipSignalService.buildMansrtspSeek(seekSeconds);
                newStatus = "PLAYING";
            }
            case "TEARDOWN" -> {
                mansrtspBody = sipSignalService.buildMansrtspTeardown();
                newStatus = "CLOSED";
            }
            default -> throw new ApiException(400, "不支持的控制动作: " + action);
        }

        SipSignalService.SipCommandResult result = sipSignalService.sendInfo(callId, mansrtspBody);
        repository.updatePlaybackSessionStatus(sessionId, newStatus, newSpeed);
        return result;
    }

    /**
     * Stop a playback session — send BYE and release RTP.
     */
    public void stopPlayback(String sessionId) {
        GbPlaybackSession session = repository.findPlaybackSessionBySessionId(sessionId)
                .orElseThrow(() -> new ApiException(404, "回放会话不存在: " + sessionId));
        if (!"CLOSED".equals(session.status())) {
            sipSignalService.bye(session.callId());
        }
        if (session.streamId() != null) {
            zlmClient.closeRtpServer(session.streamId());
        }
        repository.updatePlaybackSessionStatus(sessionId, "CLOSED", session.speed());
        log.info("playback stopped: sessionId={}, callId={}", sessionId, session.callId());
    }

    // ===== PTZ Control (new) =====

    /**
     * Send PTZ control command to a device.
     */
    public SipSignalService.SipCommandResult ptzControl(String deviceId, PtzCommand command) {
        Device device = deviceService.findDeviceByCode(deviceId)
                .orElseThrow(() -> new ApiException(404, "设备不存在: " + deviceId));
        if (!device.online()) {
            throw new ApiException(400, "设备离线，无法控制云台");
        }
        String channelId = command.channelId() == null || command.channelId().isBlank()
                ? deviceId
                : command.channelId();
        int speed = command.speed() != null ? command.speed() : 128;
        if (speed < 0)
            speed = 0;
        if (speed > 255)
            speed = 255;
        int address = extractAddress(channelId);

        String ptzCmd;
        String action = command.action().toUpperCase();
        switch (action) {
            case "UP" -> ptzCmd = SipSignalService.ptzUp(address, speed);
            case "DOWN" -> ptzCmd = SipSignalService.ptzDown(address, speed);
            case "LEFT" -> ptzCmd = SipSignalService.ptzLeft(address, speed);
            case "RIGHT" -> ptzCmd = SipSignalService.ptzRight(address, speed);
            case "LEFT_UP" -> ptzCmd = SipSignalService.ptzLeftUp(address, speed);
            case "RIGHT_UP" -> ptzCmd = SipSignalService.ptzRightUp(address, speed);
            case "LEFT_DOWN" -> ptzCmd = SipSignalService.ptzLeftDown(address, speed);
            case "RIGHT_DOWN" -> ptzCmd = SipSignalService.ptzRightDown(address, speed);
            case "ZOOM_IN" -> ptzCmd = SipSignalService.ptzZoomIn(address, speed);
            case "ZOOM_OUT" -> ptzCmd = SipSignalService.ptzZoomOut(address, speed);
            case "STOP" -> ptzCmd = SipSignalService.ptzStop(address);
            case "PRESET_SET" -> {
                int presetNo = command.presetNo() != null ? command.presetNo() : 1;
                ptzCmd = SipSignalService.ptzPresetSet(address, presetNo);
            }
            case "PRESET_CALL" -> {
                int presetNo = command.presetNo() != null ? command.presetNo() : 1;
                ptzCmd = SipSignalService.ptzPresetCall(address, presetNo);
            }
            case "PRESET_DELETE" -> {
                int presetNo = command.presetNo() != null ? command.presetNo() : 1;
                ptzCmd = SipSignalService.ptzPresetDelete(address, presetNo);
            }
            default -> throw new ApiException(400, "不支持的PTZ动作: " + action);
        }

        return sipSignalService.sendPtzControl(deviceId, channelId, ptzCmd);
    }

    // ===== Helpers =====

    private void ensureDeviceExists(String deviceId) {
        deviceService.findDeviceByCode(deviceId)
                .orElseThrow(() -> new ApiException(404, "设备不存在: " + deviceId));
    }

    private String buildQueryXml(String cmdType, String deviceId, String extra) {
        long sn = nextSn();
        String body = extra == null ? "" : extra;
        return """
                <?xml version="1.0" encoding="GB2312"?>
                <Query>
                <CmdType>%s</CmdType>
                <SN>%d</SN>
                <DeviceID>%s</DeviceID>
                %s
                </Query>
                """.formatted(cmdType, sn, deviceId, body);
    }

    private String buildNotifyXml(String eventType, String deviceId) {
        long sn = nextSn();
        return """
                <?xml version="1.0" encoding="GB2312"?>
                <Query>
                <CmdType>%s</CmdType>
                <SN>%d</SN>
                <DeviceID>%s</DeviceID>
                </Query>
                """.formatted(eventType, sn, deviceId);
    }

    private long nextSn() {
        return snGenerator.incrementAndGet();
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return 100;
        }
        return Math.min(limit, 1000);
    }

    private int normalizeExpires(Integer expires) {
        if (expires == null || expires <= 0) {
            return 3600;
        }
        return Math.min(expires, 24 * 3600);
    }

    private String normalizeEventType(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            throw new ApiException(400, "eventType 不能为空");
        }
        String value = eventType.trim();
        if ("Catalog".equalsIgnoreCase(value)) {
            return "Catalog";
        }
        if ("Alarm".equalsIgnoreCase(value)) {
            return "Alarm";
        }
        if ("MobilePosition".equalsIgnoreCase(value)) {
            return "MobilePosition";
        }
        throw new ApiException(400, "不支持的 eventType: " + eventType);
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private int resolveStreamMode(String transport) {
        if (transport != null && transport.toUpperCase().contains("TCP")) {
            return 1; // TCP passive
        }
        return 0; // UDP
    }

    /**
     * Convert PreviewService.PlayUrls record to a plain Map.
     */
    private Map<String, String> toUrlMap(PreviewService.PlayUrls urls) {
        Map<String, String> map = new LinkedHashMap<>();
        if (urls.hlsUrl() != null)
            map.put("hls", urls.hlsUrl());
        if (urls.httpFlvUrl() != null)
            map.put("httpFlv", urls.httpFlvUrl());
        if (urls.rtspUrl() != null)
            map.put("rtsp", urls.rtspUrl());
        if (urls.rtmpUrl() != null)
            map.put("rtmp", urls.rtmpUrl());
        if (urls.webrtcPlayerUrl() != null)
            map.put("webrtc", urls.webrtcPlayerUrl());
        return map;
    }

    /**
     * Extract device address (low 8 bits) from channel ID for PTZ command encoding.
     * Uses the last 2 digits of the channel ID as address, or defaults to 1.
     */
    private int extractAddress(String channelId) {
        if (channelId == null || channelId.length() < 2) {
            return 1;
        }
        try {
            return Integer.parseInt(channelId.substring(channelId.length() - 2)) & 0xFF;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    // ===== DTOs =====

    public record RecordQueryCommand(
            String channelId,
            String startTime,
            String endTime,
            String secrecy,
            String type) {
    }

    public record SubscribeCommand(
            String eventType,
            Integer expires) {
    }

    public record SubscriptionResult(
            GbSubscription subscription,
            SipSignalService.SipCommandResult sipResult) {
    }

    public record PlaybackCommand(
            String channelId,
            String startTime,
            String endTime) {
    }

    public record PlaybackControlCommand(
            String action, // PAUSE, RESUME, SPEED, SEEK, TEARDOWN
            Double speed,
            Long seekSeconds) {
    }

    public record PlaybackStartResult(
            GbPlaybackSession session,
            Map<String, String> playUrls,
            boolean streamReady) {
    }

    public record PtzCommand(
            String channelId,
            String action, // UP, DOWN, LEFT, RIGHT, LEFT_UP, RIGHT_UP, LEFT_DOWN, RIGHT_DOWN, ZOOM_IN,
                           // ZOOM_OUT, STOP, PRESET_SET, PRESET_CALL, PRESET_DELETE
            Integer speed,
            Integer presetNo) {
    }
}
