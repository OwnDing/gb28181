package com.ownding.video.push;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public class PushTokenRepository {

    private final JdbcClient jdbcClient;

    public PushTokenRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public MobilePushToken upsert(long userId, String username, PushTokenService.RegisterCommand command) {
        String now = Instant.now().toString();

        jdbcClient.sql("""
                INSERT INTO mobile_push_token (
                    user_id, username, token, token_type, platform, device_name,
                    app_version, permission_status, project_id, created_at, updated_at, last_seen_at
                ) VALUES (
                    :userId, :username, :token, :tokenType, :platform, :deviceName,
                    :appVersion, :permissionStatus, :projectId, :createdAt, :updatedAt, :lastSeenAt
                )
                ON CONFLICT(token) DO UPDATE SET
                    user_id = excluded.user_id,
                    username = excluded.username,
                    token_type = excluded.token_type,
                    platform = excluded.platform,
                    device_name = excluded.device_name,
                    app_version = excluded.app_version,
                    permission_status = excluded.permission_status,
                    project_id = excluded.project_id,
                    updated_at = excluded.updated_at,
                    last_seen_at = excluded.last_seen_at
                """)
                .param("userId", userId)
                .param("username", username)
                .param("token", command.token())
                .param("tokenType", command.tokenType())
                .param("platform", command.platform())
                .param("deviceName", command.deviceName())
                .param("appVersion", command.appVersion())
                .param("permissionStatus", command.permissionStatus())
                .param("projectId", command.projectId())
                .param("createdAt", now)
                .param("updatedAt", now)
                .param("lastSeenAt", now)
                .update();

        return findByToken(command.token());
    }

    public List<MobilePushToken> listByUserId(long userId) {
        return jdbcClient.sql("""
                SELECT id, user_id, username, token, token_type, platform, device_name,
                       app_version, permission_status, project_id, created_at, updated_at, last_seen_at
                FROM mobile_push_token
                WHERE user_id = :userId
                ORDER BY updated_at DESC
                """)
                .param("userId", userId)
                .query(this::mapRow)
                .list();
    }

    private MobilePushToken findByToken(String token) {
        return jdbcClient.sql("""
                SELECT id, user_id, username, token, token_type, platform, device_name,
                       app_version, permission_status, project_id, created_at, updated_at, last_seen_at
                FROM mobile_push_token
                WHERE token = :token
                LIMIT 1
                """)
                .param("token", token)
                .query(this::mapRow)
                .single();
    }

    private MobilePushToken mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new MobilePushToken(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("username"),
                rs.getString("token"),
                rs.getString("token_type"),
                rs.getString("platform"),
                rs.getString("device_name"),
                rs.getString("app_version"),
                rs.getString("permission_status"),
                rs.getString("project_id"),
                rs.getString("created_at"),
                rs.getString("updated_at"),
                rs.getString("last_seen_at"));
    }
}
