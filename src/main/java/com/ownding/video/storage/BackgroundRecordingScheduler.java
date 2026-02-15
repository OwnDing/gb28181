package com.ownding.video.storage;

import com.ownding.video.device.Device;
import com.ownding.video.device.DeviceChannel;
import com.ownding.video.device.DeviceService;
import com.ownding.video.media.PreviewService;
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
    private final Map<String, ManagedChannel> managedChannels = new HashMap<>();
    private final Map<String, String> lastErrorByChannel = new HashMap<>();

    public BackgroundRecordingScheduler(DeviceService deviceService, StorageService storageService,
            PreviewService previewService) {
        this.deviceService = deviceService;
        this.storageService = storageService;
        this.previewService = previewService;
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
        List<BackgroundRecordingStatus> result = new ArrayList<>();
        List<Device> devices = deviceService.listDevices();
        for (Device device : devices) {
            List<DeviceChannel> channels = deviceService.listChannels(device.id());
            for (DeviceChannel channel : channels) {
                String channelKey = key(device.id(), channel.channelId());
                Optional<PreviewService.ChannelRuntime> runtimeOpt = previewService.findChannelRuntime(
                        device.id(),
                        device.deviceId(),
                        channel.channelId()
                );
                PreviewService.ChannelRuntime runtime = runtimeOpt.orElse(null);
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
                        runtime != null,
                        runtime != null && runtime.streamReady(),
                        runtime != null && runtime.recordingEnabled(),
                        runtime != null && runtime.recording(),
                        runtime != null && runtime.backgroundPinned(),
                        runtime == null ? 0 : runtime.viewerCount(),
                        runtime == null ? null : runtime.app(),
                        runtime == null ? null : runtime.streamId(),
                        lastErrorByChannel.get(channelKey),
                        runtime == null ? null : runtime.updatedAt()
                ));
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
                        channel.channelId()
                );
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
