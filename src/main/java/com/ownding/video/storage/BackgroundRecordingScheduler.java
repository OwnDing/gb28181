package com.ownding.video.storage;

import com.ownding.video.device.Device;
import com.ownding.video.device.DeviceChannel;
import com.ownding.video.device.DeviceService;
import com.ownding.video.config.AppProperties;
import com.ownding.video.media.PreviewService;
import com.ownding.video.media.ZlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class BackgroundRecordingScheduler {

    private static final Logger log = LoggerFactory.getLogger(BackgroundRecordingScheduler.class);

    private final DeviceService deviceService;
    private final StorageService storageService;
    private final PreviewService previewService;
    private final ZlmClient zlmClient;
    private final AppProperties appProperties;
    private final Map<String, ManagedChannel> managedChannels = new HashMap<>();
    private final Map<String, String> lastErrorByChannel = new HashMap<>();

    public BackgroundRecordingScheduler(DeviceService deviceService, StorageService storageService,
            PreviewService previewService, ZlmClient zlmClient, AppProperties appProperties) {
        this.deviceService = deviceService;
        this.storageService = storageService;
        this.previewService = previewService;
        this.zlmClient = zlmClient;
        this.appProperties = appProperties;
    }

    @Scheduled(fixedDelayString = "${app.storage.background-record-interval-ms:15000}")
    public synchronized void maintainBackgroundRecording() {
        StoragePolicy policy = storageService.getPolicy();
        if (!policy.recordEnabled()) {
            releaseAllManaged("recordEnabled=false");
            return;
        }

        Map<String, ManagedChannel> targetChannels = collectTargetChannels();

        for (ManagedChannel channel : targetChannels.values()) {
            String key = key(channel.devicePk(), channel.channelId());
            try {
                previewService.ensureBackgroundRecording(channel.devicePk(), channel.channelId());
                managedChannels.put(key, channel);
                lastErrorByChannel.remove(key);
            } catch (Exception ex) {
                String message = ex.getMessage();
                lastErrorByChannel.put(key, message);
                log.warn("ensure background recording failed. deviceId={}, channelId={}, err={}",
                        channel.deviceId(), channel.channelId(), message);
            }
        }

        managedChannels.entrySet().removeIf(entry -> {
            if (targetChannels.containsKey(entry.getKey())) {
                return false;
            }
            ManagedChannel channel = entry.getValue();
            try {
                previewService.releaseBackgroundRecording(channel.devicePk(), channel.channelId());
                lastErrorByChannel.remove(entry.getKey());
            } catch (Exception ex) {
                lastErrorByChannel.put(entry.getKey(), ex.getMessage());
                log.warn("release background recording failed. deviceId={}, channelId={}, err={}",
                        channel.deviceId(), channel.channelId(), ex.getMessage());
            }
            return true;
        });
    }

    public synchronized List<BackgroundRecordingStatus> listStatuses() {
        StoragePolicy policy = storageService.getPolicy();
        String defaultApp = appProperties.getZlm().getDefaultApp();
        Map<String, ZlmClient.MediaRuntime> runtimeByDefaultApp = zlmClient.listMediaRuntimeByApp(defaultApp);
        Map<String, ZlmClient.MediaRuntime> runtimeByAnyApp = zlmClient.listMediaRuntimeByApp(null);

        List<BackgroundRecordingStatus> result = new ArrayList<>();
        List<Device> devices = deviceService.listDevices();
        for (Device device : devices) {
            List<DeviceChannel> channels = deviceService.listChannels(device.id());
            for (DeviceChannel channel : channels) {
                String channelKey = key(device.id(), channel.channelId());
                String expectedStreamId = buildStreamId(channel.channelId());

                // Lightweight session lookup — only for metadata, NOT for
                // streamReady/recording.
                PreviewService.ChannelRuntime runtime = previewService.findChannelRuntimeLocal(
                        device.id(), channel.channelId());

                String app = runtime != null && runtime.app() != null && !runtime.app().isBlank()
                        ? runtime.app()
                        : defaultApp;
                String streamId = runtime != null && runtime.streamId() != null && !runtime.streamId().isBlank()
                        ? runtime.streamId()
                        : expectedStreamId;

                // Use pre-fetched runtime maps (batch-loaded, no per-channel HTTP calls).
                ZlmClient.MediaRuntime mediaRuntime = resolveMediaRuntime(
                        runtimeByDefaultApp,
                        runtimeByAnyApp,
                        expectedStreamId,
                        streamId);
                // Only fall back to per-channel HTTP if maps had no match.
                if (mediaRuntime == null) {
                    mediaRuntime = zlmClient.queryMediaRuntime(app, streamId);
                }
                if (mediaRuntime == null && !expectedStreamId.equals(streamId)) {
                    mediaRuntime = zlmClient.queryMediaRuntime(defaultApp, expectedStreamId);
                }
                if (mediaRuntime != null && mediaRuntime.app() != null && !mediaRuntime.app().isBlank()) {
                    app = mediaRuntime.app();
                }
                if (mediaRuntime != null && mediaRuntime.streamId() != null && !mediaRuntime.streamId().isBlank()) {
                    streamId = mediaRuntime.streamId();
                }

                // Determine streamReady primarily from pre-fetched mediaRuntime.
                boolean streamReady = mediaRuntime != null && mediaRuntime.streamReady();
                if (!streamReady && runtime != null) {
                    streamReady = runtime.streamReady();
                }
                if (!streamReady) {
                    streamReady = zlmClient.isStreamReady(app, streamId);
                    if (!streamReady && !expectedStreamId.equals(streamId)) {
                        streamReady = zlmClient.isStreamReady(defaultApp, expectedStreamId);
                    }
                }

                // Determine recording primarily from pre-fetched mediaRuntime.
                boolean recording = mediaRuntime != null && mediaRuntime.mp4Recording();
                if (!recording && runtime != null) {
                    recording = runtime.recording();
                }
                if (!recording) {
                    recording = zlmClient.isMp4Recording(app, streamId);
                    if (!recording && !expectedStreamId.equals(streamId)) {
                        recording = zlmClient.isMp4Recording(defaultApp, expectedStreamId);
                    }
                }

                boolean sessionActive = runtime != null || mediaRuntime != null || streamReady || recording;
                boolean recordingEnabled = runtime != null
                        ? runtime.recordingEnabled()
                        : policy.recordEnabled() && device.online();
                boolean backgroundPinned = runtime != null && runtime.backgroundPinned();
                int viewerCount = runtime == null ? 0 : runtime.viewerCount();
                String updatedAt = runtime == null ? null : runtime.updatedAt();

                result.add(new BackgroundRecordingStatus(
                        device.id(),
                        device.deviceId(),
                        device.name(),
                        device.online(),
                        channel.channelId(),
                        channel.name(),
                        channel.codec(),
                        policy.recordEnabled() && device.online(),
                        managedChannels.containsKey(channelKey),
                        sessionActive,
                        streamReady,
                        recordingEnabled,
                        recording,
                        backgroundPinned,
                        viewerCount,
                        app,
                        streamId,
                        lastErrorByChannel.get(channelKey),
                        updatedAt));
            }
        }
        result.sort(Comparator.comparingLong(BackgroundRecordingStatus::devicePk)
                .thenComparing(BackgroundRecordingStatus::channelId));
        return result;
    }

    private Map<String, ManagedChannel> collectTargetChannels() {
        Map<String, ManagedChannel> targets = new HashMap<>();
        List<Device> devices = deviceService.listDevices();
        for (Device device : devices) {
            if (!device.online()) {
                continue;
            }
            List<DeviceChannel> channels = deviceService.listChannels(device.id());
            for (DeviceChannel channel : channels) {
                ManagedChannel managedChannel = new ManagedChannel(
                        device.id(),
                        device.deviceId(),
                        channel.channelId());
                targets.put(key(device.id(), channel.channelId()), managedChannel);
            }
        }
        return targets;
    }

    private void releaseAllManaged(String reason) {
        for (ManagedChannel channel : managedChannels.values()) {
            try {
                previewService.releaseBackgroundRecording(channel.devicePk(), channel.channelId());
            } catch (Exception ex) {
                log.warn("release managed background recording failed. deviceId={}, channelId={}, err={}",
                        channel.deviceId(), channel.channelId(), ex.getMessage());
            }
        }
        if (!managedChannels.isEmpty()) {
            log.info("background recording released. reason={}, count={}", reason, managedChannels.size());
        }
        managedChannels.clear();
        lastErrorByChannel.clear();
    }

    private String key(long devicePk, String channelId) {
        return devicePk + ":" + channelId;
    }

    private String buildStreamId(String channelId) {
        return ("ch" + channelId).replaceAll("[^A-Za-z0-9_\\-]", "");
    }

    private ZlmClient.MediaRuntime resolveMediaRuntime(
            Map<String, ZlmClient.MediaRuntime> runtimeByDefaultApp,
            Map<String, ZlmClient.MediaRuntime> runtimeByAnyApp,
            String expectedStreamId,
            String runtimeStreamId) {
        if (runtimeStreamId != null && !runtimeStreamId.isBlank()) {
            ZlmClient.MediaRuntime byRuntime = getRuntime(runtimeByDefaultApp, runtimeStreamId);
            if (byRuntime == null) {
                byRuntime = getRuntime(runtimeByAnyApp, runtimeStreamId);
            }
            if (byRuntime != null) {
                return byRuntime;
            }
        }
        ZlmClient.MediaRuntime byExpected = getRuntime(runtimeByDefaultApp, expectedStreamId);
        if (byExpected == null) {
            byExpected = getRuntime(runtimeByAnyApp, expectedStreamId);
        }
        return byExpected;
    }

    private ZlmClient.MediaRuntime getRuntime(Map<String, ZlmClient.MediaRuntime> runtimeMap, String streamId) {
        if (runtimeMap == null || runtimeMap.isEmpty() || streamId == null || streamId.isBlank()) {
            return null;
        }
        ZlmClient.MediaRuntime runtime = runtimeMap.get(streamId);
        if (runtime != null) {
            return runtime;
        }
        String normalized = normalizeStreamId(streamId);
        if (normalized == null || normalized.equals(streamId)) {
            return null;
        }
        return runtimeMap.get(normalized);
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

    private record ManagedChannel(
            long devicePk,
            String deviceId,
            String channelId) {
    }

    public record BackgroundRecordingStatus(
            long devicePk,
            String deviceId,
            String deviceName,
            boolean deviceOnline,
            String channelId,
            String channelName,
            String channelCodec,
            boolean targetRecording,
            boolean managedByScheduler,
            boolean sessionActive,
            boolean streamReady,
            boolean recordingEnabled,
            boolean recording,
            boolean backgroundPinned,
            int viewerCount,
            String app,
            String streamId,
            String lastError,
            String updatedAt) {
    }
}
