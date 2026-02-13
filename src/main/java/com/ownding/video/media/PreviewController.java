package com.ownding.video.media;

import com.ownding.video.common.ApiResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/preview")
public class PreviewController {

    private final PreviewService previewService;

    public PreviewController(PreviewService previewService) {
        this.previewService = previewService;
    }

    @PostMapping("/start")
    public ApiResult<PreviewService.StartPreviewResult> startPreview(@Valid @RequestBody StartRequest request) {
        return ApiResult.success(previewService.startPreview(new PreviewService.StartPreviewCommand(
                request.devicePk(),
                request.channelId(),
                request.protocol(),
                request.browserSupportsH265()
        )));
    }

    @PostMapping("/stop")
    public ApiResult<Void> stopPreview(@Valid @RequestBody StopRequest request) {
        previewService.stopPreview(request.sessionId());
        return ApiResult.successMessage("已停止预览");
    }

    @GetMapping("/status")
    public ApiResult<List<PreviewService.SessionStatus>> status() {
        return ApiResult.success(previewService.listSessions());
    }

    public record StartRequest(
            long devicePk,
            String channelId,
            String protocol,
            boolean browserSupportsH265
    ) {
    }

    public record StopRequest(@NotBlank(message = "不能为空") String sessionId) {
    }
}
