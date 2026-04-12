package com.ownding.video.push;

import com.ownding.video.auth.AuthContext;
import com.ownding.video.auth.AuthWebFilter;
import com.ownding.video.common.ApiException;
import com.ownding.video.common.ApiResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/app")
public class PushTokenController {

    private final PushTokenService pushTokenService;

    public PushTokenController(PushTokenService pushTokenService) {
        this.pushTokenService = pushTokenService;
    }

    @PostMapping("/push-tokens")
    public ApiResult<MobilePushToken> registerPushToken(
            @Valid @RequestBody RegisterPushTokenRequest request,
            ServerWebExchange exchange) {
        AuthContext authContext = requireAuth(exchange);
        return ApiResult.success(pushTokenService.register(authContext, new PushTokenService.RegisterCommand(
                request.token(),
                request.tokenType(),
                request.platform(),
                request.deviceName(),
                request.appVersion(),
                request.permissionStatus(),
                request.projectId())));
    }

    @GetMapping("/push-tokens")
    public ApiResult<List<MobilePushToken>> listPushTokens(ServerWebExchange exchange) {
        AuthContext authContext = requireAuth(exchange);
        return ApiResult.success(pushTokenService.list(authContext));
    }

    private AuthContext requireAuth(ServerWebExchange exchange) {
        Object value = exchange.getAttribute(AuthWebFilter.AUTH_CONTEXT_KEY);
        if (value instanceof AuthContext authContext) {
            return authContext;
        }
        throw new ApiException(401, "未登录或登录已过期");
    }

    public record RegisterPushTokenRequest(
            @NotBlank(message = "不能为空") String token,
            @NotBlank(message = "不能为空") String tokenType,
            @NotBlank(message = "不能为空") String platform,
            String deviceName,
            String appVersion,
            String permissionStatus,
            String projectId) {
    }
}
