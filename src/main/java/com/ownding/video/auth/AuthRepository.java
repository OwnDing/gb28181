package com.ownding.video.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public class AuthRepository {

    private final JdbcClient jdbcClient;

    public AuthRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<UserAccount> findUserByUsername(String username) {
        return jdbcClient.sql("""
                        SELECT id, username, password_hash, role, enabled, created_at, updated_at
                        FROM user_account
                        WHERE username = :username
                        LIMIT 1
                        """)
                .param("username", username)
                .query((rs, rowNum) -> new UserAccount(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        rs.getString("role"),
                        rs.getInt("enabled") == 1,
                        rs.getString("created_at"),
                        rs.getString("updated_at")
                ))
                .optional();
    }

    public void createUser(String username, String passwordHash, String role) {
        String now = Instant.now().toString();
        jdbcClient.sql("""
                        INSERT INTO user_account (username, password_hash, role, enabled, created_at, updated_at)
                        VALUES (:username, :passwordHash, :role, 1, :now, :now)
                        """)
                .param("username", username)
                .param("passwordHash", passwordHash)
                .param("role", role)
                .param("now", now)
                .update();
    }

    public void updatePassword(long userId, String passwordHash) {
        String now = Instant.now().toString();
        jdbcClient.sql("""
                        UPDATE user_account
                        SET password_hash = :passwordHash, updated_at = :now
                        WHERE id = :userId
                        """)
                .param("passwordHash", passwordHash)
                .param("userId", userId)
                .param("now", now)
                .update();
    }

    public String createToken(long userId, String token, Instant expiresAt) {
        String now = Instant.now().toString();
        jdbcClient.sql("""
                        INSERT INTO auth_token (user_id, token, expires_at, created_at)
                        VALUES (:userId, :token, :expiresAt, :now)
                        """)
                .param("userId", userId)
                .param("token", token)
                .param("expiresAt", expiresAt.toString())
                .param("now", now)
                .update();
        return token;
    }

    public Optional<AuthContext> findAuthContextByToken(String token) {
        String now = Instant.now().toString();
        return jdbcClient.sql("""
                        SELECT u.id AS user_id, u.username, u.role, t.token
                        FROM auth_token t
                        JOIN user_account u ON u.id = t.user_id
                        WHERE t.token = :token
                          AND u.enabled = 1
                          AND t.expires_at > :now
                        LIMIT 1
                        """)
                .param("token", token)
                .param("now", now)
                .query((rs, rowNum) -> new AuthContext(
                        rs.getLong("user_id"),
                        rs.getString("username"),
                        rs.getString("role"),
                        rs.getString("token")
                ))
                .optional();
    }

    public void deleteToken(String token) {
        jdbcClient.sql("DELETE FROM auth_token WHERE token = :token")
                .param("token", token)
                .update();
    }

    public void deleteExpiredTokens() {
        jdbcClient.sql("DELETE FROM auth_token WHERE expires_at <= :now")
                .param("now", Instant.now().toString())
                .update();
    }

    public record UserAccount(
            long id,
            String username,
            String passwordHash,
            String role,
            boolean enabled,
            String createdAt,
            String updatedAt
    ) {
    }
}
