package com.ownding.video.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Auth auth = new Auth();
    private final Zlm zlm = new Zlm();
    private final Preview preview = new Preview();
    private final Storage storage = new Storage();
    private final Gb28181 gb28181 = new Gb28181();

    public Auth getAuth() {
        return auth;
    }

    public Zlm getZlm() {
        return zlm;
    }

    public Preview getPreview() {
        return preview;
    }

    public Storage getStorage() {
        return storage;
    }

    public Gb28181 getGb28181() {
        return gb28181;
    }

    public static class Auth {
        @Min(1)
        private int tokenExpireHours = 12;
        @NotBlank
        private String defaultAdminUsername = "admin";
        @NotBlank
        private String defaultAdminPassword = "admin123";

        public int getTokenExpireHours() {
            return tokenExpireHours;
        }

        public void setTokenExpireHours(int tokenExpireHours) {
            this.tokenExpireHours = tokenExpireHours;
        }

        public String getDefaultAdminUsername() {
            return defaultAdminUsername;
        }

        public void setDefaultAdminUsername(String defaultAdminUsername) {
            this.defaultAdminUsername = defaultAdminUsername;
        }

        public String getDefaultAdminPassword() {
            return defaultAdminPassword;
        }

        public void setDefaultAdminPassword(String defaultAdminPassword) {
            this.defaultAdminPassword = defaultAdminPassword;
        }
    }

    public static class Zlm {
        @NotBlank
        private String baseUrl = "http://127.0.0.1:8081";
        @NotBlank
        private String secret = "123456";
        @NotBlank
        private String publicBaseUrl = "http://127.0.0.1:8081";
        @NotBlank
        private String defaultApp = "rtp";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public String getPublicBaseUrl() {
            return publicBaseUrl;
        }

        public void setPublicBaseUrl(String publicBaseUrl) {
            this.publicBaseUrl = publicBaseUrl;
        }

        public String getDefaultApp() {
            return defaultApp;
        }

        public void setDefaultApp(String defaultApp) {
            this.defaultApp = defaultApp;
        }
    }

    public static class Preview {
        private boolean allowH265DirectPlay = true;
        private boolean enableH265TranscodeFallback = false;

        public boolean isAllowH265DirectPlay() {
            return allowH265DirectPlay;
        }

        public void setAllowH265DirectPlay(boolean allowH265DirectPlay) {
            this.allowH265DirectPlay = allowH265DirectPlay;
        }

        public boolean isEnableH265TranscodeFallback() {
            return enableH265TranscodeFallback;
        }

        public void setEnableH265TranscodeFallback(boolean enableH265TranscodeFallback) {
            this.enableH265TranscodeFallback = enableH265TranscodeFallback;
        }
    }

    public static class Storage {
        @Min(30)
        private int cleanupIntervalSeconds = 180;
        private String zlmRecordPath = "";

        public int getCleanupIntervalSeconds() {
            return cleanupIntervalSeconds;
        }

        public void setCleanupIntervalSeconds(int cleanupIntervalSeconds) {
            this.cleanupIntervalSeconds = cleanupIntervalSeconds;
        }

        public String getZlmRecordPath() {
            return zlmRecordPath;
        }

        public void setZlmRecordPath(String zlmRecordPath) {
            this.zlmRecordPath = zlmRecordPath;
        }
    }

    public static class Gb28181 {
        private boolean enabled = true;
        private boolean autoRegisterUnknownDevice = true;
        @NotBlank
        private String localIp = "0.0.0.0";
        private String localBindIp = "0.0.0.0";
        @Min(1)
        private int localPort = 5060;
        @NotBlank
        private String mediaIp = "127.0.0.1";
        @NotBlank
        private String serverId = "34020000002000000001";
        @NotBlank
        private String domain = "3402000000";
        @NotBlank
        private String userAgent = "video-gb28181";
        @Min(1000)
        private int inviteTimeoutMs = 8000;
        @NotBlank
        private String ssrcPrefix = "0";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isAutoRegisterUnknownDevice() {
            return autoRegisterUnknownDevice;
        }

        public void setAutoRegisterUnknownDevice(boolean autoRegisterUnknownDevice) {
            this.autoRegisterUnknownDevice = autoRegisterUnknownDevice;
        }

        public String getLocalIp() {
            return localIp;
        }

        public void setLocalIp(String localIp) {
            this.localIp = localIp;
        }

        public int getLocalPort() {
            return localPort;
        }

        public void setLocalPort(int localPort) {
            this.localPort = localPort;
        }

        public String getMediaIp() {
            return mediaIp;
        }

        public void setMediaIp(String mediaIp) {
            this.mediaIp = mediaIp;
        }

        public String getServerId() {
            return serverId;
        }

        public void setServerId(String serverId) {
            this.serverId = serverId;
        }

        public String getDomain() {
            return domain;
        }

        public void setDomain(String domain) {
            this.domain = domain;
        }

        public String getUserAgent() {
            return userAgent;
        }

        public void setUserAgent(String userAgent) {
            this.userAgent = userAgent;
        }

        public int getInviteTimeoutMs() {
            return inviteTimeoutMs;
        }

        public void setInviteTimeoutMs(int inviteTimeoutMs) {
            this.inviteTimeoutMs = inviteTimeoutMs;
        }

        public String getSsrcPrefix() {
            return ssrcPrefix;
        }

        public void setSsrcPrefix(String ssrcPrefix) {
            this.ssrcPrefix = ssrcPrefix;
        }

        public String getLocalBindIp() {
            return localBindIp;
        }

        public void setLocalBindIp(String localBindIp) {
            this.localBindIp = localBindIp;
        }
    }
}
