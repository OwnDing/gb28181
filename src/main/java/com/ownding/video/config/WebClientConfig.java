package com.ownding.video.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;

import java.time.Duration;

@Configuration
public class WebClientConfig {

    @Bean
    public ConnectionProvider zlmConnectionProvider() {
        return ConnectionProvider.builder("zlm")
                .maxConnections(8)
                .maxIdleTime(Duration.ofSeconds(30))
                .build();
    }

    @Bean
    public WebClient.Builder webClientBuilder(ConnectionProvider zlmConnectionProvider) {
        HttpClient httpClient = HttpClient.create(zlmConnectionProvider);
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient));
    }

    @Bean
    public WebClient webClient(WebClient.Builder builder) {
        return builder.build();
    }
}
