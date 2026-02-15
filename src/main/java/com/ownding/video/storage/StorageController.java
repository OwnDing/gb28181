package com.ownding.video.storage;

import com.ownding.video.common.ApiResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api")
public class StorageController {

    private final StorageService storageService;
    private final BackgroundRecordingScheduler backgroundRecordingScheduler;

    public StorageController(StorageService storageService,
            BackgroundRecordingScheduler backgroundRecordingScheduler) {
        this.storageService = storageService;
        this.backgroundRecordingScheduler = backgroundRecordingScheduler;
    }

    @GetMapping("/storage/policy")
    public ApiResult<StoragePolicy> getPolicy() {
        return ApiResult.success(storageService.getPolicy());
    }

    @PutMapping("/storage/policy")
    public ApiResult<StoragePolicy> updatePolicy(@Valid @RequestBody UpdatePolicyRequest request) {
        StoragePolicy policy = storageService.updatePolicy(new StorageService.UpdatePolicyCommand(
                request.retentionDays(),
                request.maxStorageGb(),
                request.autoOverwrite(),
                request.recordEnabled(),
                request.recordPath()
        ));
        return ApiResult.success("存储策略更新成功", policy);
    }

    @GetMapping("/storage/usage")
    public ApiResult<StorageUsage> usage() {
        return ApiResult.success(storageService.getUsage());
    }

    @PostMapping("/storage/cleanup")
    public ApiResult<StorageUsage> cleanupNow() {
        return ApiResult.success("清理完成", storageService.executeCleanup());
    }

    @GetMapping("/storage/background-recording/status")
    public ApiResult<List<BackgroundRecordingScheduler.BackgroundRecordingStatus>> backgroundRecordingStatus() {
        return ApiResult.success(backgroundRecordingScheduler.listStatuses());
    }

    @GetMapping("/records")
    public ApiResult<List<RecordFileItem>> listRecords() {
        return ApiResult.success(storageService.listRecords());
    }

    @DeleteMapping("/records/{id}")
    public ApiResult<Void> deleteRecord(@PathVariable long id) {
        storageService.deleteRecord(id);
        return ApiResult.successMessage("录像已删除");
    }

    public record UpdatePolicyRequest(
            @Min(value = 1, message = "必须大于0") int retentionDays,
            @Min(value = 1, message = "必须大于0") int maxStorageGb,
            boolean autoOverwrite,
            boolean recordEnabled,
            @NotBlank(message = "不能为空") String recordPath
    ) {
    }
}
