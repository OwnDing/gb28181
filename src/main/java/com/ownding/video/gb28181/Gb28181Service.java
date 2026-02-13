package com.ownding.video.gb28181;

import com.ownding.video.common.ApiException;
import com.ownding.video.device.DeviceService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class Gb28181Service {

    private static final DateTimeFormatter GB_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final DeviceService deviceService;
    private final Gb28181Repository repository;
    private final SipSignalService sipSignalService;
    private final AtomicLong snGenerator = new AtomicLong(System.currentTimeMillis() % 1000000L);

    public Gb28181Service(DeviceService deviceService, Gb28181Repository repository, SipSignalService sipSignalService) {
        this.deviceService = deviceService;
        this.repository = repository;
        this.sipSignalService = sipSignalService;
    }

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
        String channelId = command.channelId() == null || command.channelId().isBlank() ? deviceId : command.channelId();

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
                xml
        );
        if (!sipResult.success()) {
            throw new ApiException(502, "取消订阅失败: " + sipResult.reason());
        }
        repository.markSubscriptionInactive(subscriptionId);
        return sipResult;
    }

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

    public record RecordQueryCommand(
            String channelId,
            String startTime,
            String endTime,
            String secrecy,
            String type
    ) {
    }

    public record SubscribeCommand(
            String eventType,
            Integer expires
    ) {
    }

    public record SubscriptionResult(
            GbSubscription subscription,
            SipSignalService.SipCommandResult sipResult
    ) {
    }
}
