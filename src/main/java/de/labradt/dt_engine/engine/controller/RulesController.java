package de.labradt.dt_engine.engine.controller;

import de.labradt.dt_engine.engine.StateChartExecutor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/engine")
public class RulesController {
    private final StateChartExecutor executor;
    public RulesController(StateChartExecutor executor) { this.executor = executor; }
    @GetMapping("/health") public ResponseEntity<Map<String,Object>> health() {
        boolean ready = !Set.of("ERROR", "INITIALIZING").contains(Objects.toString(executor.getCurrentState(), "INITIALIZING")) && executor.isRepositoryReady() && executor.isTelemetryReady();
        return ResponseEntity.status(ready ? 200 : 503).body(Map.of("status", ready ? "UP" : "DOWN", "state", Objects.toString(executor.getCurrentState(), "STARTING"), "repositoryError", executor.getRepositoryError()));
    }
    @GetMapping("/rules") public List<Map<String,String>> list() { return executor.getRuntimeRules(); }
    @PostMapping("/rules") public ResponseEntity<Void> create(@RequestBody Map<String,String> rule) {
        mutate(rule.get("idShort"), rule, true); return ResponseEntity.status(201).build();
    }
    @PutMapping("/rules/{id}") public ResponseEntity<Void> update(@PathVariable String id, @RequestBody Map<String,String> rule) {
        if (!id.equals(rule.get("idShort"))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rule identity cannot be changed; create a new rule");
        mutate(id, rule, false); return ResponseEntity.noContent().build();
    }
    @DeleteMapping("/rules/{id}") public ResponseEntity<Void> delete(@PathVariable String id) {
        mutate(id, null, false); return ResponseEntity.noContent().build();
    }
    @PostMapping("/rules/{id}/dry-run") public Map<String,Object> dryRun(@PathVariable String id, @RequestBody(required=false) Map<String,Object> body) {
        return test(id, body, false);
    }
    @PostMapping("/rules/{id}/test-trigger") public Map<String,Object> testTrigger(@PathVariable String id, @RequestBody Map<String,Object> body) {
        return test(id, body, true);
    }
    @GetMapping("/rule-tests/{testId}") public Map<String,Object> testResult(@PathVariable String testId) {
        try {return executor.getRuleTest(testId);}
        catch (NoSuchElementException error) {throw new ResponseStatusException(HttpStatus.NOT_FOUND, error.getMessage());}
    }
    private Map<String,Object> test(String id, Map<String,Object> body, boolean trigger) {
        try {
            executor.requireStopped();
            body = body == null ? Map.of() : body;
            if (!Set.of("values", "confirm", "writeBack").containsAll(body.keySet())) throw new IllegalArgumentException("Unknown test option");
            if (body.containsKey("writeBack") && !Boolean.FALSE.equals(body.get("writeBack")))
                throw new IllegalArgumentException("Rule tests do not support write-back");
            if (trigger && !Boolean.TRUE.equals(body.get("confirm")))
                throw new IllegalArgumentException("Test Trigger requires confirm=true; real Service side effects are possible");
            Map<String,String> values = new LinkedHashMap<>();
            if (body.containsKey("values")) {
                if (!(body.get("values") instanceof Map<?,?> inputs)) throw new IllegalArgumentException("values must be an object of primitive input overrides");
                inputs.forEach((name,value) -> {
                    if (!(name instanceof String) || !(value instanceof String || value instanceof Number || value instanceof Boolean))
                        throw new IllegalArgumentException("Test input values must be non-null JSON primitives");
                    values.put((String)name, value.toString());
                });
            }
            return executor.testRuntimeRule(id, values, trigger);
        } catch (NoSuchElementException error) {throw new ResponseStatusException(HttpStatus.NOT_FOUND, error.getMessage());}
        catch (IllegalArgumentException error) {throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());}
        catch (IllegalStateException error) {throw new ResponseStatusException(HttpStatus.CONFLICT, error.getMessage());}
        catch (Exception error) {throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Rule test validation failed: " + error.getMessage());}
    }
    private void mutate(String id, Map<String,String> rule, boolean create) {
        try { executor.mutateRuntimeRule(id, rule, create); }
        catch (NoSuchElementException error) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, error.getMessage()); }
        catch (IllegalArgumentException error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage()); }
        catch (IllegalStateException error) { throw new ResponseStatusException(HttpStatus.CONFLICT, error.getMessage()); }
        catch (Exception error) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Repository update failed: " + error.getMessage()); }
    }
}
