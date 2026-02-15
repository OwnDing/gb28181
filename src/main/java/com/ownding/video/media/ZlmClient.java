package com.ownding.video.media;

import com.ownding.video.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
            Map<String, Object> response = webClient.get()
                    .uri(uriBuilder.build(true).toUri())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(3));

            if (response == null) {
                return null;
            }
            Number code = (Number) response.get("code");
            if (code == null || code.intValue() != 0) {
                log.warn("openRtpServer failed, response={}", response);
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

    public PreviewService.PlayUrls buildPlayUrls(String app, String streamId) {
        String base = trimTrailingSlash(appProperties.getZlm().getPublicBaseUrl());
        String webrtcPlayerUrl = "%s/index/webrtc.html?app=%s&stream=%s".formatted(base, app, streamId);
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
}
