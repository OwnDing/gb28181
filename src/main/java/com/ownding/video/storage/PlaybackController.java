package com.ownding.video.storage;

import com.ownding.video.common.ApiResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api/playback")
public class PlaybackController {

    private static final Logger log = LoggerFactory.getLogger(PlaybackController.class);

    private final StorageService storageService;

    public PlaybackController(StorageService storageService) {
        this.storageService = storageService;
    }

    /**
     * Lists channels that have recording files.
     */
    @GetMapping("/channels")
    public ApiResult<List<StorageService.PlaybackChannel>> channels() {
        return ApiResult.success(storageService.listPlaybackChannels());
    }

    /**
     * Queries recording segments for a given channel and date.
     *
     * @param channelId channel ID (e.g. 34020000001320000001)
     * @param date      date in yyyy-MM-dd format
     */
    @GetMapping("/records")
    public ApiResult<List<RecordFileItem>> records(
            @RequestParam String channelId,
            @RequestParam String date) {
        return ApiResult.success(storageService.queryPlaybackRecords(channelId, date));
    }

    /**
     * Streams an MP4 file for playback. Supports HTTP Range requests for seeking.
     *
     * @param path relative path within the record directory (e.g.
     *             record/rtp/ch.../2026-02-17/xxx.mp4)
     */
    @GetMapping("/video")
    public ResponseEntity<Resource> video(
            @RequestParam String path,
            ServerWebExchange exchange) {
        Path recordRoot = storageService.getRecordPath().toAbsolutePath().normalize();
        Path filePath = recordRoot.resolve(path).toAbsolutePath().normalize();

        // Security check: file must be within recordRoot
        log.debug("Video request: recordRoot={}, filePath={}", recordRoot, filePath);
        if (!filePath.startsWith(recordRoot)) {
            log.warn("路径越权访问: {} 不在 {} 内", filePath, recordRoot);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            log.warn("文件不存在: {}", filePath);
            return ResponseEntity.notFound().build();
        }

        long fileSize;
        try {
            fileSize = Files.size(filePath);
        } catch (IOException e) {
            log.error("无法读取文件大小: {}", filePath, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        Resource resource = new FileSystemResource(filePath);

        // Handle Range header for seeking
        String rangeHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.RANGE);
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            return handleRangeRequest(rangeHeader, fileSize, resource);
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .contentLength(fileSize)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .body(resource);
    }

    private ResponseEntity<Resource> handleRangeRequest(String rangeHeader, long fileSize, Resource resource) {
        String rangeValue = rangeHeader.substring("bytes=".length()).trim();
        String[] parts = rangeValue.split("-", 2);

        long start = 0;
        long end = fileSize - 1;

        try {
            if (!parts[0].isEmpty()) {
                start = Long.parseLong(parts[0]);
            }
            if (parts.length > 1 && !parts[1].isEmpty()) {
                end = Long.parseLong(parts[1]);
            }
        } catch (NumberFormatException e) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE).build();
        }

        if (start > end || start >= fileSize) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + fileSize)
                    .build();
        }

        end = Math.min(end, fileSize - 1);
        long contentLength = end - start + 1;

        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .contentType(MediaType.parseMediaType("video/mp4"))
                .contentLength(contentLength)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + fileSize)
                .body(resource);
    }
}
