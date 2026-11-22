package de.labradt.dt_engine;

import de.labradt.dt_engine.engine.model.StateAction;
import de.labradt.dt_engine.engine.StateChartExecutor;
import de.labradt.dt_engine.engine.aas.AASManager;
import de.labradt.dt_engine.engine.aas.AASXImporter;
import de.labradt.dt_engine.engine.aas.StateChartLoader;
import de.labradt.dt_engine.engine.controller.AASController;
import de.labradt.dt_engine.shared.dto.ExecutionResponseDTO;
import de.labradt.dt_engine.shared.dto.ExecutionStatus;
import org.junit.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the executionId correlation fix in
 * StateChartExecutor.onServiceResult(): a result is only applied if its
 * executionId matches the attempt that is currently running, instead of
 * trusting whatever is sitting at actionQueue.peek().
 *
 * Covers the two races described in the correlation bug report.
 *
 * 1. Retry race: a stale response from an attempt before a retry must be
 *    ignored, not applied as if it were the retry's own response.
 *
 * 2. Queue rebuild race: a legitimate response must still be attributed
 *    to the rule that actually triggered it, even if actionQueue has
 *    since been rebuilt (by a concurrent RULE_EVALUATE) so that it no
 *    longer contains that rule.
 */
public class ExecutionIdCorrelationTest {

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> c = type;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** Invokes StateChartExecutor's private handleRuleEvaluate(StateAction),
     *  bypassing the state chart guard, to force an actionQueue rebuild
     *  independent of whatever state the engine is currently in. */
    private static void forceRuleEvaluate(StateChartExecutor executor) throws Exception {
        Method m = StateChartExecutor.class.getDeclaredMethod(
            "handleRuleEvaluate", StateAction.class);
        m.setAccessible(true);
        m.invoke(executor, new Object[]{null});
    }

    /**
     * Wires a fresh executor and controller against the real AASX, with a
     * RestTemplate stub that hands back a distinct executionId per call
     * (exec-1, exec-2 and so on) instead of invoking onServiceResult
     * itself, so the test controls exactly when and with which
     * executionId a result arrives.
     */
    private static class Wired {
        StateChartExecutor executor;
        AASController controller;
        AASManager aasManager;
        AtomicInteger callCount = new AtomicInteger(0);
    }

    @SuppressWarnings("unchecked")
    private Wired wire() throws Exception {
        Wired w = new Wired();
        w.executor = new StateChartExecutor();
        w.aasManager = new AASManager();
        setField(w.executor, "aasxImporter", new AASXImporter());
        setField(w.executor, "aasManager", w.aasManager);
        setField(w.executor, "stateChartLoader", new StateChartLoader());
        setField(w.executor, "rulesSubmodelEntry",
            "aasx/MachineTool-AAS/MachineTool-AAS.aas.xml");
        setField(w.executor, "aasxPath", "src/main/resources/MachineTool.aasx");
        setField(w.executor, "maxRetries", 3);
        setField(w.executor, "serviceExecutionUrl", "http://mock-service-manager");

        RestTemplate mock = new RestTemplate() {
            @Override
            public <T> ResponseEntity<T> postForEntity(
                    String url, Object request, Class<T> responseType,
                    Object... uriVariables) {
                int n = w.callCount.incrementAndGet();
                String body = "{\"triggered\":[{\"executionId\":\"exec-" + n
                    + "\",\"serviceId\":\"cooling-service\",\"status\":\"RUNNING\"}],"
                    + "\"message\":\"Queued for execution\"}";
                return (ResponseEntity<T>) ResponseEntity.ok(body);
            }
        };
        setField(w.executor, "restTemplate", mock);

        w.controller = new AASController();
        setField(w.controller, "executor", w.executor);

        w.controller.startup();
        assertEquals("precondition: engine STABLE after startup",
            "STABLE", w.executor.getCurrentState());
        return w;
    }

    private static ExecutionResponseDTO result(String serviceId, String executionId,
                                                ExecutionStatus status, String outputData) {
        ExecutionResponseDTO r = new ExecutionResponseDTO();
        r.setServiceId(serviceId);
        r.setExecutionId(executionId);
        r.setStatus(status);
        r.setSuccess(status == ExecutionStatus.COMPLETED);
        r.setOutputData(outputData);
        return r;
    }

    @Test
    public void retryRace_staleResponseFromOriginalAttempt_isIgnored() throws Exception {
        Wired w = wire();

        // Fires Rule_1 (Temperature > 85.0), which dispatches attempt 1,
        // expecting exec-1.
        w.aasManager.registerEmptySubmodel("CPS_SM");
        w.aasManager.writeProperty("CPS_SM", "Temperature", "90.0");
        w.controller.onDataPush("CPS_SM", "Temperature", "90.0");
        assertEquals("TRIGGERING", w.executor.getCurrentState());
        assertEquals("attempt 1 dispatched", 1, w.callCount.get());

        // The original attempt (exec-1) fails, which triggers a retry,
        // dispatching attempt 2 and expecting exec-2.
        w.executor.onServiceResult(result("cooling-service", "exec-1", ExecutionStatus.FAILED, null));
        assertEquals("retry dispatched", 2, w.callCount.get());
        assertEquals("still TRIGGERING mid retry", "TRIGGERING", w.executor.getCurrentState());

        // A late, duplicate or stale COMPLETED response from the original
        // (exec-1) attempt now arrives. It must be ignored, not applied as
        // if it were the retry's result.
        w.executor.onServiceResult(result("cooling-service", "exec-1", ExecutionStatus.COMPLETED, "72.0"));
        assertEquals("stale exec-1 response must NOT move the engine out of TRIGGERING",
            "TRIGGERING", w.executor.getCurrentState());

        // The retry's own (exec-2) response is legitimate and must be applied.
        w.executor.onServiceResult(result("cooling-service", "exec-2", ExecutionStatus.COMPLETED, "72.0"));
        assertEquals("STABLE", w.executor.getCurrentState());
    }

    @Test
    public void queueRebuildRace_resultStillAttributedToTriggeringRule() throws Exception {
        Wired w = wire();

        // Fires Rule_1 (Temperature > 85.0, threshold 85.0), which
        // dispatches the attempt and expects exec-1.
        w.aasManager.registerEmptySubmodel("CPS_SM");
        w.aasManager.writeProperty("CPS_SM", "Temperature", "90.0");
        w.controller.onDataPush("CPS_SM", "Temperature", "90.0");
        assertEquals("TRIGGERING", w.executor.getCurrentState());
        assertEquals(1, w.callCount.get());

        Object inFlightRuleBefore = getField(w.executor, "inFlightRule");
        assertTrue("inFlightRule must be captured for the dispatch",
            inFlightRuleBefore != null);

        // Simulate a concurrent RULE_EVALUATE rebuilding actionQueue while
        // the call above is still running. Temperature drops back to a
        // safe value, but a different element now trips a different rule
        // (Rule_3: XAxisLoad > 30.0). After this, actionQueue.peek()
        // would return Rule_3, a different rule than the one that
        // actually triggered the call that is still running.
        w.aasManager.writeProperty("CPS_SM", "Temperature", "10.0");
        w.aasManager.writeProperty("CPS_SM", "XAxisLoad", "35.0");
        forceRuleEvaluate(w.executor);

        // inFlightRule must be unaffected by the rebuild.
        Object inFlightRuleAfter = getField(w.executor, "inFlightRule");
        assertEquals("inFlightRule must survive an actionQueue rebuild unchanged",
            inFlightRuleBefore, inFlightRuleAfter);

        // The result for the call that is still running (exec-1) now
        // arrives. A correction of 72.0 is safe for Rule_1 (the inverse
        // of >85.0 is <=85.0), but would be judged unsafe against Rule_3
        // (the inverse of >30.0 is <=30.0). Ending in STABLE, not ERROR,
        // proves the bounds check used the rule that actually triggered
        // the call, not whatever is now at the head of the queue.
        w.executor.onServiceResult(result("cooling-service", "exec-1", ExecutionStatus.COMPLETED, "72.0"));
        assertEquals("result must be judged against the rule that triggered it",
            "STABLE", w.executor.getCurrentState());
    }
}
