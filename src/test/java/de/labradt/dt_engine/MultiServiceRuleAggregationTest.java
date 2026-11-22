package de.labradt.dt_engine;

import de.labradt.dt_engine.engine.model.StateAction;
import de.labradt.dt_engine.engine.GatewayWriteClient;
import de.labradt.dt_engine.engine.StateChartExecutor;
import de.labradt.dt_engine.engine.aas.AASManager;
import de.labradt.dt_engine.engine.aas.AASXImporter;
import de.labradt.dt_engine.engine.aas.StateChartLoader;
import de.labradt.dt_engine.engine.model.Rule;
import de.labradt.dt_engine.shared.dto.ExecutionResponseDTO;
import de.labradt.dt_engine.shared.dto.ExecutionStatus;
import org.junit.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;

/**
 * Verifies the fix for a rule that dispatches several services at once (see
 * Rule.getActions()): once the first service to report a terminal result has
 * decided the attempt's outcome, a sibling service's later result must be
 * dropped instead of being acted on a second time.
 *
 * See the attemptConcluded field on StateChartExecutor for the reasoning.
 * This does not test full aggregation across every dispatched service, only
 * that a late sibling result can no longer double apply a write back or
 * trigger a confusing extra retry. Full aggregation is left as future work,
 * since no rule in MachineTool.aasx or Molder.aasx currently dispatches more
 * than one service.
 */
public class MultiServiceRuleAggregationTest {

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        f.set(target, value);
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

    private static void invokePrivate(Object target, String methodName, Class<?>[] paramTypes, Object[] args)
            throws Exception {
        Method m = target.getClass().getDeclaredMethod(methodName, paramTypes);
        m.setAccessible(true);
        m.invoke(target, args);
    }

    /** Counts how many times a correction was written back to the gateway, without making any real HTTP call. */
    private static class CountingGatewayWriteClient extends GatewayWriteClient {
        int callCount = 0;
        String lastSubmodelId;
        String lastElementId;
        String lastValue;

        @Override
        public void writeValue(String submodelId, String elementId, String value) {
            callCount++;
            lastSubmodelId = submodelId;
            lastElementId = elementId;
            lastValue = value;
        }
    }

    private static Rule multiServiceRule() {
        Rule rule = new Rule();
        rule.setAttribute("idShort", "MultiRule");
        rule.setAttribute("targetSubmodel", "CPS_SM");
        rule.setAttribute("targetElement", "Temperature");
        rule.setAttribute("condition", "greater_than");
        rule.setAttribute("threshold", "85.0");
        rule.setAttribute("action", "serviceA,serviceB");
        return rule;
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

    private static class Wired {
        StateChartExecutor executor;
        CountingGatewayWriteClient gatewayWriteClient = new CountingGatewayWriteClient();
        int dispatchCount = 0;
    }

    @SuppressWarnings("unchecked")
    private Wired wire() throws Exception {
        Wired w = new Wired();
        w.executor = new StateChartExecutor();
        setField(w.executor, "aasxImporter", new AASXImporter());
        setField(w.executor, "aasManager", new AASManager());
        setField(w.executor, "stateChartLoader", new StateChartLoader());
        setField(w.executor, "rulesSubmodelEntry",
            "aasx/MachineTool-AAS/MachineTool-AAS.aas.xml");
        setField(w.executor, "aasxPath", "src/main/resources/MachineTool.aasx");
        setField(w.executor, "maxRetries", 3);
        setField(w.executor, "serviceExecutionUrl", "http://mock-service-manager");
        setField(w.executor, "gatewayWriteClient", w.gatewayWriteClient);

        // Loads the real state chart so fireEvent(...) below has
        // transitions to look up, then overrides the runtime state
        // directly to simulate the multi-service rule's dispatch,
        // without needing to drive a real data push through the whole
        // state chart first.
        w.executor.startup();
        assertEquals("STABLE", w.executor.getCurrentState());
        setField(w.executor, "lastPushedElementId", "Temperature");
        setField(w.executor, "currentState", "TRIGGERING");
        setField(w.executor, "inFlightRule", multiServiceRule());

        RestTemplate mock = new RestTemplate() {
            @Override
            public <T> ResponseEntity<T> postForEntity(
                    String url, Object request, Class<T> responseType,
                    Object... uriVariables) {
                w.dispatchCount++;
                String body = "{\"triggered\":["
                    + "{\"executionId\":\"execA\",\"serviceId\":\"serviceA\",\"status\":\"RUNNING\"},"
                    + "{\"executionId\":\"execB\",\"serviceId\":\"serviceB\",\"status\":\"RUNNING\"}"
                    + "],\"message\":\"Queued for execution\"}";
                return (ResponseEntity<T>) ResponseEntity.ok(body);
            }
        };
        setField(w.executor, "restTemplate", mock);

        // Dispatches the multi-service rule as a retry-style call, which
        // reads the rule from inFlightRule instead of actionQueue, so no
        // AASX-loaded state chart or controller needs to be wired for
        // this test.
        invokePrivate(w.executor, "handleServiceCall",
            new Class<?>[]{StateAction.class},
            new Object[]{null});
        assertEquals("dispatch reaches the mock service manager once", 1, w.dispatchCount);
        assertEquals("TRIGGERING", w.executor.getCurrentState());
        return w;
    }

    @Test
    public void siblingSuccessAfterConclusion_doesNotDoubleWriteBack() throws Exception {
        Wired w = wire();

        // serviceA completes first, with a safe correction, and decides
        // the attempt's outcome: the write back happens once and the
        // engine returns to STABLE.
        w.executor.onServiceResult(result("serviceA", "execA", ExecutionStatus.COMPLETED, "80.0"));
        assertEquals(1, w.gatewayWriteClient.callCount);
        assertEquals("STABLE", w.executor.getCurrentState());

        // serviceB, the sibling dispatched by the same rule, now also
        // reports COMPLETED with its own correction. Its executionId is
        // still one that was being tracked, so it is not stale, but the
        // attempt already concluded through serviceA. It must be dropped
        // instead of writing the correction back a second time.
        w.executor.onServiceResult(result("serviceB", "execB", ExecutionStatus.COMPLETED, "81.0"));
        assertEquals("a sibling's late result must not cause a second write back",
            1, w.gatewayWriteClient.callCount);
        assertEquals("STABLE", w.executor.getCurrentState());
    }

    @Test
    public void siblingFailureAfterConclusion_doesNotTriggerExtraRetry() throws Exception {
        Wired w = wire();

        // serviceA completes first and decides the attempt's outcome.
        w.executor.onServiceResult(result("serviceA", "execA", ExecutionStatus.COMPLETED, "80.0"));
        assertEquals(1, w.dispatchCount);
        assertEquals("STABLE", w.executor.getCurrentState());

        // serviceB, the sibling, now reports FAILED. Without the fix this
        // would be read as a fresh failure of the (already concluded)
        // attempt and would re-dispatch both services again.
        w.executor.onServiceResult(result("serviceB", "execB", ExecutionStatus.FAILED, null));
        assertEquals("a sibling's late failure must not trigger a new dispatch",
            1, w.dispatchCount);
        assertEquals("STABLE", w.executor.getCurrentState());
    }

    @Test
    public void successWritesBackToTriggeringRuleTarget_notLatestTelemetryElement() throws Exception {
        Wired w = wire();

        // While the service is running, unrelated telemetry can continue to
        // arrive. It must not redirect the eventual correction to that most
        // recently pushed element.
        setField(w.executor, "lastPushedElementId", "CommandedFeedrate");

        w.executor.onServiceResult(result(
            "serviceA", "execA", ExecutionStatus.COMPLETED, "80.0"));

        assertEquals(1, w.gatewayWriteClient.callCount);
        assertEquals("CPS_SM", w.gatewayWriteClient.lastSubmodelId);
        assertEquals("Temperature", w.gatewayWriteClient.lastElementId);
        assertEquals("80.0", w.gatewayWriteClient.lastValue);
        assertEquals("STABLE", w.executor.getCurrentState());
    }
}
