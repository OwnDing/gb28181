package com.ownding.video.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaForwardController {

    @GetMapping({
            "/",
            "/login",
            "/devices",
            "/video-preview",
            "/video-playback",
            "/storage-settings",
            "/gb28181",
            "/alarm-history",
            "/m",
            "/m/login",
            "/m/home",
            "/m/devices",
            "/m/preview",
            "/m/alarms",
            "/m/more",
            "/m/playback",
            "/m/settings",
            "/m/gb28181"
    })
    public String forwardToIndex() {
        return "forward:/index.html";
    }
}
