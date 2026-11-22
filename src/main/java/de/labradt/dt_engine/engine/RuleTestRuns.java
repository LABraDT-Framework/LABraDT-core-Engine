package de.labradt.dt_engine.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.labradt.dt_engine.engine.model.Rule;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiPredicate;

/** Bounded, process-local manual-test records. No Gateway/Repository writes or retries. */
final class RuleTestRuns {
    private final ObjectMapper json = new ObjectMapper();
    private final LinkedHashMap<String,Run> runs = new LinkedHashMap<>();
    private static class Run {
        final Rule rule;
        final Map<String,Object> report;
        final List<Map<String,Object>> executions = new ArrayList<>();
        Run(Rule rule, Map<String,Object> report) {this.rule=rule; this.report=report;}
    }
    synchronized Map<String,Object> submit(Map<String,Object> plan, Rule rule, RestTemplate http, String url) {
        if (rule.getActions().size() > 4) throw new IllegalArgumentException("A manual test supports at most four Service actions");
        for (Run old : runs.values()) if ("RUNNING".equals(old.report.get("status"))
            && System.currentTimeMillis() - ((Number)old.report.get("evaluatedAt")).longValue() > 60000) {
            old.report.put("status", "TIMED_OUT");
            old.report.put("error", "Result wait exceeded 60 seconds. Remote jobs are not cancelled; inspect Service history.");
        }
        if (runs.values().stream().filter(r -> "RUNNING".equals(r.report.get("status"))).count() >= 8)
            throw new IllegalStateException("Too many pending tests; refresh results before submitting another test");
        // Never evict a pending test to make room: its result must remain inspectable.
        while (runs.size() >= 64) {
            String old = runs.entrySet().stream().filter(e -> !"RUNNING".equals(e.getValue().report.get("status")))
                .map(Map.Entry::getKey).findFirst().orElseThrow(() -> new IllegalStateException("Test history is full"));
            runs.remove(old);
        }
        String id = UUID.randomUUID().toString();
        Run run = new Run(rule, new LinkedHashMap<>(plan));
        run.report.put("testId", id); run.report.put("status", "SKIPPED"); run.report.put("executions", run.executions);
        runs.put(id, run);
        if (!Boolean.TRUE.equals(plan.get("conditionMet"))) return snapshot(run);
        run.report.put("status", "RUNNING");
        try {
            HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<String> accepted = http.postForEntity(url, new HttpEntity<>(Map.of(
                "serviceIds", rule.getActions(), "dynamicArgs", plan.get("dynamicArgs")), headers), String.class);
            JsonNode entries = json.readTree(accepted.getBody()).path("triggered");
            Set<String> ids = new HashSet<>();
            for (JsonNode item : entries) {
                String executionId = item.path("executionId").asText("");
                if (executionId.isBlank() || !ids.add(executionId)) continue;
                Map<String,Object> execution = new LinkedHashMap<>();
                execution.put("executionId", executionId); execution.put("serviceId", item.path("serviceId").asText("unknown"));
                execution.put("status", "RUNNING"); run.executions.add(execution);
            }
            if (run.executions.size() != rule.getActions().size()) {
                run.report.put("status", "FAILED");
                run.report.put("error", "Incomplete execution acknowledgement. Remote jobs may have started; inspect Service history. Do not blindly retry.");
            }
        } catch (Exception error) {
            run.report.put("status", "FAILED");
            run.report.put("error", "Dispatch failed or acknowledgement was lost: " + error.getMessage()
                + ". Remote jobs may have started; inspect Service history before retrying.");
        }
        return snapshot(run);
    }
    synchronized Map<String,Object> read(String id, RestTemplate http, String url, BiPredicate<Rule,String> bounds) {
        Run run = runs.get(id);
        if (run == null) throw new NoSuchElementException("Test not found; records are process-local and limited to 64 tests");
        if (!"RUNNING".equals(run.report.get("status"))) return snapshot(run);
        String base = url.replaceFirst("/services/trigger$", "/services/result?executionId=");
        boolean pending = false, failed = false;
        for (Map<String,Object> execution : run.executions) {
            if (!"RUNNING".equals(execution.get("status"))) {
                failed |= !Boolean.TRUE.equals(execution.get("valid")); continue;
            }
            try {
                JsonNode result = http.getForObject(base + URLEncoder.encode((String)execution.get("executionId"), StandardCharsets.UTF_8), JsonNode.class);
                if (result == null || !execution.get("executionId").equals(result.path("executionId").asText()))
                    throw new IllegalStateException("Result execution ID mismatch");
                String status = result.path("status").asText();
                if (Set.of("ACCEPTED", "RUNNING").contains(status)) {pending=true; continue;}
                execution.put("status", status); execution.put("result", result);
                boolean complete = "COMPLETED".equals(status) && result.path("success").asBoolean(false);
                JsonNode output = result.path("outputData");
                String value = output.isValueNode() && !output.isNull() ? output.asText() : null;
                boolean target = run.rule.getTargetElement() != null && !run.rule.getTargetElement().isBlank();
                boolean valid = complete && value != null && !value.isBlank() && bounds.test(run.rule, value);
                execution.put("valid", complete && (!target || valid));
                execution.put("outputValidation", !target ? "No explicit correction target; no write-back is performed"
                    : valid ? "Valid under existing runtime correction bounds; NOT written back"
                    : "Invalid or missing correction under existing runtime bounds; NOT written back");
                failed |= !complete || target && !valid;
                execution.remove("pollError");
            } catch (HttpClientErrorException.NotFound waiting) {pending=true;}
            catch (Exception error) {execution.put("pollError", error.getMessage()); pending=true;}
        }
        if (pending && System.currentTimeMillis() - ((Number)run.report.get("evaluatedAt")).longValue() > 60000) {
            run.report.put("status", "TIMED_OUT");
            run.report.put("error", "Result wait exceeded 60 seconds. Remote jobs are not cancelled; inspect Service history.");
        } else run.report.put("status", pending ? "RUNNING" : failed ? "FAILED" : "COMPLETED");
        return snapshot(run);
    }
    private Map<String,Object> snapshot(Run run) {
        return json.convertValue(run.report, new TypeReference<Map<String,Object>>() {});
    }
}
