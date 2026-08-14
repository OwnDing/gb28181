package com.ownding.video.config;


import java.io.IOException;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.reactive.config.ResourceHandlerRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.resource.PathResourceResolver;

import reactor.core.publisher.Mono;

/**
 * SPA 路由回退：前端 history 路由（如 /login）直访时返回 index.html，
 * 避免 404 No static resource。
 */
@Configuration
public class SpaFallbackConfig implements WebFluxConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Mono<Resource> getResource(String resourcePath, Resource location) {
                        try {
                            Resource requested = location.createRelative(resourcePath);
                            if (requested.isReadable()) {
                                return Mono.just(requested);
                            }
                        } catch (IOException ignored) {
                            // 路径解析失败时回退到 index.html
                        }
                        return Mono.just(new ClassPathResource("/static/index.html"));
                    }
                });
    }
}
