package com.ownding.video.auth;

import com.ownding.video.common.ApiException;
import com.ownding.video.config.AppProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();
    private final AuthRepository authRepository;
    private final AppProperties appProperties;

    public AuthService(AuthRepository authRepository, AppProperties appProperties) {
        this.authRepository = authRepository;
        this.appProperties = appProperties;
    }

    @PostConstruct
    public void initDefaultAdmin() {
        String username = appProperties.getAuth().getDefaultAdminUsername();
        Optional<AuthRepository.UserAccount> existing = authRepository.findUserByUsername(username);
        if (existing.isEmpty()) {
            authRepository.createUser(
                    username,
                    PASSWORD_ENCODER.encode(appProperties.getAuth().getDefaultAdminPassword()),
                    "ADMIN"
            );
        }
    }

    public LoginResult login(String username, String password) {
        AuthRepository.UserAccount user = authRepository.findUserByUsername(username)
                .orElseThrow(() -> new ApiException(401, "用户名或密码错误"));
        if (!user.enabled()) {
            throw new ApiException(403, "账号已禁用");
        }

        boolean passwordMatched = verifyAndUpgradePassword(user, password);
        if (!passwordMatched) {
            throw new ApiException(401, "用户名或密码错误");
        }

        Instant expiresAt = Instant.now().plus(appProperties.getAuth().getTokenExpireHours(), ChronoUnit.HOURS);
        String token = UUID.randomUUID().toString().replace("-", "");
        authRepository.createToken(user.id(), token, expiresAt);
        return new LoginResult(token, user.username(), user.role(), expiresAt.toString());
    }

    public AuthContext authenticate(String token) {
        return authRepository.findAuthContextByToken(token)
                .orElseThrow(() -> new ApiException(401, "未登录或登录已过期"));
    }

    public Optional<AuthContext> authenticateOptional(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return authRepository.findAuthContextByToken(token);
    }

    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            authRepository.deleteToken(token);
        }
    }

    @Scheduled(fixedDelay = 3600000)
    public void cleanupExpiredTokens() {
        authRepository.deleteExpiredTokens();
    }

    private boolean verifyAndUpgradePassword(AuthRepository.UserAccount user, String inputPassword) {
        String currentHash = user.passwordHash();
        if (currentHash.startsWith("$2a$") || currentHash.startsWith("$2b$") || currentHash.startsWith("$2y$")) {
            return PASSWORD_ENCODER.matches(inputPassword, currentHash);
        }
        if (currentHash.equals(inputPassword)) {
            authRepository.updatePassword(user.id(), PASSWORD_ENCODER.encode(inputPassword));
            return true;
        }
        return false;
    }

    public record LoginResult(String token, String username, String role, String expiresAt) {
    }
}
