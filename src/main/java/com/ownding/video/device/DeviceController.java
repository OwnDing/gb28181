package com.ownding.video.device;

import com.ownding.video.common.ApiResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @GetMapping
    public ApiResult<List<Device>> listDevices() {
        return ApiResult.success(deviceService.listDevices());
    }

    @PostMapping
    public ApiResult<Device> createDevice(@Valid @RequestBody DeviceRequest request) {
        Device device = deviceService.createDevice(new DeviceService.CreateDeviceCommand(
                request.name(),
                request.deviceId(),
                request.ip(),
                request.port(),
                request.transport().toUpperCase(),
                request.username(),
                request.password(),
                request.manufacturer(),
                request.channelCount(),
                request.preferredCodec().toUpperCase()
        ));
        return ApiResult.success("设备注册成功", device);
    }

    @PutMapping("/{id}")
    public ApiResult<Device> updateDevice(@PathVariable long id, @Valid @RequestBody DeviceRequest request) {
        Device device = deviceService.updateDevice(id, new DeviceService.UpdateDeviceCommand(
                request.name(),
                request.deviceId(),
                request.ip(),
                request.port(),
                request.transport().toUpperCase(),
                request.username(),
                request.password(),
                request.manufacturer(),
                request.channelCount(),
                request.preferredCodec().toUpperCase()
        ));
        return ApiResult.success("设备更新成功", device);
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> deleteDevice(@PathVariable long id) {
        deviceService.deleteDevice(id);
        return ApiResult.successMessage("设备删除成功");
    }

    @PatchMapping("/{id}/status")
    public ApiResult<Device> updateStatus(@PathVariable long id, @Valid @RequestBody StatusRequest request) {
        Device device = deviceService.updateDeviceOnlineStatus(id, request.online());
        return ApiResult.success(device);
    }

    @GetMapping("/{id}/channels")
    public ApiResult<List<DeviceChannel>> listChannels(@PathVariable long id) {
        return ApiResult.success(deviceService.listChannels(id));
    }

    public record DeviceRequest(
            @NotBlank(message = "不能为空") String name,
            @NotBlank(message = "不能为空")
            @Pattern(regexp = "\\d{20}", message = "必须是20位数字")
            String deviceId,
            @NotBlank(message = "不能为空") String ip,
            @Min(value = 1, message = "必须大于0")
            @Max(value = 65535, message = "不能大于65535")
            int port,
            @NotBlank(message = "不能为空")
            @Pattern(regexp = "(?i)UDP|TCP", message = "仅支持 UDP 或 TCP")
            String transport,
            String username,
            String password,
            @NotBlank(message = "不能为空") String manufacturer,
            @Min(value = 1, message = "必须大于0")
            @Max(value = 64, message = "不能超过64")
            int channelCount,
            @NotBlank(message = "不能为空")
            @Pattern(regexp = "(?i)H264|H265", message = "仅支持 H264 或 H265")
            String preferredCodec
    ) {
    }

    public record StatusRequest(boolean online) {
    }
}
