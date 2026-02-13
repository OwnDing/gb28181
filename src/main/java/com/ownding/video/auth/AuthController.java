package com.ownding.video.auth;

import com.ownding.video.common.ApiException;
import com.ownding.video.common.ApiResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResult<AuthService.LoginResult> login(@Valid @RequestBody LoginRequest request) {
        return ApiResult.success(authService.login(request.username(), request.password()));
    }

    @GetMapping("/me")
    public ApiResult<Map<String, Object>> me(@RequestHeader(name = "Authorization", required = false) String authHeader) {
        String token = extractToken(authHeader);
        AuthContext context = authService.authenticate(token);
        return ApiResult.success(Map.of(
                "userId", context.userId(),
                "username", context.username(),
                "role", context.role()
        ));
    }

    @PostMapping("/logout")
    public ApiResult<Void> logout(@RequestHeader(name = "Authorization", required = false) String authHeader) {
        String token = extractToken(authHeader);
        authService.logout(token);
        return ApiResult.successMessage("已退出登录");
    }

    private String extractToken(String authHeader) {
        if (authHeader == null || authHeader.isBlank() || !authHeader.startsWith("Bearer ")) {
            throw new ApiException(401, "未登录或登录已过期");
        }
        return authHeader.substring("Bearer ".length()).trim();
    }

    public record LoginRequest(
            @NotBlank(message = "不能为空") String username,
            @NotBlank(message = "不能为空") String password
    ) {
    }
}
