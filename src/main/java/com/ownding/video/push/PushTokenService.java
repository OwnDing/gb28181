package com.ownding.video.push;

import com.ownding.video.auth.AuthContext;
import com.ownding.video.common.ApiException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PushTokenService {

    private final PushTokenRepository pushTokenRepository;

    public PushTokenService(PushTokenRepository pushTokenRepository) {
        this.pushTokenRepository = pushTokenRepository;
    }

    public MobilePushToken register(AuthContext authContext, RegisterCommand command) {
        validateCommand(command);
        return pushTokenRepository.upsert(authContext.userId(), authContext.username(), command);
    }

    public List<MobilePushToken> list(AuthContext authContext) {
        return pushTokenRepository.listByUserId(authContext.userId());
    }

    private void validateCommand(RegisterCommand command) {
        if (command.token() == null || command.token().isBlank()) {
            throw new ApiException(400, "push token 不能为空");
        }
        if (command.tokenType() == null || command.tokenType().isBlank()) {
            throw new ApiException(400, "tokenType 不能为空");
        }
        if (command.platform() == null || command.platform().isBlank()) {
            throw new ApiException(400, "platform 不能为空");
        }
    }

    public record RegisterCommand(
            String token,
            String tokenType,
            String platform,
            String deviceName,
            String appVersion,
            String permissionStatus,
            String projectId) {
    }
}
