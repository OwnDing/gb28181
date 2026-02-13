package com.ownding.video.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class StorageCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(StorageCleanupScheduler.class);
    private final StorageService storageService;

    public StorageCleanupScheduler(StorageService storageService) {
        this.storageService = storageService;
    }

    @Scheduled(fixedDelayString = "${app.storage.cleanup-interval-ms:180000}")
    public void cleanupTask() {
        try {
            StorageUsage usage = storageService.executeCleanup();
            log.info("storage cleanup done, files={}, usedGb={}", usage.fileCount(), String.format("%.2f", usage.usedGb()));
        } catch (Exception ex) {
            log.warn("storage cleanup failed: {}", ex.getMessage());
        }
    }
}
