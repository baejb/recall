package com.recall;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Recall — single-user, self-hosted personal memory system.
 *
 * <p>Modular monolith (Memory Core). Store path runs as async jobs; query path is
 * synchronous SSE. Nothing reaches {@code memory} without passing the review gate.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
public class RecallApplication {

    public static void main(String[] args) {
        SpringApplication.run(RecallApplication.class, args);
    }
}
