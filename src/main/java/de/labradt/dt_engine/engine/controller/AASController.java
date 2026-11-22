package de.labradt.dt_engine.engine.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import de.labradt.dt_engine.engine.StateChartExecutor;
import de.labradt.dt_engine.engine.aas.AASManager;
import de.labradt.dt_engine.shared.FileStorageService;
import de.labradt.dt_engine.shared.IDataReceiver;
import de.labradt.dt_engine.shared.IEngineStatusListener;
import de.labradt.dt_engine.shared.dto.ExecutionResponseDTO;
import de.labradt.dt_engine.shared.dto.ExecutionStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import de.labradt.dt_engine.shared.db.DatabaseStorageService;
import de.labradt.dt_engine.shared.db.EngineStateEntity;
import de.labradt.dt_engine.shared.db.SubmodelDataEntity;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;

/**
 * AASController is the entry point of the engine.
 *
 * It receives data pushes from the external Gateway through IDataReceiver and
 * service results from the external Runtime component through
 * IEngineStatusListener. It fires the appropriate events into the
 * StateChartExecutor.
 *
 * It also exposes REST endpoints for manual testing and demo purposes.
 */
@Slf4j
@RestController
@RequestMapping("/engine")
public class AASController implements IDataReceiver, IEngineStatusListener {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private StateChartExecutor executor;

    @Autowired
    private AASManager aasManager;

    @Autowired(required = false)
    private DatabaseStorageService dbStorageService;

    @Autowired
    private FileStorageService storageService;

    // ─────────────────────────────────────────
    // REST ENDPOINTS (manual triggering)
    // ─────────────────────────────────────────

    /**
     * Start the engine.
     * GET http://localhost:8080/engine/startup
     */
    @GetMapping("/startup")
    public ResponseEntity<String> startup() {
        executor.requireStopped();
        log.info("[AASController] REST: startup called");
        executor.startup();
        return ResponseEntity.ok("Engine started. Current state: "
            + executor.getCurrentState());
    }

    /**
     * Receives a data push as a JSON body.
     * POST http://localhost:8080/engine/push
     * Body: { "submodelId": "CPS_SM", "elementId": "Temperature", "value": "80.0" }
     */
    @PostMapping("/push")
    public ResponseEntity<String> pushJson(
            @RequestBody Map<String, String> body) {
        String submodelId = body.get("submodelId");
        String elementId  = body.get("elementId");
        String value      = body.get("value");

        log.info("[AASController] POST data push: {}:{} = {}",
            submodelId, elementId, value);
        onDataPush(submodelId, elementId, value);

        return ResponseEntity.ok("Data push processed. Current state: "
            + executor.getCurrentState());
    }

    /**
     * Simulate a shutdown.
     * GET http://localhost:8080/engine/shutdown
     */
    @GetMapping("/shutdown")
    public ResponseEntity<String> shutdown() {
        log.info("[AASController] REST: shutdown called");
        executor.shutdown();
        return ResponseEntity.ok("Engine shutting down. Current state: "
            + executor.getCurrentState());
    }

    /**
     * Get the current state, loaded rule count and rejection counter.
     * GET http://localhost:8080/engine/state
     */
    @GetMapping("/state")
    public ResponseEntity<String> state() {
        return ResponseEntity.ok("Current state: " + executor.getCurrentState()
            + " | Rules loaded: " + executor.getRuleCount()
            + " | Rejected pushes: " + executor.getRejectedDataCount());
    }

    /**
     * Reads a scalar value from the engine's live AAS model. This endpoint is
     * used by the repository compatibility service on port 8081, keeping UI
     * reads and rule evaluation on the same in-memory model.
     */
    @GetMapping("/aas/value")
    public ResponseEntity<JsonNode> readAasValue(
            @RequestParam String submodelId,
            @RequestParam String idShortPath) {
        String value = aasManager.readProperty(submodelId, idShortPath);
        if (value == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toJsonPrimitive(value));
    }

    /**
     * Writes a scalar through the normal data-push path so persistence,
     * validation and rule evaluation are identical for UI and Gateway writes.
     */
    @PatchMapping("/aas/value")
    public ResponseEntity<Void> writeAasValue(
            @RequestParam String submodelId,
            @RequestParam String idShortPath,
            @RequestBody JsonNode value) {
        executor.requireStopped();
        if (value == null || value.isNull() || value.isContainerNode()) {
            return ResponseEntity.badRequest().build();
        }
        if (aasManager.readProperty(submodelId, idShortPath) == null) return ResponseEntity.notFound().build();
        if (!executor.acceptAasValue(submodelId, idShortPath,
            value.isTextual() ? value.textValue() : value.asText())) return ResponseEntity.unprocessableEntity().build();
        return ResponseEntity.noContent().build();
    }

    // ─────────────────────────────────────────
    // IDataReceiver (called by the external Gateway)
    // ─────────────────────────────────────────

    /**
     * Store an initial/reconnect snapshot without firing a business event.
     */
    @Override
    public void onDataSnapshot(String submodelId, String elementId, String value) {
        executor.onDataSnapshot(submodelId, elementId, value);
    }

    @Override
    public void onDataPush(String submodelId, String elementId, String value) {
        log.info("[AASController] Data push received: {}:{} = {}",
            submodelId, elementId, value);
        executor.onDataPush(submodelId, elementId, value);
    }

    /**
     * Called when the Gateway detects an emergency condition.
     * Fires DATA_PUSH_RECEIVED with priority into the state machine.
     */
    @Override
    public void onEmergencyPush(String submodelId, String elementId, String value) {
        log.warn("[AASController] EMERGENCY push: {}:{} = {}",
            submodelId, elementId, value);
        executor.onDataPush(submodelId, elementId, value);
    }

    // ─────────────────────────────────────────
    // IEngineStatusListener (called by the external Runtime component)
    // ─────────────────────────────────────────

    /**
     * Called by the external Service Manager when a service completes.
     * Wraps the individual fields into an ExecutionResponseDTO and passes
     * it into the executor, which checks the executionId before acting on
     * it (see StateChartExecutor.onServiceResult()).
     */
    @Override
    public void UpdateSystemState(String serviceId,
                                   String executionId,
                                   ExecutionStatus status,
                                   String info) {
        log.info("[AASController] Service result: {} executionId={} status={} info={}",
            serviceId, executionId, status, info);

        ExecutionResponseDTO response = new ExecutionResponseDTO();
        response.setServiceId(serviceId);
        response.setExecutionId(executionId);
        response.setStatus(status);
        response.setSuccess(status == ExecutionStatus.COMPLETED);
        response.setOutputData(info);

        executor.onServiceResult(response);
    }

    /**
     * Returns the 10 most recent state transitions from the database.
     * GET http://localhost:8080/engine/history
     */
    @GetMapping("/history")
    public ResponseEntity<String> history() {
        if (dbStorageService == null) {
            return ResponseEntity.ok("Database not available");
        }

        List<EngineStateEntity> recent =
            dbStorageService.getRecentTransitions();

        StringBuilder sb = new StringBuilder();
        sb.append("Recent state transitions:\n");
        for (EngineStateEntity e : recent) {
            sb.append(String.format("  [%s] %s --%s--> %s%n",
                e.getTimestamp(), e.getFromState(),
                e.getTrigger(), e.getToState()));
        }
        return ResponseEntity.ok(sb.toString());
    }

    /**
     * Receives a SubmodelElement from the external Gateway.
     * POST http://localhost:8080/engine/push/{submodelId}
     * Body: BaSyx SubmodelElement JSON
     */
    @PostMapping("/push/{submodelId}")
    public ResponseEntity<String> pushSubmodelElement(
            @PathVariable String submodelId,
            @RequestBody java.util.Map<String, Object> element) {

        String elementId = (String) element.get("idShort");
        String value     = String.valueOf(element.get("value"));

        if (elementId == null || value == null || value.equals("null")) {
            log.warn("[AASController] Invalid SubmodelElement received: {}", element);
            return ResponseEntity.badRequest()
                .body("Missing idShort or value in SubmodelElement");
        }

        log.info("[AASController] SubmodelElement push: {}.{} = {}",
            submodelId, elementId, value);

        onDataPush(submodelId, elementId, value);

        return ResponseEntity.ok("SubmodelElement push processed. Current state: "
            + executor.getCurrentState());
    }

    /**
     * Called by the external Service Manager over HTTP when a service completes.
     * POST http://localhost:8080/engine/result
     * Body: ExecutionResponseDTO JSON
     */
    @PostMapping("/result")
    public ResponseEntity<String> serviceResult(
            @RequestBody ExecutionResponseDTO response) {
        log.info("[AASController] Service result received via HTTP: {} executionId={} status={}",
            response.getServiceId(), response.getExecutionId(), response.getStatus());
        UpdateSystemState(
            response.getServiceId(),
            response.getExecutionId(),
            response.getStatus(),
            response.getOutputData());
        return ResponseEntity.ok("Result received. Current state: "
            + executor.getCurrentState());
    }

    @GetMapping("/submodel-data")
    public ResponseEntity<String> submodelData() {
        if (dbStorageService == null)
            return ResponseEntity.ok("Database not available");
        List<SubmodelDataEntity> rows = dbStorageService.getRecentDataWrites();
        StringBuilder sb = new StringBuilder("Submodel data:\n");
        for (SubmodelDataEntity e : rows) {
            sb.append(String.format("  [%s] %s.%s = %s%n",
                e.getTimestamp(), e.getSubmodelId(),
                e.getElementId(), e.getValue()));
        }
        return ResponseEntity.ok(sb.toString());
    }

    /**
     * Lists the top level contents of the storage folder.
     * GET http://localhost:8080/engine/storage
     */
    @GetMapping("/storage")
    public ResponseEntity<String> storageList() {
        java.io.File dir = new java.io.File("storage");
        if (!dir.exists() || !dir.isDirectory())
            return ResponseEntity.ok("storage/ folder not found");
        StringBuilder sb = new StringBuilder();
        for (java.io.File f : dir.listFiles()) {
            sb.append(String.format("%s | %d bytes%n", f.getName(), f.length()));
        }
        return ResponseEntity.ok(sb.toString());
    }

    /**
     * Retrieves a stored value by key from the file storage.
     * Used by the external Runtime component to fetch Dockerfiles before running a service.
     *
     * GET http://localhost:8080/engine/storage/services/cooling-service/Dockerfile
     * GET http://localhost:8080/engine/storage/CPS_SM/Temperature
     */
    @GetMapping("/storage/**")
    public ResponseEntity<String> getStorageValue(HttpServletRequest request) {
        String fullPath = request.getRequestURI()
            .replaceFirst("/engine/storage/", "");
        log.info("[AASController] Storage fetch requested for key: {}", fullPath);
        String value = storageService.retrieve(fullPath);
        if (value == null) {
            log.warn("[AASController] Storage key not found: {}", fullPath);
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(value);
    }

    /**
     * Stores a service artifact (script, Dockerfile) in the engine file storage.
     * Returns the storage key to be used as fileReference when registering a service.
     *
     * POST http://localhost:8080/engine/storage/upload
     *   Content-Type: multipart/form-data
     *   file: the file
     *   key:  services/coolingService/script   (optional, generated automatically if omitted)
     *
     * Response: { "fileReference": "services/coolingService/script" }
     */
    @PostMapping(value = "/storage/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadToStorage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "key", required = false) String key) {
        executor.requireStopped();
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Uploaded file is empty"));
        }
        try {
            String storageKey = (key != null && !key.isBlank())
                    ? key
                    : "services/" + UUID.randomUUID() + "-" + stripExtension(file.getOriginalFilename());
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            storageService.store(storageKey, content);
            log.info("[AASController] Stored service artifact: {} key={}", file.getOriginalFilename(), storageKey);
            return ResponseEntity.ok(Map.of("fileReference", storageKey));
        } catch (Exception e) {
            log.error("[AASController] Failed to store artifact: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "STORAGE_ERROR", "message", e.getMessage()));
        }
    }

    /**
     * Deletes a stored artifact by key.
     *
     * DELETE http://localhost:8080/engine/storage/services/cooling-service/script
     */
    @DeleteMapping("/storage/**")
    public ResponseEntity<Void> deleteStorageValue(HttpServletRequest request) {
        executor.requireStopped();
        String key = request.getRequestURI().replaceFirst("/engine/storage/", "");
        log.info("[AASController] Storage delete requested for key: {}", key);
        if (!storageService.exists(key)) {
            log.warn("[AASController] Storage key not found for delete: {}", key);
            return ResponseEntity.notFound().build();
        }
        storageService.delete(key);
        return ResponseEntity.noContent().build();
    }

    private static String stripExtension(String filename) {
        if (filename == null) return "file";
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private static JsonNode toJsonPrimitive(String value) {
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(value);
            if (parsed != null && !parsed.isContainerNode() && !parsed.isNull()) {
                return parsed;
            }
        } catch (Exception ignored) {
            // Ordinary string values are not necessarily JSON literals.
        }
        return OBJECT_MAPPER.getNodeFactory().textNode(value);
    }
}
