package com.ownding.video.gb28181;

import com.ownding.video.common.ApiResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

@Validated
@RestController
@RequestMapping("/api/gb28181")
public class Gb28181Controller {

    private final Gb28181Service gb28181Service;

    public Gb28181Controller(Gb28181Service gb28181Service) {
        this.gb28181Service = gb28181Service;
    }

    @PostMapping("/devices/{deviceId}/queries/device-info")
    public ApiResult<SipSignalService.SipCommandResult> queryDeviceInfo(
            @PathVariable @NotBlank(message = "不能为空") String deviceId) {
        return ApiResult.success(gb28181Service.queryDeviceInfo(deviceId));
    }

    @PostMapping("/devices/{deviceId}/queries/catalog")
    public ApiResult<SipSignalService.SipCommandResult> queryCatalog(
            @PathVariable @NotBlank(message = "不能为空") String deviceId) {
        return ApiResult.success(gb28181Service.queryCatalog(deviceId));
    }

    @PostMapping("/devices/{deviceId}/queries/records")
    public ApiResult<SipSignalService.SipCommandResult> queryRecords(
            @PathVariable @NotBlank(message = "不能为空") String deviceId,
            @Valid @RequestBody RecordQueryRequest request) {
        return ApiResult.success(gb28181Service.queryRecordInfo(deviceId, new Gb28181Service.RecordQueryCommand(
                request.channelId(),
                request.startTime(),
                request.endTime(),
                request.secrecy(),
                request.type())));
    }

    @GetMapping("/devices/{deviceId}/profile")
    public ApiResult<Optional<GbDeviceProfile>> getProfile(
            @PathVariable @NotBlank(message = "不能为空") String deviceId) {
        return ApiResult.success(gb28181Service.getProfile(deviceId));
    }

    @GetMapping("/devices/{deviceId}/catalog")
    public ApiResult<List<GbCatalogItem>> listCatalog(
            @PathVariable @NotBlank(message = "不能为空") String deviceId) {
        return ApiResult.success(gb28181Service.listCatalog(deviceId));
    }

    @GetMapping("/devices/{deviceId}/records")
    public ApiResult<List<GbRecordItem>> listRecords(
            @PathVariable @NotBlank(message = "不能为空") String deviceId,
            @RequestParam(required = false) String channelId,
            @RequestParam(defaultValue = "100") @Min(value = 1, message = "必须大于0") int limit) {
        return ApiResult.success(gb28181Service.listRecordItems(deviceId, channelId, limit));
    }

    @GetMapping("/alarms")
    public ApiResult<List<GbAlarmEvent>> listAlarms(
            @RequestParam(required = false) String deviceId,
            @RequestParam(defaultValue = "100") @Min(value = 1, message = "必须大于0") int limit) {
        return ApiResult.success(gb28181Service.listAlarms(deviceId, limit));
    }

    @GetMapping("/mobile-positions")
    public ApiResult<List<GbMobilePosition>> listMobilePositions(
            @RequestParam(required = false) String deviceId,
            @RequestParam(defaultValue = "100") @Min(value = 1, message = "必须大于0") int limit) {
        return ApiResult.success(gb28181Service.listMobilePositions(deviceId, limit));
    }

    @PostMapping("/devices/{deviceId}/subscriptions")
    public ApiResult<Gb28181Service.SubscriptionResult> subscribe(
            @PathVariable @NotBlank(message = "不能为空") String deviceId,
            @Valid @RequestBody SubscribeRequest request) {
        return ApiResult.success(gb28181Service.subscribe(deviceId, new Gb28181Service.SubscribeCommand(
                request.eventType(),
                request.expires())));
    }

    @DeleteMapping("/subscriptions/{id}")
    public ApiResult<SipSignalService.SipCommandResult> unsubscribe(@PathVariable long id) {
        return ApiResult.success(gb28181Service.unsubscribe(id));
    }

    @GetMapping("/subscriptions")
    public ApiResult<List<GbSubscription>> listSubscriptions(@RequestParam(required = false) String deviceId) {
        return ApiResult.success(gb28181Service.listSubscriptions(deviceId));
    }

    @GetMapping("/playback-sessions")
    public ApiResult<List<GbPlaybackSession>> listPlaybackSessions() {
        return ApiResult.success(gb28181Service.listPlaybackSessions());
    }

    // ===== Playback Control =====

    @PostMapping("/devices/{deviceId}/playback")
    public ApiResult<Gb28181Service.PlaybackStartResult> startPlayback(
            @PathVariable @NotBlank(message = "不能为空") String deviceId,
            @Valid @RequestBody PlaybackRequest request) {
        return ApiResult.success(gb28181Service.startPlayback(deviceId,
                new Gb28181Service.PlaybackCommand(request.channelId(), request.startTime(), request.endTime())));
    }

    @PostMapping("/playback-sessions/{sessionId}/control")
    public ApiResult<SipSignalService.SipCommandResult> controlPlayback(
            @PathVariable @NotBlank(message = "不能为空") String sessionId,
            @Valid @RequestBody PlaybackControlRequest request) {
        return ApiResult.success(gb28181Service.controlPlayback(sessionId,
                new Gb28181Service.PlaybackControlCommand(request.action(), request.speed(), request.seekSeconds())));
    }

    @DeleteMapping("/playback-sessions/{sessionId}")
    public ApiResult<Void> stopPlayback(
            @PathVariable @NotBlank(message = "不能为空") String sessionId) {
        gb28181Service.stopPlayback(sessionId);
        return ApiResult.success(null);
    }

    // ===== PTZ Control =====

    @PostMapping("/devices/{deviceId}/ptz")
    public ApiResult<SipSignalService.SipCommandResult> ptzControl(
            @PathVariable @NotBlank(message = "不能为空") String deviceId,
            @Valid @RequestBody PtzRequest request) {
        return ApiResult.success(gb28181Service.ptzControl(deviceId,
                new Gb28181Service.PtzCommand(request.channelId(), request.action(), request.speed(),
                        request.presetNo())));
    }

    // ===== Request DTOs =====

    public record RecordQueryRequest(
            String channelId,
            String startTime,
            String endTime,
            String secrecy,
            String type) {
    }

    public record SubscribeRequest(
            @NotBlank(message = "不能为空") String eventType,
            Integer expires) {
    }

    public record PlaybackRequest(
            String channelId,
            @NotBlank(message = "不能为空") String startTime,
            @NotBlank(message = "不能为空") String endTime) {
    }

    public record PlaybackControlRequest(
            @NotBlank(message = "不能为空") String action,
            Double speed,
            Long seekSeconds) {
    }

    public record PtzRequest(
            String channelId,
            @NotBlank(message = "不能为空") String action,
            Integer speed,
            Integer presetNo) {
    }
}
