package com.ownding.video;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiSmokeTests {

    @LocalServerPort
    private int port;

    @Test
    void loginShouldWork() throws Exception {
        WebTestClient client = createClient();
        String token = loginAndGetToken(client);
        assertNotNull(token);
        assertFalse(token.isBlank());
    }

    @Test
    void deviceCrudShouldWork() throws Exception {
        WebTestClient client = createClient();
        String token = loginAndGetToken(client);
        String deviceId = randomDeviceId();
        AtomicReference<Long> idRef = new AtomicReference<>();

        client.post()
                .uri("/api/devices")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "name": "测试设备",
                          "deviceId": "%s",
                          "ip": "192.168.1.200",
                          "port": 5060,
                          "transport": "UDP",
                          "manufacturer": "TEST",
                          "channelCount": 2,
                          "preferredCodec": "H264"
                        }
                        """.formatted(deviceId))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.id").exists()
                .jsonPath("$.data.id").value(value -> {
                    if (value instanceof Number number) {
                        idRef.set(number.longValue());
                        return;
                    }
                    idRef.set(Long.parseLong(String.valueOf(value)));
                });

        long id = idRef.get();

        client.get()
                .uri("/api/devices/" + id + "/channels")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.length()").isEqualTo(2);

        client.put()
                .uri("/api/devices/" + id)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "name": "测试设备-更新",
                          "deviceId": "%s",
                          "ip": "192.168.1.201",
                          "port": 5060,
                          "transport": "TCP",
                          "manufacturer": "TEST",
                          "channelCount": 1,
                          "preferredCodec": "H265"
                        }
                        """.formatted(deviceId))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.transport").isEqualTo("TCP")
                .jsonPath("$.data.preferredCodec").isEqualTo("H265");

        client.delete()
                .uri("/api/devices/" + id)
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0);
    }

    private WebTestClient createClient() {
        return WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .build();
    }

    private String loginAndGetToken(WebTestClient client) {
        AtomicReference<String> tokenRef = new AtomicReference<>();
        client.post()
                .uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "username": "admin",
                          "password": "admin123"
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.token").isNotEmpty()
                .jsonPath("$.data.username").isEqualTo("admin")
                .jsonPath("$.data.token").value(value -> tokenRef.set(String.valueOf(value)));
        return tokenRef.get();
    }

    private String randomDeviceId() {
        long value = ThreadLocalRandom.current().nextLong(0, 100_000_000_000_000L);
        return "340200" + String.format("%014d", value);
    }
}
