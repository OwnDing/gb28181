package com.ownding.video.gb28181;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api/alarms")
public class AlarmController {

    private final Gb28181Repository repository;
    private static final String SNAPSHOT_DIR = "www/snap/alarms";

    public AlarmController(Gb28181Repository repository) {
        this.repository = repository;
        // Ensure snapshot directory exists
        try {
            Files.createDirectories(Paths.get(SNAPSHOT_DIR));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @GetMapping
    public List<GbAlarmEvent> listAlarms(@RequestParam(defaultValue = "100") int limit) {
        return repository.listAlarms(limit);
    }

    @PostMapping
    public void receiveAlarm(
            @RequestParam("deviceId") String deviceId,
            @RequestParam("channelId") String channelId,
            @RequestParam("timestamp") long timestamp,
            @RequestParam(value = "file", required = false) MultipartFile file) {
        String snapshotUrl = null;
        if (file != null && !file.isEmpty()) {
            try {
                String filename = deviceId + "_" + channelId + "_" + timestamp + ".jpg";
                Path path = Paths.get(SNAPSHOT_DIR, filename);
                Files.copy(file.getInputStream(), path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                // URL assumed to be served statically from /snap/alarms/
                snapshotUrl = "/snap/alarms/" + filename;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        String timeStr = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(timestamp));

        Gb28181Repository.UpsertAlarmEventCommand command = new Gb28181Repository.UpsertAlarmEventCommand(
                deviceId,
                channelId,
                "AI_DETECTION",
                "PERSON",
                "1",
                timeStr,
                null, // longitude
                null, // latitude
                "Person Detected by AI",
                null, // rawXml
                snapshotUrl,
                null // videoPath detection usually doesn't have immediate video path, frontend can
                     // calculate from time
        );
        repository.insertAlarm(command);
    }
}
