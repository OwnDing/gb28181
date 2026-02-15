package com.ownding.video.gb28181;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class Gb28181Repository {

    private final JdbcClient jdbcClient;

    public Gb28181Repository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<GbDeviceProfile> getDeviceProfile(String deviceId) {
        return jdbcClient.sql("""
                        SELECT device_id, name, manufacturer, model, firmware, status, raw_xml, updated_at
                        FROM gb_device_profile
                        WHERE device_id = :deviceId
                        LIMIT 1
                        """)
                .param("deviceId", deviceId)
                .query((rs, rowNum) -> new GbDeviceProfile(
                        rs.getString("device_id"),
                        rs.getString("name"),
                        rs.getString("manufacturer"),
                        rs.getString("model"),
                        rs.getString("firmware"),
                        rs.getString("status"),
                        rs.getString("raw_xml"),
                        rs.getString("updated_at")
                ))
                .optional();
    }

    public void upsertDeviceProfile(UpsertDeviceProfileCommand command) {
        String now = Instant.now().toString();
        jdbcClient.sql("""
                        INSERT INTO gb_device_profile (
                            device_id, name, manufacturer, model, firmware, status, raw_xml, updated_at
                        ) VALUES (
                            :deviceId, :name, :manufacturer, :model, :firmware, :status, :rawXml, :updatedAt
                        )
                        ON CONFLICT(device_id) DO UPDATE SET
                            name = excluded.name,
                            manufacturer = excluded.manufacturer,
                            model = excluded.model,
                            firmware = excluded.firmware,
                            status = excluded.status,
                            raw_xml = excluded.raw_xml,
                            updated_at = excluded.updated_at
                        """)
                .param("deviceId", command.deviceId())
                .param("name", command.name())
                .param("manufacturer", command.manufacturer())
                .param("model", command.model())
                .param("firmware", command.firmware())
                .param("status", command.status())
                .param("rawXml", command.rawXml())
                .param("updatedAt", now)
                .update();
    }

    @Transactional
    public void replaceRecordItems(String deviceId, String channelId, List<UpsertRecordItemCommand> items) {
        jdbcClient.sql("""
                        DELETE FROM gb_record_item
                        WHERE device_id = :deviceId
                          AND (:channelId IS NULL OR channel_id = :channelId)
                        """)
                .param("deviceId", deviceId)
                .param("channelId", channelId)
                .update();

        String now = Instant.now().toString();
        for (UpsertRecordItemCommand item : items) {
            jdbcClient.sql("""
                            INSERT INTO gb_record_item (
                                device_id, channel_id, record_id, name, address, start_time, end_time,
                                secrecy, type, recorder_id, file_path, raw_xml, updated_at
                            ) VALUES (
                                :deviceId, :channelId, :recordId, :name, :address, :startTime, :endTime,
                                :secrecy, :type, :recorderId, :filePath, :rawXml, :updatedAt
                            )
                            """)
                    .param("deviceId", deviceId)
                    .param("channelId", item.channelId())
                    .param("recordId", item.recordId())
                    .param("name", item.name())
                    .param("address", item.address())
                    .param("startTime", item.startTime())
                    .param("endTime", item.endTime())
                    .param("secrecy", item.secrecy())
                    .param("type", item.type())
                    .param("recorderId", item.recorderId())
                    .param("filePath", item.filePath())
                    .param("rawXml", item.rawXml())
                    .param("updatedAt", now)
                    .update();
        }
    }

    public List<GbRecordItem> listRecordItems(String deviceId, String channelId, int limit) {
        return jdbcClient.sql("""
                        SELECT id, device_id, channel_id, record_id, name, address, start_time, end_time,
                               secrecy, type, recorder_id, file_path, raw_xml, updated_at
                        FROM gb_record_item
                        WHERE device_id = :deviceId
                          AND (:channelId IS NULL OR channel_id = :channelId)
                        ORDER BY start_time DESC
                        LIMIT :limit
                        """)
                .param("deviceId", deviceId)
                .param("channelId", channelId)
                .param("limit", limit)
                .query((rs, rowNum) -> new GbRecordItem(
                        rs.getLong("id"),
                        rs.getString("device_id"),
                        rs.getString("channel_id"),
                        rs.getString("record_id"),
                        rs.getString("name"),
                        rs.getString("address"),
                        rs.getString("start_time"),
                        rs.getString("end_time"),
                        rs.getString("secrecy"),
                        rs.getString("type"),
                        rs.getString("recorder_id"),
                        rs.getString("file_path"),
                        rs.getString("raw_xml"),
                        rs.getString("updated_at")
                ))
                .list();
    }

    public void insertAlarm(UpsertAlarmEventCommand command) {
        String now = Instant.now().toString();
        jdbcClient.sql("""
                        INSERT INTO gb_alarm_event (
                            device_id, channel_id, alarm_method, alarm_type, alarm_priority,
                            alarm_time, longitude, latitude, description, raw_xml, created_at
                        ) VALUES (
                            :deviceId, :channelId, :alarmMethod, :alarmType, :alarmPriority,
                            :alarmTime, :longitude, :latitude, :description, :rawXml, :createdAt
                        )
                        """)
                .param("deviceId", command.deviceId())
                .param("channelId", command.channelId())
                .param("alarmMethod", command.alarmMethod())
                .param("alarmType", command.alarmType())
                .param("alarmPriority", command.alarmPriority())
                .param("alarmTime", command.alarmTime())
                .param("longitude", command.longitude())
                .param("latitude", command.latitude())
                .param("description", command.description())
                .param("rawXml", command.rawXml())
                .param("createdAt", now)
                .update();
    }

    public List<GbAlarmEvent> listAlarms(int limit) {
        return jdbcClient.sql("""
                        SELECT id, device_id, channel_id, alarm_method, alarm_type, alarm_priority,
                               alarm_time, longitude, latitude, description, raw_xml, created_at
                        FROM gb_alarm_event
                        ORDER BY created_at DESC
                        LIMIT :limit
                        """)
                .param("limit", limit)
                .query((rs, rowNum) -> new GbAlarmEvent(
                        rs.getLong("id"),
                        rs.getString("device_id"),
                        rs.getString("channel_id"),
                        rs.getString("alarm_method"),
                        rs.getString("alarm_type"),
                        rs.getString("alarm_priority"),
                        rs.getString("alarm_time"),
                        rs.getString("longitude"),
                        rs.getString("latitude"),
                        rs.getString("description"),
                        rs.getString("raw_xml"),
                        rs.getString("created_at")
                ))
                .list();
    }

    public void insertMobilePosition(UpsertMobilePositionCommand command) {
        String now = Instant.now().toString();
        jdbcClient.sql("""
                        INSERT INTO gb_mobile_position (
                            device_id, channel_id, time, longitude, latitude, speed, direction, altitude, raw_xml, created_at
                        ) VALUES (
                            :deviceId, :channelId, :time, :longitude, :latitude, :speed, :direction, :altitude, :rawXml, :createdAt
                        )
                        """)
                .param("deviceId", command.deviceId())
                .param("channelId", command.channelId())
                .param("time", command.time())
                .param("longitude", command.longitude())
                .param("latitude", command.latitude())
                .param("speed", command.speed())
                .param("direction", command.direction())
                .param("altitude", command.altitude())
                .param("rawXml", command.rawXml())
                .param("createdAt", now)
                .update();
    }

    public List<GbMobilePosition> listMobilePositions(String deviceId, int limit) {
        return jdbcClient.sql("""
                        SELECT id, device_id, channel_id, time, longitude, latitude, speed, direction, altitude, raw_xml, created_at
                        FROM gb_mobile_position
                        WHERE (:deviceId IS NULL OR device_id = :deviceId)
                        ORDER BY created_at DESC
                        LIMIT :limit
                        """)
                .param("deviceId", deviceId)
                .param("limit", limit)
                .query((rs, rowNum) -> new GbMobilePosition(
                        rs.getLong("id"),
                        rs.getString("device_id"),
                        rs.getString("channel_id"),
                        rs.getString("time"),
                        rs.getString("longitude"),
                        rs.getString("latitude"),
                        rs.getString("speed"),
                        rs.getString("direction"),
                        rs.getString("altitude"),
                        rs.getString("raw_xml"),
                        rs.getString("created_at")
                ))
                .list();
    }

    public List<GbSubscription> listSubscriptions(String deviceId) {
        return jdbcClient.sql("""
                        SELECT id, device_id, event_type, call_id, expires, status, created_at, updated_at
                        FROM gb_subscription
                        WHERE (:deviceId IS NULL OR device_id = :deviceId)
                        ORDER BY id DESC
                        """)
                .param("deviceId", deviceId)
                .query((rs, rowNum) -> new GbSubscription(
                        rs.getLong("id"),
                        rs.getString("device_id"),
                        rs.getString("event_type"),
                        rs.getString("call_id"),
                        rs.getInt("expires"),
                        rs.getString("status"),
                        rs.getString("created_at"),
                        rs.getString("updated_at")
                ))
                .list();
    }

    public GbSubscription createSubscription(String deviceId, String eventType, String callId, int expires) {
        String now = Instant.now().toString();
        Long id = jdbcClient.sql("""
                        INSERT INTO gb_subscription (device_id, event_type, call_id, expires, status, created_at, updated_at)
                        VALUES (:deviceId, :eventType, :callId, :expires, 'ACTIVE', :createdAt, :updatedAt)
                        RETURNING id
                        """)
                .param("deviceId", deviceId)
                .param("eventType", eventType)
                .param("callId", callId)
                .param("expires", expires)
                .param("createdAt", now)
                .param("updatedAt", now)
                .query(Long.class)
                .single();
        return findSubscriptionById(id).orElseThrow();
    }

    public Optional<GbSubscription> findSubscriptionById(long id) {
        return jdbcClient.sql("""
                        SELECT id, device_id, event_type, call_id, expires, status, created_at, updated_at
                        FROM gb_subscription
                        WHERE id = :id
                        LIMIT 1
                        """)
                .param("id", id)
                .query((rs, rowNum) -> new GbSubscription(
                        rs.getLong("id"),
                        rs.getString("device_id"),
                        rs.getString("event_type"),
                        rs.getString("call_id"),
                        rs.getInt("expires"),
                        rs.getString("status"),
                        rs.getString("created_at"),
                        rs.getString("updated_at")
                ))
                .optional();
    }

    public void markSubscriptionInactive(long id) {
        String now = Instant.now().toString();
        jdbcClient.sql("""
                        UPDATE gb_subscription
                        SET status = 'INACTIVE', updated_at = :updatedAt
                        WHERE id = :id
                        """)
                .param("updatedAt", now)
                .param("id", id)
                .update();
    }

    public GbPlaybackSession savePlaybackSession(UpsertPlaybackSessionCommand command) {
        String now = Instant.now().toString();
        jdbcClient.sql("""
                        INSERT INTO gb_playback_session (
                            session_id, device_id, channel_id, stream_id, app, ssrc, call_id, rtp_port,
                            protocol, speed, status, start_time, end_time, created_at, updated_at
                        ) VALUES (
                            :sessionId, :deviceId, :channelId, :streamId, :app, :ssrc, :callId, :rtpPort,
                            :protocol, :speed, :status, :startTime, :endTime, :createdAt, :updatedAt
                        )
                        ON CONFLICT(session_id) DO UPDATE SET
                            call_id = excluded.call_id,
                            rtp_port = excluded.rtp_port,
                            speed = excluded.speed,
                            status = excluded.status,
                            updated_at = excluded.updated_at
                        """)
                .param("sessionId", command.sessionId())
                .param("deviceId", command.deviceId())
                .param("channelId", command.channelId())
                .param("streamId", command.streamId())
                .param("app", command.app())
                .param("ssrc", command.ssrc())
                .param("callId", command.callId())
                .param("rtpPort", command.rtpPort())
                .param("protocol", command.protocol())
                .param("speed", command.speed())
                .param("status", command.status())
                .param("startTime", command.startTime())
                .param("endTime", command.endTime())
                .param("createdAt", now)
                .param("updatedAt", now)
                .update();
        return findPlaybackSessionBySessionId(command.sessionId()).orElseThrow();
    }

    public Optional<GbPlaybackSession> findPlaybackSessionBySessionId(String sessionId) {
        return jdbcClient.sql("""
                        SELECT id, session_id, device_id, channel_id, stream_id, app, ssrc, call_id, rtp_port,
                               protocol, speed, status, start_time, end_time, created_at, updated_at
                        FROM gb_playback_session
                        WHERE session_id = :sessionId
                        LIMIT 1
                        """)
                .param("sessionId", sessionId)
                .query((rs, rowNum) -> new GbPlaybackSession(
                        rs.getLong("id"),
                        rs.getString("session_id"),
                        rs.getString("device_id"),
                        rs.getString("channel_id"),
                        rs.getString("stream_id"),
                        rs.getString("app"),
                        rs.getString("ssrc"),
                        rs.getString("call_id"),
                        rs.getObject("rtp_port", Integer.class),
                        rs.getString("protocol"),
                        rs.getDouble("speed"),
                        rs.getString("status"),
                        rs.getString("start_time"),
                        rs.getString("end_time"),
                        rs.getString("created_at"),
                        rs.getString("updated_at")
                ))
                .optional();
    }

    public List<GbPlaybackSession> listPlaybackSessions() {
        return jdbcClient.sql("""
                        SELECT id, session_id, device_id, channel_id, stream_id, app, ssrc, call_id, rtp_port,
                               protocol, speed, status, start_time, end_time, created_at, updated_at
                        FROM gb_playback_session
                        ORDER BY created_at DESC
                        """)
                .query((rs, rowNum) -> new GbPlaybackSession(
                        rs.getLong("id"),
                        rs.getString("session_id"),
                        rs.getString("device_id"),
                        rs.getString("channel_id"),
                        rs.getString("stream_id"),
                        rs.getString("app"),
                        rs.getString("ssrc"),
                        rs.getString("call_id"),
                        rs.getObject("rtp_port", Integer.class),
                        rs.getString("protocol"),
                        rs.getDouble("speed"),
                        rs.getString("status"),
                        rs.getString("start_time"),
                        rs.getString("end_time"),
                        rs.getString("created_at"),
                        rs.getString("updated_at")
                ))
                .list();
    }

    public void updatePlaybackSessionStatus(String sessionId, String status, double speed) {
        String now = Instant.now().toString();
        jdbcClient.sql("""
                        UPDATE gb_playback_session
                        SET status = :status, speed = :speed, updated_at = :updatedAt
                        WHERE session_id = :sessionId
                        """)
                .param("status", status)
                .param("speed", speed)
                .param("updatedAt", now)
                .param("sessionId", sessionId)
                .update();
    }

    @Transactional
    public void syncCatalog(String deviceId, List<UpsertCatalogItemCommand> items) {
        Optional<DevicePkAndCodec> deviceInfo = jdbcClient.sql("""
                        SELECT id, preferred_codec
                        FROM gb_device
                        WHERE device_id = :deviceId
                        LIMIT 1
                        """)
                .param("deviceId", deviceId)
                .query((rs, rowNum) -> new DevicePkAndCodec(
                        rs.getLong("id"),
                        rs.getString("preferred_codec")))
                .optional();
        if (deviceInfo.isEmpty()) {
            return;
        }

        long pk = deviceInfo.get().devicePk();
        String preferredCodec = normalizeCodec(deviceInfo.get().preferredCodec());
        if (preferredCodec == null) {
            preferredCodec = "H264";
        }
        Map<String, String> existingCodecByChannelId = new HashMap<>();
        jdbcClient.sql("""
                        SELECT channel_id, codec
                        FROM gb_channel
                        WHERE device_pk = :devicePk
                        """)
                .param("devicePk", pk)
                .query((rs, rowNum) -> {
                    existingCodecByChannelId.put(rs.getString("channel_id"), rs.getString("codec"));
                    return 0;
                })
                .list();

        String now = Instant.now().toString();
        jdbcClient.sql("DELETE FROM gb_channel WHERE device_pk = :devicePk")
                .param("devicePk", pk)
                .update();

        int channelNo = 1;
        for (UpsertCatalogItemCommand item : items) {
            String codec = normalizeCodec(item.codec());
            if (codec == null) {
                codec = normalizeCodec(existingCodecByChannelId.get(item.channelId()));
            }
            if (codec == null) {
                codec = preferredCodec;
            }
            jdbcClient.sql("""
                            INSERT INTO gb_channel (device_pk, channel_no, channel_id, name, codec, status, created_at, updated_at)
                            VALUES (:devicePk, :channelNo, :channelId, :name, :codec, :status, :now, :now)
                            ON CONFLICT(channel_id) DO UPDATE SET
                                device_pk = excluded.device_pk,
                                channel_no = excluded.channel_no,
                                name = excluded.name,
                                status = excluded.status,
                                updated_at = excluded.updated_at
                            """)
                    .param("devicePk", pk)
                    .param("channelNo", channelNo++)
                    .param("channelId", item.channelId())
                    .param("name", item.name() == null || item.name().isBlank() ? item.channelId() : item.name())
                    .param("codec", codec)
                    .param("status", item.status() == null ? "OFFLINE" : item.status().toUpperCase())
                    .param("now", now)
                    .update();
        }

        jdbcClient.sql("""
                        UPDATE gb_device
                        SET channel_count = :channelCount, updated_at = :updatedAt
                        WHERE id = :devicePk
                        """)
                .param("channelCount", items.size())
                .param("updatedAt", now)
                .param("devicePk", pk)
                .update();
    }

    private String normalizeCodec(String codec) {
        if (codec == null || codec.isBlank()) {
            return null;
        }
        String upper = codec.toUpperCase();
        if (upper.contains("265") || upper.contains("HEVC")) {
            return "H265";
        }
        if (upper.contains("264") || upper.contains("AVC")) {
            return "H264";
        }
        return null;
    }

    public List<GbCatalogItem> listCatalog(String deviceId) {
        Optional<Long> devicePk = jdbcClient.sql("SELECT id FROM gb_device WHERE device_id = :deviceId LIMIT 1")
                .param("deviceId", deviceId)
                .query(Long.class)
                .optional();
        if (devicePk.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql("""
                        SELECT channel_id, name, status
                        FROM gb_channel
                        WHERE device_pk = :devicePk
                        ORDER BY channel_no ASC
                        """)
                .param("devicePk", devicePk.get())
                .query((rs, rowNum) -> new GbCatalogItem(
                        rs.getString("channel_id"),
                        rs.getString("name"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        rs.getString("status")
                ))
                .list();
    }

    public List<GbAlarmEvent> listAlarmsByDevice(String deviceId, int limit) {
        return jdbcClient.sql("""
                        SELECT id, device_id, channel_id, alarm_method, alarm_type, alarm_priority,
                               alarm_time, longitude, latitude, description, raw_xml, created_at
                        FROM gb_alarm_event
                        WHERE (:deviceId IS NULL OR device_id = :deviceId)
                        ORDER BY created_at DESC
                        LIMIT :limit
                        """)
                .param("deviceId", deviceId)
                .param("limit", limit)
                .query((rs, rowNum) -> new GbAlarmEvent(
                        rs.getLong("id"),
                        rs.getString("device_id"),
                        rs.getString("channel_id"),
                        rs.getString("alarm_method"),
                        rs.getString("alarm_type"),
                        rs.getString("alarm_priority"),
                        rs.getString("alarm_time"),
                        rs.getString("longitude"),
                        rs.getString("latitude"),
                        rs.getString("description"),
                        rs.getString("raw_xml"),
                        rs.getString("created_at")
                ))
                .list();
    }

    public record UpsertDeviceProfileCommand(
            String deviceId,
            String name,
            String manufacturer,
            String model,
            String firmware,
            String status,
            String rawXml
    ) {
    }

    public record UpsertRecordItemCommand(
            String channelId,
            String recordId,
            String name,
            String address,
            String startTime,
            String endTime,
            String secrecy,
            String type,
            String recorderId,
            String filePath,
            String rawXml
    ) {
    }

    public record UpsertAlarmEventCommand(
            String deviceId,
            String channelId,
            String alarmMethod,
            String alarmType,
            String alarmPriority,
            String alarmTime,
            String longitude,
            String latitude,
            String description,
            String rawXml
    ) {
    }

    public record UpsertMobilePositionCommand(
            String deviceId,
            String channelId,
            String time,
            String longitude,
            String latitude,
            String speed,
            String direction,
            String altitude,
            String rawXml
    ) {
    }

    public record UpsertPlaybackSessionCommand(
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
            String endTime
    ) {
    }

    public record UpsertCatalogItemCommand(
            String channelId,
            String name,
            String codec,
            String status
    ) {
    }

    private record DevicePkAndCodec(long devicePk, String preferredCodec) {
    }
}
