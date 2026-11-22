package de.labradt.dt_engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point. Component scanning starts from this package,
 * so every {@code @Component}, {@code @RestController} and
 * {@code @Repository} under {@code org.example.machinetool} is picked up
 * automatically, nothing is registered here by hand.
 *
 * Once Spring finishes booting, StateChartExecutor.onApplicationReady()
 * and GatewayStreamClient.startListening() both fire on the
 * ApplicationReadyEvent this triggers: the engine initializes itself and
 * the Gateway SSE listener starts, with no separate step needed after
 * this method returns. Business execution defaults to STOPPED until runtime Start.
 */
@SpringBootApplication
public class Main {
    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }
}
