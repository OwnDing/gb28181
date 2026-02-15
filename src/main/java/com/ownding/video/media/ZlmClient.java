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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    public boolean startMp4Record(String app, String streamId, String customPath) {
        try {
            Map<String, Object> response = requestRecordControl("/index/api/startRecord", app, streamId, customPath);
            if (isSuccess(response) || isAlreadyRecording(response)) {
                return true;
            }
            log.warn("startMp4Record failed, app={}, streamId={}, response={}", app, streamId, response);
            return false;
        } catch (Exception ex) {
            log.warn("startMp4Record exception, app={}, streamId={}, err={}", app, streamId, ex.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public boolean stopMp4Record(String app, String streamId) {
        try {
            Map<String, Object> response = requestRecordControl("/index/api/stopRecord", app, streamId, null);
            if (isSuccess(response) || isNotRecording(response) || isStreamNotFound(response)) {
                return true;
            }
            log.warn("stopMp4Record failed, app={}, streamId={}, response={}", app, streamId, response);
            return false;
        } catch (Exception ex) {
            log.warn("stopMp4Record exception, app={}, streamId={}, err={}", app, streamId, ex.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public boolean isMp4Recording(String app, String streamId) {
        try {
            MediaRuntime runtime = queryMediaRuntime(app, streamId);
            if (runtime != null && runtime.mp4Recording()) {
                return runtime.mp4Recording();
            }
            Map<String, Object> response = requestRecordControl("/index/api/isRecording", app, streamId, null);
            if (!isSuccess(response)) {
                return runtime != null && runtime.mp4Recording();
            }
            boolean byApi = extractRecordingFlag(response);
            return byApi || (runtime != null && runtime.mp4Recording());
        } catch (Exception ex) {
            log.warn("isMp4Recording exception, app={}, streamId={}, err={}", app, streamId, ex.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, MediaRuntime> listMediaRuntimeByApp(String app) {
        return listMediaRuntimeInternal(app, null);
    }

    public MediaRuntime queryMediaRuntime(String app, String streamId) {
        if (streamId == null || streamId.isBlank()) {
            return null;
        }
        String normalizedStreamId = normalizeStreamId(streamId);

        MediaRuntime runtime = lookupRuntime(listMediaRuntimeInternal(app, streamId), streamId, normalizedStreamId);
        if (runtime == null && normalizedStreamId != null && !normalizedStreamId.equals(streamId)) {
            runtime = lookupRuntime(
                    listMediaRuntimeInternal(app, normalizedStreamId),
                    streamId,
                    normalizedStreamId);
        }
        if (runtime != null) {
            return runtime;
        }

        if (app != null && !app.isBlank()) {
            runtime = lookupRuntime(listMediaRuntimeInternal(null, streamId), streamId, normalizedStreamId);
            if (runtime == null && normalizedStreamId != null && !normalizedStreamId.equals(streamId)) {
                runtime = lookupRuntime(
                        listMediaRuntimeInternal(null, normalizedStreamId),
                        streamId,
                        normalizedStreamId);
            }
        }
        return runtime;
    }

    @SuppressWarnings("unchecked")
    private Map<String, MediaRuntime> listMediaRuntimeInternal(String app, String streamFilter) {
        try {
            Map<String, Object> response = requestMediaList(app, streamFilter);
            if (!isSuccess(response)) {
                return Map.of();
            }
            Object dataObj = response.get("data");
            Map<String, MediaRuntime> result = new LinkedHashMap<>();
            String normalizedFilter = normalizeStreamId(streamFilter);
            if (dataObj instanceof List<?> list) {
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> raw)) {
                        continue;
                    }
                    Map<String, Object> map = (Map<String, Object>) raw;
                    List<String> streamCandidates = extractStreamCandidates(map);
                    if (streamCandidates.isEmpty()) {
                        continue;
                    }
                    if (normalizedFilter != null && streamCandidates.stream().noneMatch(normalizedFilter::equals)) {
                        continue;
                    }
                    String runtimeApp = firstString(map, "app");
                    if (runtimeApp == null || runtimeApp.isBlank()) {
                        runtimeApp = app;
                    }
                    boolean mp4 = extractMp4RecordingFlag(map);
                    String primaryStream = streamCandidates.get(0);
                    for (String candidate : streamCandidates) {
                        registerRuntime(result, candidate, runtimeApp, primaryStream, mp4);
                    }
                }
            }
            return result;
        } catch (Exception ex) {
            log.warn("listMediaRuntimeInternal exception, app={}, stream={}, err={}",
                    app, streamFilter, ex.getMessage());
            return Map.of();
        }
    }

    private MediaRuntime lookupRuntime(Map<String, MediaRuntime> runtimeMap, String rawStreamId,
            String normalizedStreamId) {
        if (runtimeMap == null || runtimeMap.isEmpty()) {
            return null;
        }
        if (rawStreamId != null) {
            MediaRuntime byRaw = runtimeMap.get(rawStreamId);
            if (byRaw != null) {
                return byRaw;
            }
        }
        if (normalizedStreamId != null) {
            return runtimeMap.get(normalizedStreamId);
        }
        return null;
    }

    private void registerRuntime(Map<String, MediaRuntime> result, String key, String runtimeApp, String streamId,
            boolean mp4) {
        MediaRuntime existing = result.get(key);
        if (existing == null) {
            result.put(key, new MediaRuntime(runtimeApp, streamId, true, mp4));
            return;
        }
        result.put(key, new MediaRuntime(
                existing.app(),
                existing.streamId(),
                true,
                existing.mp4Recording() || mp4));
    }

    @SuppressWarnings("unchecked")
    private List<String> extractStreamCandidates(Map<String, Object> map) {
        Set<String> candidates = new LinkedHashSet<>();
        String stream = normalizeStreamId(firstString(map, "stream"));
        if (stream != null) {
            candidates.add(stream);
        }

        Object originSockObj = map.get("originSock");
        if (originSockObj instanceof Map<?, ?> rawSock) {
            Map<String, Object> originSock = (Map<String, Object>) rawSock;
            String identifier = normalizeStreamId(firstString(originSock, "identifier"));
            if (identifier != null) {
                candidates.add(identifier);
            }
        }

        String originUrl = firstString(map, "originUrl");
        String byOriginUrl = normalizeStreamId(parseStreamFromOriginUrl(originUrl));
        if (byOriginUrl != null) {
            candidates.add(byOriginUrl);
        }
        return new ArrayList<>(candidates);
    }

    private String parseStreamFromOriginUrl(String originUrl) {
        if (originUrl == null || originUrl.isBlank()) {
            return null;
        }
        int queryIndex = originUrl.indexOf('?');
        String withoutQuery = queryIndex >= 0 ? originUrl.substring(0, queryIndex) : originUrl;
        int slash = withoutQuery.lastIndexOf('/');
        if (slash < 0 || slash == withoutQuery.length() - 1) {
            return null;
        }
        return withoutQuery.substring(slash + 1).trim();
    }

    private String normalizeStreamId(String streamId) {
        if (streamId == null) {
            return null;
        }
        String trimmed = streamId.trim();
        if (trimmed.isBlank()) {
            return null;
        }
        String normalized = trimmed.replaceAll("[^A-Za-z0-9_\\-]", "");
        if (normalized.isBlank()) {
            return trimmed;
        }
        return normalized;
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
            MediaRuntime runtime = queryMediaRuntime(app, streamId);
            return runtime != null && runtime.streamReady();
        } catch (Exception ex) {
            log.warn("isStreamReady exception: {}", ex.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public String detectStreamCodec(String app, String streamId) {
        try {
            Map<String, Object> response = requestMediaList(app, streamId);
            if (!isSuccess(response)) {
                return null;
            }
            return detectCodecFromObject(response.get("data"), 0);
        } catch (Exception ex) {
            log.debug("detectStreamCodec exception: {}", ex.getMessage());
            return null;
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> requestRecordControl(String path, String app, String streamId, String customPath) {
        String baseUrl = trimTrailingSlash(appProperties.getZlm().getBaseUrl());
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(baseUrl)
                .path(path)
                .queryParam("secret", appProperties.getZlm().getSecret())
                .queryParam("type", 1)
                .queryParam("vhost", "__defaultVhost__")
                .queryParam("app", app)
                .queryParam("stream", streamId);
        if (customPath != null && !customPath.isBlank()) {
            uriBuilder.queryParam("customized_path", customPath.trim());
        }
        return webClient.get()
                .uri(uriBuilder.build(true).toUri())
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofSeconds(3));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> requestMediaList(String app, String streamId) {
        String baseUrl = trimTrailingSlash(appProperties.getZlm().getBaseUrl());
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(baseUrl)
                .path("/index/api/getMediaList")
                .queryParam("secret", appProperties.getZlm().getSecret());
        if (app != null && !app.isBlank()) {
            uriBuilder.queryParam("app", app);
        }
        if (streamId != null && !streamId.isBlank()) {
            uriBuilder.queryParam("stream", streamId);
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

    private boolean isAlreadyRecording(Map<String, Object> response) {
        String msg = extractMessage(response).toLowerCase();
        return msg.contains("already recording") || msg.contains("recording already started");
    }

    private boolean isNotRecording(Map<String, Object> response) {
        String msg = extractMessage(response).toLowerCase();
        return msg.contains("not recording");
    }

    private boolean isStreamNotFound(Map<String, Object> response) {
        String msg = extractMessage(response).toLowerCase();
        return msg.contains("can not find the stream") || msg.contains("stream not found");
    }

    @SuppressWarnings("unchecked")
    private boolean extractRecordingFlag(Map<String, Object> response) {
        Boolean extracted = extractRecordingFlagValue(response, 0);
        return extracted != null && extracted;
    }

    @SuppressWarnings("unchecked")
    private Boolean extractRecordingFlagValue(Object value, int depth) {
        if (value == null || depth > 6) {
            return null;
        }
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> map = (Map<String, Object>) raw;
            Boolean direct = readRecordingFlagFromMap(map);
            if (direct != null) {
                return direct;
            }
            Boolean nestedFound = null;
            for (Object child : map.values()) {
                Boolean nested = extractRecordingFlagValue(child, depth + 1);
                if (nested == null) {
                    continue;
                }
                if (nested) {
                    return true;
                }
                nestedFound = false;
            }
            return nestedFound;
        }
        if (value instanceof List<?> list) {
            Boolean nestedFound = null;
            for (Object item : list) {
                Boolean nested = extractRecordingFlagValue(item, depth + 1);
                if (nested == null) {
                    continue;
                }
                if (nested) {
                    return true;
                }
                nestedFound = false;
            }
            return nestedFound;
        }
        if (value instanceof Boolean || value instanceof Number || value instanceof CharSequence) {
            return asBoolean(value);
        }
        return null;
    }

    private boolean extractMp4RecordingFlag(Map<String, Object> map) {
        Boolean mp4 = readBooleanByKeys(map, "isRecordingMP4", "mp4Recording", "mp4_recording");
        if (mp4 != null) {
            return mp4;
        }
        Boolean generic = readBooleanByKeys(map, "isRecording", "recording", "status");
        return generic != null && generic;
    }

    private Boolean readRecordingFlagFromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        Boolean mp4 = readBooleanByKeys(
                map,
                "isRecordingMP4",
                "mp4Recording",
                "mp4_recording");
        Boolean generic = readBooleanByKeys(
                map,
                "isRecording",
                "recording",
                "status");
        Boolean hls = readBooleanByKeys(
                map,
                "isRecordingHLS",
                "hlsRecording",
                "hls_recording");
        if (Boolean.TRUE.equals(mp4) || Boolean.TRUE.equals(generic) || Boolean.TRUE.equals(hls)) {
            return true;
        }
        if (mp4 != null) {
            return false;
        }
        if (generic != null) {
            return false;
        }
        if (hls != null) {
            return false;
        }
        return null;
    }

    private Boolean readBooleanByKeys(Map<String, Object> map, String... keys) {
        if (map == null || keys == null || keys.length == 0) {
            return null;
        }
        Boolean found = null;
        for (String key : keys) {
            Object value = findValueIgnoreCase(map, key);
            if (value == null) {
                continue;
            }
            if (asBoolean(value)) {
                return true;
            }
            found = false;
        }
        return found;
    }

    private Object findValueIgnoreCase(Map<String, Object> map, String targetKey) {
        if (map == null || targetKey == null) {
            return null;
        }
        if (map.containsKey(targetKey)) {
            return map.get(targetKey);
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            if (key != null && key.equalsIgnoreCase(targetKey)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private boolean asBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return false;
        }
        if ("true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text) || "no".equalsIgnoreCase(text)) {
            return false;
        }
        try {
            return Integer.parseInt(text) != 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
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

    @SuppressWarnings("unchecked")
    private String detectCodecFromObject(Object value, int depth) {
        if (value == null || depth > 6) {
            return null;
        }
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = (Map<String, Object>) rawMap;
            String byMap = detectCodecFromMap(map);
            if (byMap != null) {
                return byMap;
            }
            for (Object child : map.values()) {
                String detected = detectCodecFromObject(child, depth + 1);
                if (detected != null) {
                    return detected;
                }
            }
            return null;
        }
        if (value instanceof List<?> list) {
            for (Object child : list) {
                String detected = detectCodecFromObject(child, depth + 1);
                if (detected != null) {
                    return detected;
                }
            }
        }
        return null;
    }

    private String detectCodecFromMap(Map<String, Object> map) {
        String trackType = firstString(map, "codec_type_name", "track_type", "type", "kind", "media");
        if (trackType != null && trackType.toLowerCase().contains("audio")) {
            return null;
        }
        String codec = firstString(map, "codec_id_name", "codec", "codec_name", "videoCodec", "vcodec");
        return normalizeCodec(codec);
    }

    private String firstString(Map<String, Object> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = map.get(key);
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (!text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private String normalizeCodec(String codecText) {
        if (codecText == null || codecText.isBlank()) {
            return null;
        }
        String upper = codecText.toUpperCase();
        if (upper.contains("265") || upper.contains("HEVC")) {
            return "H265";
        }
        if (upper.contains("264") || upper.contains("AVC")) {
            return "H264";
        }
        return null;
    }

    public record MediaRuntime(
            String app,
            String streamId,
            boolean streamReady,
            boolean mp4Recording) {
    }
}
