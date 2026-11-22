package de.labradt.dt_engine.engine;

import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.labradt.dt_engine.shared.IDataReceiver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Connects to the external Gateway's SSE stream at startup and forwards
 * every update event into the engine via IDataReceiver.
 * Runs in a background thread, so it does not block startup.
 */
@Slf4j
@Component
public class GatewayStreamClient {
    private final ObjectMapper eventMapper = new ObjectMapper();

    @Value("${gateway.url}")
    private String gatewayUrl;

    @Value("${gateway.submodelId:CPS_SM}")
    private String submodelId;

    /**
     * This instance's own machine id (e.g. "cnc-1", "molder-1"). The Gateway
     * may stream data for multiple machines over the same shared SSE
     * connection, tagging each event's "key" field as "<machineId>.<idShort>"
     * (e.g. "molder-1.ShotCount"). Without checking this prefix, every Engine
     * instance connected to that shared stream would accept every machine's
     * data indiscriminately into its own model. Comparing against this value
     * lets each instance keep only the events that are actually its own.
     */
    @Value("${gateway.machineId:cnc-1}")
    private String machineId;

    @Autowired
    private IDataReceiver dataReceiver;

    /**
     * Starts listening to the external Gateway's stream after Spring is
     * fully started. Runs in a background daemon thread.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startListening() {
        Thread thread = new Thread(() -> {
            while (true) {
                try {
                    log.info("[GatewayStreamClient] Connecting to: {}", gatewayUrl);
                    connect();
                    // A stream that ends without an exception (graceful close)
                    // must also back off, or this loop reconnects hot.
                    log.warn("[GatewayStreamClient] Stream ended — reconnecting in 5s");
                    Thread.sleep(5000);
                } catch (Exception e) {
                    log.error("[GatewayStreamClient] Stream disconnected: {} — reconnecting in 5s",
                        e.getMessage());
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                }
            }
        });
        thread.setDaemon(true);
        thread.setName("gateway-stream");
        thread.start();
        log.info("[GatewayStreamClient] Background stream listener started");
    }

    private void connect() throws Exception {
        URL url = new URL(gatewayUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setRequestProperty("Cache-Control", "no-cache");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(0);
        conn.setInstanceFollowRedirects(true);

        BufferedReader reader = new BufferedReader(
            new InputStreamReader(conn.getInputStream()));

        String eventType = null;
        String line;

        while ((line = reader.readLine()) != null) {
            if (line.startsWith("event:")) {
                eventType = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                String data = line.substring(5).trim();
                handleEvent(eventType, data);
                eventType = null;
            }
        }
    }

    private void handleEvent(String eventType, String data) {
        try {
            // The Gateway's SSE format:
            // {"key": "cnc-1.SpindleSpeed", "property": {"idShort": "SpindleSpeed", "value": "1500.0",
            //   "qualifiers": [{"type": "StatusCode", "value": "OK"}, ...]}}

            // The stream may carry events for multiple machines (e.g. when
            // several Engine instances share one Gateway connection). The
            // "key" field's prefix before the first dot names the machine
            // this event belongs to. Skip anything that isn't ours before
            // it ever reaches the engine.
            JsonNode event = eventMapper.readTree(data);
            JsonNode property = event.path("property");
            String key = event.path("key").asText(null);
            if (key != null) {
                int dot = key.indexOf('.');
                String eventMachineId = (dot > 0) ? key.substring(0, dot) : key;
                if (!machineId.equals(eventMachineId)) {
                    log.trace("[GatewayStreamClient] Event for {} — not this instance ({}), skipping",
                        eventMachineId, machineId);
                    return;
                }
            } else return;

            // Extract idShort and value from inside the property object
            String idShort = property.path("idShort").asText(null);
            JsonNode valueNode = property.path("value");
            String value = valueNode.isValueNode() && !valueNode.isNull() ? valueNode.asText() : null;

            // StatusCode is inside qualifiers, not a flat field.
            // Find the StatusCode qualifier block and read its value.
            String status = null;
            for (JsonNode qualifier : property.path("qualifiers")) {
                if ("StatusCode".equals(qualifier.path("type").asText())) status = qualifier.path("value").asText(null);
            }

            if (status != null && !"OK".equals(status)) {
                log.warn("[GatewayStreamClient] {} value for {} — ignoring", status, idShort);
                return;
            }

            if (idShort == null || value == null) {
                log.warn("[GatewayStreamClient] Could not parse idShort/value from event — skipping");
                return;
            }

            if ("update".equals(eventType)) {
                log.info("[GatewayStreamClient] Update: {}.{} = {}",
                    submodelId, idShort, value);
                dataReceiver.onDataPush(submodelId, idShort, value);
            } else if ("snapshot".equals(eventType) || "keyframe".equals(eventType)) {
                if ("snapshot".equals(eventType)) {
                    log.info("[GatewayStreamClient] Snapshot: {}.{} = {}", submodelId, idShort, value);
                } else {
                    log.debug("[GatewayStreamClient] Keyframe: {}.{} = {}", submodelId, idShort, value);
                }
                // Periodic observations keep unchanged values fresh; neither
                // initial snapshots nor keyframes dispatch business rules.
                dataReceiver.onDataSnapshot(submodelId, idShort, value);
            }

        } catch (Exception e) {
            log.error("[GatewayStreamClient] Failed to parse event: {}", e.getMessage());
        }
    }

}
