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

import static org.junit.Assert.assertEquals;

/**
 * End-to-end tests for the write-back safety check, driven through the real
 * onServiceResult path rather than the pure helper.
 *
 * Updated to match the direct-HTTP execution architecture by eliminating ServiceProxy
 * and utilizing an embedded RestTemplate stub.
 */
public class WriteBackValidationTest {

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /**
     * Replaces the executor's RestTemplate with a mock that simulates the
     * external service manager: returns HTTP 200 and synchronously fires
     * onServiceResult with the given serviceId and outputData.
     */
    @SuppressWarnings("unchecked")
    private static void injectMockRestTemplate(StateChartExecutor executor,
                                               String serviceId,
                                               String outputData) throws Exception {
        RestTemplate mock = new RestTemplate() {
            @Override
            public <T> ResponseEntity<T> postForEntity(
                    String url, Object request, Class<T> responseType,
                    Object... uriVariables) {
                ExecutionResponseDTO r = new ExecutionResponseDTO();
                r.setServiceId(serviceId);
                r.setStatus(ExecutionStatus.COMPLETED);
                r.setSuccess(true);
                r.setOutputData(outputData);
                executor.onServiceResult(r);
                return (ResponseEntity<T>) ResponseEntity.ok("accepted");
            }
        };
        setField(executor, "restTemplate", mock);
    }

    /**
     * Wires an executor, intercepts its outgoing RestTemplate HTTP requests with the
     * specified correction payload, pushes an over-threshold value, and returns
     * the resulting engine state.
     */
    private String runCycleWithCorrection(String correction) throws Exception {
        AASXImporter importer = new AASXImporter();
        AASManager aasManager = new AASManager();
        StateChartLoader loader = new StateChartLoader();

        StateChartExecutor executor = new StateChartExecutor();
        setField(executor, "aasxImporter", importer);
        setField(executor, "aasManager", aasManager);
        setField(executor, "stateChartLoader", loader);
        setField(executor, "rulesSubmodelEntry",
            "aasx/MachineTool-AAS/MachineTool-AAS.aas.xml");
        setField(executor, "aasxPath", "src/main/resources/MachineTool.aasx");
        setField(executor, "maxRetries", 3);
        setField(executor, "serviceExecutionUrl", "http://mock-service-manager");

        // Inject the simulated RestTemplate instead of relying on an old ServiceProxy block
        injectMockRestTemplate(executor, "cooling-service", correction);

        AASController controller = new AASController();
        setField(controller, "executor", executor);

        controller.startup();
        assertEquals("precondition: engine STABLE after startup",
            "STABLE", executor.getCurrentState());

        // Push a value that fires the numeric greater_than rule (Rule_1: Temperature > 85.0).
        aasManager.registerEmptySubmodel("CPS_SM");
        aasManager.writeProperty("CPS_SM", "Temperature", "90.0");
        controller.onDataPush("CPS_SM", "Temperature", "90.0");

        return executor.getCurrentState();
    }

    @Test
    public void safeCorrection_isWrittenBack_engineReturnsToStable() throws Exception {
        // 72.0 satisfies the inverse of greater_than 85.0 (72 <= 85) → safe.
        String state = runCycleWithCorrection("72.0");
        assertEquals("a safe correction should be written back and end STABLE",
            "STABLE", state);
    }

    @Test
    public void unsafeCorrection_isRejected_engineDivertsToError() throws Exception {
        // 200.0 still exceeds 85.0 → must NOT be written back; diverts to ERROR.
        String state = runCycleWithCorrection("200.0");
        assertEquals("an unsafe correction should be rejected and divert to ERROR",
            "ERROR", state);
    }

    @Test
    public void nonNumericCorrection_isRejected_engineDivertsToError() throws Exception {
        // A non-numeric correction is unsafe for a numeric rule and must divert to ERROR.
        String state = runCycleWithCorrection("done");
        assertEquals("a non-numeric correction should be rejected and divert to ERROR",
            "ERROR", state);
    }
}
