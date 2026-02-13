package com.ownding.video.device;

import com.ownding.video.common.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class DeviceService {

    private final DeviceRepository deviceRepository;

    public DeviceService(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    public List<Device> listDevices() {
        return deviceRepository.findAllDevices();
    }

    public Device getDevice(long id) {
        return deviceRepository.findDeviceById(id)
                .orElseThrow(() -> new ApiException(404, "设备不存在"));
    }

    public Optional<Device> findDeviceByCode(String deviceCode) {
        return deviceRepository.findDeviceByCode(deviceCode);
    }

    public Device createDevice(CreateDeviceCommand command) {
        validateCodec(command.preferredCodec());
        validateTransport(command.transport());
        if (command.channelCount() < 1) {
            throw new ApiException(400, "通道数必须大于0");
        }
        try {
            return deviceRepository.createDevice(new DeviceRepository.CreateDeviceRequest(
                    command.name(),
                    command.deviceId(),
                    command.ip(),
                    command.port(),
                    command.transport(),
                    command.username(),
                    command.password(),
                    command.manufacturer(),
                    command.channelCount(),
                    command.preferredCodec()
            ));
        } catch (DuplicateKeyException ex) {
            throw new ApiException(409, "设备编码已存在");
        }
    }

    public Device updateDevice(long id, UpdateDeviceCommand command) {
        getDevice(id);
        validateCodec(command.preferredCodec());
        validateTransport(command.transport());
        if (command.channelCount() < 1) {
            throw new ApiException(400, "通道数必须大于0");
        }
        try {
            return deviceRepository.updateDevice(id, new DeviceRepository.UpdateDeviceRequest(
                    command.name(),
                    command.deviceId(),
                    command.ip(),
                    command.port(),
                    command.transport(),
                    command.username(),
                    command.password(),
                    command.manufacturer(),
                    command.channelCount(),
                    command.preferredCodec()
            ));
        } catch (DuplicateKeyException ex) {
            throw new ApiException(409, "设备编码已存在");
        }
    }

    public void deleteDevice(long id) {
        int affected = deviceRepository.deleteDevice(id);
        if (affected == 0) {
            throw new ApiException(404, "设备不存在");
        }
    }

    public Device updateDeviceOnlineStatus(long id, boolean online) {
        getDevice(id);
        deviceRepository.updateDeviceOnlineStatus(id, online);
        return getDevice(id);
    }

    public boolean updateDeviceOnlineStatusByCode(String deviceCode, boolean online) {
        return deviceRepository.updateDeviceOnlineStatusByCode(deviceCode, online) > 0;
    }

    public List<DeviceChannel> listChannels(long deviceId) {
        getDevice(deviceId);
        return deviceRepository.findChannelsByDeviceId(deviceId);
    }

    public DeviceChannel resolveChannel(long deviceId, String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return deviceRepository.findFirstChannelByDeviceId(deviceId)
                    .orElseThrow(() -> new ApiException(400, "设备没有可用通道"));
        }
        return deviceRepository.findChannelByDeviceIdAndChannelId(deviceId, channelId)
                .orElseThrow(() -> new ApiException(404, "通道不存在"));
    }

    private void validateCodec(String codec) {
        if (!"H264".equalsIgnoreCase(codec) && !"H265".equalsIgnoreCase(codec)) {
            throw new ApiException(400, "编码仅支持 H264 或 H265");
        }
    }

    private void validateTransport(String transport) {
        if (!"UDP".equalsIgnoreCase(transport) && !"TCP".equalsIgnoreCase(transport)) {
            throw new ApiException(400, "传输协议仅支持 UDP 或 TCP");
        }
    }

    public record CreateDeviceCommand(
            String name,
            String deviceId,
            String ip,
            int port,
            String transport,
            String username,
            String password,
            String manufacturer,
            int channelCount,
            String preferredCodec
    ) {
    }

    public record UpdateDeviceCommand(
            String name,
            String deviceId,
            String ip,
            int port,
            String transport,
            String username,
            String password,
            String manufacturer,
            int channelCount,
            String preferredCodec
    ) {
    }
}
