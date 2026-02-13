package com.ownding.video.device;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class DeviceRepository {

    private final JdbcClient jdbcClient;

    public DeviceRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Device> findAllDevices() {
        return jdbcClient.sql("""
                        SELECT id, name, device_id, ip, port, transport, username, password, manufacturer,
                               channel_count, preferred_codec, online, last_seen_at, created_at, updated_at
                        FROM gb_device
                        ORDER BY id DESC
                        """)
                .query((rs, rowNum) -> new Device(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("device_id"),
                        rs.getString("ip"),
                        rs.getInt("port"),
                        rs.getString("transport"),
                        rs.getString("username"),
                        rs.getString("password"),
                        rs.getString("manufacturer"),
                        rs.getInt("channel_count"),
                        rs.getString("preferred_codec"),
                        rs.getInt("online") == 1,
                        rs.getString("last_seen_at"),
                        rs.getString("created_at"),
                        rs.getString("updated_at")
                ))
                .list();
    }

    public Optional<Device> findDeviceById(long id) {
        return jdbcClient.sql("""
                        SELECT id, name, device_id, ip, port, transport, username, password, manufacturer,
                               channel_count, preferred_codec, online, last_seen_at, created_at, updated_at
                        FROM gb_device
                        WHERE id = :id
                        LIMIT 1
                        """)
                .param("id", id)
                .query((rs, rowNum) -> new Device(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("device_id"),
                        rs.getString("ip"),
                        rs.getInt("port"),
                        rs.getString("transport"),
                        rs.getString("username"),
                        rs.getString("password"),
                        rs.getString("manufacturer"),
                        rs.getInt("channel_count"),
                        rs.getString("preferred_codec"),
                        rs.getInt("online") == 1,
                        rs.getString("last_seen_at"),
                        rs.getString("created_at"),
                        rs.getString("updated_at")
                ))
                .optional();
    }

    public Optional<Device> findDeviceByCode(String deviceCode) {
        return jdbcClient.sql("""
                        SELECT id, name, device_id, ip, port, transport, username, password, manufacturer,
                               channel_count, preferred_codec, online, last_seen_at, created_at, updated_at
                        FROM gb_device
                        WHERE device_id = :deviceCode
                        LIMIT 1
                        """)
                .param("deviceCode", deviceCode)
                .query((rs, rowNum) -> new Device(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("device_id"),
                        rs.getString("ip"),
                        rs.getInt("port"),
                        rs.getString("transport"),
                        rs.getString("username"),
                        rs.getString("password"),
                        rs.getString("manufacturer"),
                        rs.getInt("channel_count"),
                        rs.getString("preferred_codec"),
                        rs.getInt("online") == 1,
                        rs.getString("last_seen_at"),
                        rs.getString("created_at"),
                        rs.getString("updated_at")
                ))
                .optional();
    }

    @Transactional
    public Device createDevice(CreateDeviceRequest request) {
        String now = Instant.now().toString();
        Long id = jdbcClient.sql("""
                        INSERT INTO gb_device (
                            name, device_id, ip, port, transport, username, password, manufacturer,
                            channel_count, preferred_codec, online, created_at, updated_at
                        )
                        VALUES (
                            :name, :deviceId, :ip, :port, :transport, :username, :password, :manufacturer,
                            :channelCount, :preferredCodec, 0, :now, :now
                        )
                        RETURNING id
                        """)
                .param("name", request.name())
                .param("deviceId", request.deviceId())
                .param("ip", request.ip())
                .param("port", request.port())
                .param("transport", request.transport())
                .param("username", request.username())
                .param("password", request.password())
                .param("manufacturer", request.manufacturer())
                .param("channelCount", request.channelCount())
                .param("preferredCodec", request.preferredCodec())
                .param("now", now)
                .query(Long.class)
                .single();

        recreateChannels(id, request.deviceId(), request.channelCount(), request.preferredCodec());
        return findDeviceById(id).orElseThrow();
    }

    @Transactional
    public Device updateDevice(long id, UpdateDeviceRequest request) {
        String now = Instant.now().toString();
        jdbcClient.sql("""
                        UPDATE gb_device
                        SET name = :name,
                            device_id = :deviceId,
                            ip = :ip,
                            port = :port,
                            transport = :transport,
                            username = :username,
                            password = :password,
                            manufacturer = :manufacturer,
                            channel_count = :channelCount,
                            preferred_codec = :preferredCodec,
                            updated_at = :now
                        WHERE id = :id
                        """)
                .param("name", request.name())
                .param("deviceId", request.deviceId())
                .param("ip", request.ip())
                .param("port", request.port())
                .param("transport", request.transport())
                .param("username", request.username())
                .param("password", request.password())
                .param("manufacturer", request.manufacturer())
                .param("channelCount", request.channelCount())
                .param("preferredCodec", request.preferredCodec())
                .param("now", now)
                .param("id", id)
                .update();

        recreateChannels(id, request.deviceId(), request.channelCount(), request.preferredCodec());
        return findDeviceById(id).orElseThrow();
    }

    public int deleteDevice(long id) {
        return jdbcClient.sql("DELETE FROM gb_device WHERE id = :id")
                .param("id", id)
                .update();
    }

    public int updateDeviceOnlineStatus(long id, boolean online) {
        String now = Instant.now().toString();
        return jdbcClient.sql("""
                        UPDATE gb_device
                        SET online = :online, last_seen_at = :lastSeenAt, updated_at = :updatedAt
                        WHERE id = :id
                        """)
                .param("online", online ? 1 : 0)
                .param("lastSeenAt", now)
                .param("updatedAt", now)
                .param("id", id)
                .update();
    }

    public int updateDeviceOnlineStatusByCode(String deviceCode, boolean online) {
        String now = Instant.now().toString();
        return jdbcClient.sql("""
                        UPDATE gb_device
                        SET online = :online, last_seen_at = :lastSeenAt, updated_at = :updatedAt
                        WHERE device_id = :deviceCode
                        """)
                .param("online", online ? 1 : 0)
                .param("lastSeenAt", now)
                .param("updatedAt", now)
                .param("deviceCode", deviceCode)
                .update();
    }

    public List<DeviceChannel> findChannelsByDeviceId(long deviceId) {
        return jdbcClient.sql("""
                        SELECT id, device_pk, channel_no, channel_id, name, codec, status, created_at, updated_at
                        FROM gb_channel
                        WHERE device_pk = :deviceId
                        ORDER BY channel_no ASC
                        """)
                .param("deviceId", deviceId)
                .query((rs, rowNum) -> new DeviceChannel(
                        rs.getLong("id"),
                        rs.getLong("device_pk"),
                        rs.getInt("channel_no"),
                        rs.getString("channel_id"),
                        rs.getString("name"),
                        rs.getString("codec"),
                        rs.getString("status"),
                        rs.getString("created_at"),
                        rs.getString("updated_at")
                ))
                .list();
    }

    public Optional<DeviceChannel> findChannelByDeviceIdAndChannelId(long deviceId, String channelId) {
        return jdbcClient.sql("""
                        SELECT id, device_pk, channel_no, channel_id, name, codec, status, created_at, updated_at
                        FROM gb_channel
                        WHERE device_pk = :deviceId AND channel_id = :channelId
                        LIMIT 1
                        """)
                .param("deviceId", deviceId)
                .param("channelId", channelId)
                .query((rs, rowNum) -> new DeviceChannel(
                        rs.getLong("id"),
                        rs.getLong("device_pk"),
                        rs.getInt("channel_no"),
                        rs.getString("channel_id"),
                        rs.getString("name"),
                        rs.getString("codec"),
                        rs.getString("status"),
                        rs.getString("created_at"),
                        rs.getString("updated_at")
                ))
                .optional();
    }

    public Optional<DeviceChannel> findFirstChannelByDeviceId(long deviceId) {
        return jdbcClient.sql("""
                        SELECT id, device_pk, channel_no, channel_id, name, codec, status, created_at, updated_at
                        FROM gb_channel
                        WHERE device_pk = :deviceId
                        ORDER BY channel_no ASC
                        LIMIT 1
                        """)
                .param("deviceId", deviceId)
                .query((rs, rowNum) -> new DeviceChannel(
                        rs.getLong("id"),
                        rs.getLong("device_pk"),
                        rs.getInt("channel_no"),
                        rs.getString("channel_id"),
                        rs.getString("name"),
                        rs.getString("codec"),
                        rs.getString("status"),
                        rs.getString("created_at"),
                        rs.getString("updated_at")
                ))
                .optional();
    }

    @Transactional
    protected void recreateChannels(long devicePk, String deviceCode, int channelCount, String codec) {
        jdbcClient.sql("DELETE FROM gb_channel WHERE device_pk = :devicePk")
                .param("devicePk", devicePk)
                .update();
        String now = Instant.now().toString();
        for (int i = 1; i <= channelCount; i++) {
            String channelId = deviceCode + String.format("%03d", i);
            jdbcClient.sql("""
                            INSERT INTO gb_channel (device_pk, channel_no, channel_id, name, codec, status, created_at, updated_at)
                            VALUES (:devicePk, :channelNo, :channelId, :name, :codec, 'OFFLINE', :now, :now)
                            """)
                    .param("devicePk", devicePk)
                    .param("channelNo", i)
                    .param("channelId", channelId)
                    .param("name", "通道-" + i)
                    .param("codec", codec)
                    .param("now", now)
                    .update();
        }
    }

    public record CreateDeviceRequest(
            String name,
            String deviceId,
            String ip,
            int port,
            String transport,
            String username,
            String password,
            String manufacturer,
            int channelCount,
            String preferredCodec
    ) {
    }

    public record UpdateDeviceRequest(
            String name,
            String deviceId,
            String ip,
            int port,
            String transport,
            String username,
            String password,
            String manufacturer,
            int channelCount,
            String preferredCodec
    ) {
    }
}
