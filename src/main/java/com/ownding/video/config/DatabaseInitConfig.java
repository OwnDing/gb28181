package com.ownding.video.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class DatabaseInitConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitConfig.class);
    private final JdbcClient jdbcClient;

    public DatabaseInitConfig(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @PostConstruct
    public void initPragma() {
        try {
            jdbcClient.sql("PRAGMA journal_mode=WAL;").query(String.class).single();
            jdbcClient.sql("PRAGMA synchronous=NORMAL;").update();
            jdbcClient.sql("PRAGMA foreign_keys=ON;").update();
        } catch (Exception ex) {
            log.warn("SQLite PRAGMA init failed: {}", ex.getMessage());
        }
    }
}
