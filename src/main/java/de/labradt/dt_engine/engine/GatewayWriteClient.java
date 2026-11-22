package de.labradt.dt_engine.engine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

/**
 * Sends correction values back to the external Gateway after a service
 * completes. Called from exactly one place,
 * StateChartExecutor.onServiceResult(), once a corrected value has passed
 * the write back safety check.
 *
 * The Gateway's write endpoint format:
 *   PATCH {gateway.write.url}/api/submodel/{machineId}.{elementId}/$value
 *   Content-Type: application/json
 *   Body: raw value string, for example "42.5"
 *
 * Configure in application.properties:
 *   gateway.write.url=http://localhost:8085
 *
 * Note: uses Apache HttpClient through HttpComponentsClientHttpRequestFactory
 * because the default RestTemplate does not support PATCH.
 * Requires the httpclient5 dependency in pom.xml.
 *
 * This is a concrete class, not an interface, with no extension point of
 * its own. If a second write back transport were ever needed, the clean
 * way to add it would be to introduce an interface here and make
 * StateChartExecutor depend on that instead, following the same pattern
 * already used for storage (see IStorageService).
 */
@Slf4j
@Component
public class GatewayWriteClient {

    @Value("${gateway.write.url:#{null}}")
    private String baseWriteUrl;

    @Value("${gateway.machineId:cnc-1}")
    private String machineId;

    /** Max time to open the connection to the Gateway (ms). Env var GATEWAY_WRITE_CONNECT_TIMEOUT_MS. */
    @Value("${gateway.write.connect-timeout-ms:3000}")
    private int connectTimeoutMs = 3000;

    /**
     * Max time to wait for the Gateway's answer (ms). The Gateway waits up to 5 s for an
     *
     */
    @Value("${gateway.write.read-timeout-ms:10000}")
    private int readTimeoutMs = 10000;

    private RestTemplate restTemplate = createRestTemplate(connectTimeoutMs, readTimeoutMs);

    /** Rebuilds the client once Spring has injected the configured timeouts. */
    @javax.annotation.PostConstruct
    void applyTimeouts() {
        restTemplate = createRestTemplate(connectTimeoutMs, readTimeoutMs);
        log.info("[GatewayWriteClient] Write back timeouts: connect={} ms, read={} ms",
            connectTimeoutMs, readTimeoutMs);
    }

    static RestTemplate createRestTemplate(int connectTimeoutMs, int readTimeoutMs) {
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setConnectionRequestTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }

    public void writeValue(String submodelId, String elementId, String value) {
        if (baseWriteUrl == null) {
            log.warn("[GatewayWriteClient] No write URL configured — skipping write back");
            return;
        }

        // The Gateway's routing key format: machineId.idShort
        // PATCH /api/submodel/{machineId.idShort}/$value
        String routingKey = machineId + "." + elementId;
        String url = baseWriteUrl + "/api/submodel/" + routingKey + "/$value";

        log.info("[GatewayWriteClient] Writing back: {}.{} = {} → PATCH {}",
            submodelId, elementId, value, url);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // Body is the raw value string e.g. "42.5"
            HttpEntity<String> entity = new HttpEntity<>(value, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.PATCH,
                entity,
                String.class);

            log.info("[GatewayWriteClient] Write back successful: {} {}",
                response.getStatusCode(), response.getBody());

        } catch (Exception e) {
            log.error("[GatewayWriteClient] Write back failed: {}", e.getMessage());
        }
    }
}