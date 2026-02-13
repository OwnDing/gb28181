package com.ownding.video.auth;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthWebFilter implements WebFilter {

    public static final String AUTH_CONTEXT_KEY = "AUTH_CONTEXT";
    private static final Set<String> OPEN_API = Set.of("/api/auth/login");

    private final AuthService authService;

    public AuthWebFilter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (HttpMethod.OPTIONS.equals(exchange.getRequest().getMethod())) {
            return chain.filter(exchange);
        }
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/api/") || OPEN_API.contains(path)) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorized(exchange.getResponse(), "未登录或登录已过期");
        }

        String token = authHeader.substring("Bearer ".length()).trim();
        return authService.authenticateOptional(token)
                .map(context -> {
                    exchange.getAttributes().put(AUTH_CONTEXT_KEY, context);
                    return chain.filter(exchange);
                })
                .orElseGet(() -> unauthorized(exchange.getResponse(), "未登录或登录已过期"));
    }

    private Mono<Void> unauthorized(ServerHttpResponse response, String message) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String safeMessage = message.replace("\"", "\\\"");
        String body = "{\"code\":401,\"message\":\"" + safeMessage + "\",\"data\":null}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }
}
