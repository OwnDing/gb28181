package com.ownding.video.media;

import com.ownding.video.common.ApiException;
import com.ownding.video.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class ZlmClient {

    private static final Logger log = LoggerFactory.getLogger(ZlmClient.class);
    private final WebClient webClient;
    private final AppProperties appProperties;

    public ZlmClient(WebClient webClient, AppProperties appProperties) {
        this.webClient = webClient;
        this.appProperties = appProperties;
    }

    @SuppressWarnings("unchecked")
    public Integer openRtpServer(String streamId, int tcpMode) {
        return openRtpServer(streamId, tcpMode, null);
    }

    @SuppressWarnings("unchecked")
    public Integer openRtpServer(String streamId, int tcpMode, String ssrc) {
        try {
            Map<String, Object> response = requestOpenRtpServer(streamId, tcpMode, ssrc);
            if (isSuccess(response)) {
                return extractPort(response);
            }
            if (isStreamAlreadyExists(response)) {
                log.warn("openRtpServer stream already exists, try to close stale rtp server first. streamId={}",
                        streamId);
                closeRtpServer(streamId);
                try {
                    Thread.sleep(120);
                } catch (InterruptedException interruptedEx) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                Map<String, Object> retried = requestOpenRtpServer(streamId, tcpMode, ssrc);
                if (isSuccess(retried)) {
                    return extractPort(retried);
                }
                log.warn("openRtpServer retry failed, response={}", retried);
                return null;
            }
            log.warn("openRtpServer failed, response={}", response);
            return null;
        } catch (Exception ex) {
            log.warn("openRtpServer exception: {}", ex.getMessage());
            return null;
        }
    }

    public void closeRtpServer(String streamId) {
        try {
            webClient.get()
                    .uri(appProperties.getZlm().getBaseUrl()
                                    + "/index/api/closeRtpServer?secret={secret}&stream_id={streamId}",
                            Map.of("secret", appProperties.getZlm().getSecret(),
                                    "streamId", streamId))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(2));
        } catch (Exception ex) {
            log.warn("closeRtpServer exception: {}", ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public boolean waitStreamReady(String app, String streamId, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (isStreamReady(app, streamId)) {
                return true;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return isStreamReady(app, streamId);
    }

    @SuppressWarnings("unchecked")
    public boolean isStreamReady(String app, String streamId) {
        try {
            Map<String, Object> response = webClient.get()
                    .uri(appProperties.getZlm().getBaseUrl()
                                    + "/index/api/getMediaList?secret={secret}&app={app}&stream={stream}",
                            Map.of("secret", appProperties.getZlm().getSecret(),
                                    "app", app,
                                    "stream", streamId))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(3));

            if (response == null) {
                return false;
            }
            Number code = (Number) response.get("code");
            if (code == null || code.intValue() != 0) {
                return false;
            }
            Object dataObj = response.get("data");
            if (dataObj instanceof List<?> list) {
                return !list.isEmpty();
            }
            return false;
        } catch (Exception ex) {
            log.debug("isStreamReady exception: {}", ex.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public PreviewService.WebRtcAnswer playWebRtc(String app, String streamId, String offerSdp) {
        try {
            String baseUrl = trimTrailingSlash(appProperties.getZlm().getBaseUrl());
            UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(baseUrl)
                    .path("/index/api/webrtc")
                    .queryParam("secret", appProperties.getZlm().getSecret())
                    .queryParam("app", app)
                    .queryParam("stream", streamId)
                    .queryParam("type", "play");

            Map<String, Object> response = webClient.post()
                    .uri(uriBuilder.build(true).toUri())
                    .contentType(MediaType.TEXT_PLAIN)
                    .bodyValue(offerSdp)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(5));
            if (response == null) {
                throw new ApiException(502, "ZLMediaKit WebRTC 返回为空");
            }
            Number code = (Number) response.get("code");
            if (code == null || code.intValue() != 0) {
                String msg = extractMessage(response);
                throw new ApiException(502, "ZLMediaKit WebRTC信令失败: " + msg);
            }

            String answerSdp = extractString(response, "sdp", "answer");
            if (answerSdp == null || answerSdp.isBlank()) {
                throw new ApiException(502, "ZLMediaKit WebRTC 未返回 answer SDP");
            }
            String answerType = extractString(response, "type");
            if (answerType == null || answerType.isBlank()) {
                answerType = "answer";
            }
            return new PreviewService.WebRtcAnswer(answerType, answerSdp);
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(502, "ZLMediaKit WebRTC信令异常: " + ex.getMessage());
        }
    }

    public PreviewService.PlayUrls buildPlayUrls(String app, String streamId) {
        String base = trimTrailingSlash(appProperties.getZlm().getPublicBaseUrl());
        String webrtcPlayerUrl = "%s/index/api/webrtc?app=%s&stream=%s&type=play".formatted(base, app, streamId);
        String hlsUrl = "%s/%s/%s/hls.m3u8".formatted(base, app, streamId);
        String httpFlvUrl = "%s/%s/%s.live.flv".formatted(base, app, streamId);
        String rtmpUrl = toRtmp(base, app, streamId);
        String rtspUrl = toRtsp(base, app, streamId);
        return new PreviewService.PlayUrls(webrtcPlayerUrl, hlsUrl, httpFlvUrl, rtspUrl, rtmpUrl);
    }

    private String trimTrailingSlash(String url) {
        if (url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }

    private String toRtsp(String base, String app, String streamId) {
        String host = base.replace("http://", "").replace("https://", "");
        return "rtsp://%s/%s/%s".formatted(host, app, streamId);
    }

    private String toRtmp(String base, String app, String streamId) {
        String host = base.replace("http://", "").replace("https://", "");
        return "rtmp://%s/%s/%s".formatted(host, app, streamId);
    }

    private String extractMessage(Map<String, Object> response) {
        if (response == null) {
            return "unknown";
        }
        Object msg = response.get("msg");
        if (msg == null || String.valueOf(msg).isBlank()) {
            msg = response.get("message");
        }
        if (msg == null) {
            return "unknown";
        }
        return String.valueOf(msg);
    }

    @SuppressWarnings("unchecked")
    private String extractString(Map<String, Object> response, String... keys) {
        for (String key : keys) {
            Object top = response.get(key);
            if (top != null && !String.valueOf(top).isBlank()) {
                return String.valueOf(top);
            }
        }
        Object dataObj = response.get("data");
        if (dataObj instanceof Map<?, ?> dataMap) {
            Map<String, Object> data = (Map<String, Object>) dataMap;
            for (String key : keys) {
                Object nested = data.get(key);
                if (nested != null && !String.valueOf(nested).isBlank()) {
                    return String.valueOf(nested);
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> requestOpenRtpServer(String streamId, int tcpMode, String ssrc) {
        String baseUrl = trimTrailingSlash(appProperties.getZlm().getBaseUrl());
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(baseUrl)
                .path("/index/api/openRtpServer")
                .queryParam("secret", appProperties.getZlm().getSecret())
                .queryParam("port", 0)
                .queryParam("tcp_mode", tcpMode)
                .queryParam("stream_id", streamId);
        if (ssrc != null && !ssrc.isBlank()) {
            uriBuilder.queryParam("ssrc", ssrc.trim());
        }
        return webClient.get()
                .uri(uriBuilder.build(true).toUri())
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofSeconds(3));
    }

    private boolean isSuccess(Map<String, Object> response) {
        Integer code = extractCode(response);
        return code != null && code == 0;
    }

    private boolean isStreamAlreadyExists(Map<String, Object> response) {
        Integer code = extractCode(response);
        if (code == null || code != -300) {
            return false;
        }
        String msg = extractMessage(response).toLowerCase();
        return msg.contains("already exists") || msg.contains("stream already exists");
    }

    private Integer extractCode(Map<String, Object> response) {
        if (response == null) {
            return null;
        }
        Object codeObj = response.get("code");
        if (codeObj instanceof Number number) {
            return number.intValue();
        }
        if (codeObj != null) {
            try {
                return Integer.parseInt(String.valueOf(codeObj));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Integer extractPort(Map<String, Object> response) {
        if (response == null) {
            return null;
        }
        Object portObj = response.get("port");
        if (portObj instanceof Number number) {
            return number.intValue();
        }
        Object dataObj = response.get("data");
        if (dataObj instanceof Map<?, ?> dataMap) {
            Object dataPort = dataMap.get("port");
            if (dataPort instanceof Number dataPortNumber) {
                return dataPortNumber.intValue();
            }
        }
        return null;
    }
}
