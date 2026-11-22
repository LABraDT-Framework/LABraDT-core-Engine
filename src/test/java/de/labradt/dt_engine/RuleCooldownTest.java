package de.labradt.dt_engine;

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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;

/** Verifies that cooldownMs from the AASX actually throttles dispatches. */
public class RuleCooldownTest {

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void continuouslyTrueRuleDispatchesAgainOnlyAfterCooldown() throws Exception {
        StateChartExecutor executor = new StateChartExecutor();
        AASManager aasManager = new AASManager();
        AtomicLong now = new AtomicLong(1_000_000L);
        AtomicInteger dispatches = new AtomicInteger();

        setField(executor, "aasxImporter", new AASXImporter());
        setField(executor, "aasManager", aasManager);
        setField(executor, "stateChartLoader", new StateChartLoader());
        setField(executor, "rulesSubmodelEntry",
            "aasx/MachineTool-AAS/MachineTool-AAS.aas.xml");
        setField(executor, "aasxPath", "src/main/resources/MachineTool.aasx");
        setField(executor, "maxRetries", 3);
        setField(executor, "serviceExecutionUrl", "http://mock-service-manager");
        setField(executor, "clock", (java.util.function.LongSupplier) now::get);

        RestTemplate mock = new RestTemplate() {
            @Override
            public <T> ResponseEntity<T> postForEntity(
                    String url, Object request, Class<T> responseType,
                    Object... uriVariables) {
                int attempt = dispatches.incrementAndGet();
                ExecutionResponseDTO completed = new ExecutionResponseDTO();
                completed.setServiceId("cooling-service");
                completed.setStatus(ExecutionStatus.COMPLETED);
                completed.setSuccess(true);
                completed.setOutputData("72.0");
                executor.onServiceResult(completed);
                String body = "{\"triggered\":[{\"executionId\":\"exec-" + attempt
                    + "\",\"serviceId\":\"cooling-service\",\"status\":\"RUNNING\"}]}";
                return (ResponseEntity<T>) ResponseEntity.ok(body);
            }
        };
        setField(executor, "restTemplate", mock);

        AASController controller = new AASController();
        setField(controller, "executor", executor);
        controller.startup();
        aasManager.registerEmptySubmodel("CPS_SM");

        aasManager.getRules().get(0).setAttribute("enabled", "false");
        for (String invalid : new String[]{"NaN", "Infinity", "1e999"}) controller.onDataPush("CPS_SM", "Temperature", invalid);
        controller.onDataPush("CPS_SM", "XAxisPos", "NaN,0,0");
        assertEquals("non-finite scalar/vector values must be quarantined", 4, executor.getRejectedDataCount());
        assertEquals(0, dispatches.get());
        controller.onDataPush("CPS_SM", "Temperature", "90.0");
        assertEquals("disabled rule must not dispatch", 0, dispatches.get());
        assertEquals("STABLE", executor.getCurrentState());
        aasManager.getRules().get(0).setAttribute("enabled", "true");

        controller.onDataPush("CPS_SM", "Temperature", "90.0");
        assertEquals(1, dispatches.get());
        assertEquals("STABLE", executor.getCurrentState());

        now.addAndGet(29_999L);
        controller.onDataPush("CPS_SM", "Temperature", "91.0");
        assertEquals("the rule must be suppressed inside its 30 s cooldown",
            1, dispatches.get());
        assertEquals("STABLE", executor.getCurrentState());

        now.incrementAndGet();
        controller.onDataPush("CPS_SM", "Temperature", "92.0");
        assertEquals("the rule may dispatch again when cooldown expires",
            2, dispatches.get());
        assertEquals("STABLE", executor.getCurrentState());
    }
}
