package de.labradt.dt_engine;

import de.labradt.dt_engine.engine.RuntimeLockedException;
import de.labradt.dt_engine.engine.StateChartExecutor;
import de.labradt.dt_engine.engine.aas.AASManager;
import de.labradt.dt_engine.engine.aas.AASXImporter;
import de.labradt.dt_engine.engine.aas.RepositoryClient;
import de.labradt.dt_engine.engine.aas.StateChartLoader;
import de.labradt.dt_engine.shared.dto.ExecutionResponseDTO;
import de.labradt.dt_engine.shared.dto.ExecutionStatus;
import de.labradt.dt_engine.engine.*;
import de.labradt.dt_engine.engine.aas.*;
import de.labradt.dt_engine.engine.model.Rule;
import de.labradt.dt_engine.shared.dto.*;
import org.junit.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class RuntimeLifecycleTest {
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    static class Fixture {
        StateChartExecutor engine = new StateChartExecutor();
        AASManager aas = new AASManager();
        AtomicInteger calls = new AtomicInteger();
        @SuppressWarnings("unchecked") Fixture() throws Exception {
            set(engine,"aasxImporter",new AASXImporter()); set(engine,"aasManager",aas);
            set(engine,"stateChartLoader",new StateChartLoader());
            set(engine,"rulesSubmodelEntry","aasx/MachineTool-AAS/MachineTool-AAS.aas.xml");
            set(engine,"aasxPath","src/main/resources/MachineTool.aasx");
            set(engine,"serviceExecutionUrl","http://stub");
            set(engine,"restTemplate",new RestTemplate() {
                @Override public <T> ResponseEntity<T> postForEntity(String url,Object body,Class<T> type,Object... vars) {
                    String json = "{\"triggered\":[{\"executionId\":\"exec-" + calls.incrementAndGet() + "\"}]}";
                    return (ResponseEntity<T>)ResponseEntity.ok(json);
                }
            });
            engine.onApplicationReady();
        }
        void push(String value) { engine.onDataPush("CPS_SM","Temperature",value); }
        void callback(String id) {
            ExecutionResponseDTO result = new ExecutionResponseDTO(); result.setExecutionId(id);
            result.setServiceId("cooling-service"); result.setStatus(ExecutionStatus.COMPLETED); result.setOutputData("70");
            engine.onServiceResult(result);
        }
    }
    @Test public void bootIsStoppedButTelemetryContinuesWithoutAutomaticCalls() throws Exception {
        Fixture f = new Fixture(); f.push("95");
        assertEquals("STOPPED",f.engine.runtimeStatus().get("state"));
        assertEquals("95",f.aas.readProperty("CPS_SM","Temperature"));
        assertEquals(0,f.calls.get()); assertFalse((Boolean)f.engine.runtimeStatus().get("configurationLocked"));
    }
    @Test public void snapshotProvidesFreshStoppedTelemetryWithoutDispatch() throws Exception {
        Fixture f = new Fixture(); f.engine.onDataSnapshot("CPS_SM", "Temperature", "95");
        assertEquals("95", f.aas.readProperty("CPS_SM", "Temperature"));
        assertTrue(((Long) f.engine.runtimeStatus().get("telemetryAgeMs")) >= 0); assertEquals(0, f.calls.get());
        f.engine.startRuntime(); assertEquals("RUNNING", f.engine.runtimeStatus().get("state"));
        assertEquals(0, f.calls.get());
    }
    @Test public void reconnectSnapshotNeverDispatchesWhileRunning() throws Exception {
        Fixture f = new Fixture(); f.push("70"); f.engine.startRuntime();
        f.engine.onDataSnapshot("CPS_SM", "Temperature", "95");
        assertEquals("95", f.aas.readProperty("CPS_SM", "Temperature"));
        assertEquals(0, f.calls.get()); assertEquals("STABLE", f.engine.getCurrentState());
        f.push("95"); assertEquals(1, f.calls.get());
    }
    @Test public void startRequiresFreshTelemetryAndKeepsStoppedOnFailure() throws Exception {
        Fixture f = new Fixture();
        try { f.engine.startRuntime(); fail("start without telemetry"); } catch (IllegalArgumentException expected) {}
        assertEquals("STOPPED",f.engine.runtimeStatus().get("state"));
        f.push("70"); set(f.engine,"lastTelemetryAt",System.currentTimeMillis()-16000);
        try { f.engine.startRuntime(); fail("start with stale telemetry"); } catch (IllegalArgumentException expected) {}
        assertEquals("STOPPED",f.engine.runtimeStatus().get("state"));
    }
    @Test public void startStopStartControlsDispatchAndLocksDirectRuleMutation() throws Exception {
        Fixture f = new Fixture(); f.push("95"); f.engine.startRuntime(); f.push("95");
        assertEquals(1,f.calls.get()); assertEquals("TRIGGERING",f.engine.getCurrentState());
        try { f.engine.mutateRuntimeRule("Rule_1",null,false); fail("running edit"); } catch (
                RuntimeLockedException expected) {}
        try { f.engine.acceptAasValue("CPS_SM","Temperature","95"); fail("running manual write"); } catch (RuntimeLockedException expected) {}
        String run = (String)f.engine.runtimeStatus().get("runId");
        f.engine.stopRuntime(); f.push("96"); assertEquals(1,f.calls.get());
        assertEquals(0,f.engine.runtimeStatus().get("inFlightExecutions"));
        f.engine.startRuntime(); assertNotEquals(run,f.engine.runtimeStatus().get("runId"));
        assertEquals("RUNNING",f.engine.runtimeStatus().get("state"));
    }
    @Test public void stoppedAndOldOrUnidentifiedCallbacksCannotChangeState() throws Exception {
        Fixture f = new Fixture(); f.push("95"); f.engine.startRuntime(); f.push("95");
        f.engine.stopRuntime(); f.callback("exec-1"); f.callback(null);
        assertEquals("STABLE",f.engine.getCurrentState());
        f.engine.startRuntime(); f.callback("exec-1"); f.callback(null);
        assertEquals("STABLE",f.engine.getCurrentState()); assertEquals(1,f.calls.get());
    }
    @Test public void repeatedStartAndStopAreIdempotent() throws Exception {
        Fixture f = new Fixture(); f.engine.stopRuntime(); f.push("70"); f.engine.startRuntime();
        String run = (String)f.engine.runtimeStatus().get("runId");
        f.engine.startRuntime(); assertEquals(run,f.engine.runtimeStatus().get("runId"));
        f.engine.stopRuntime(); f.engine.stopRuntime(); assertEquals("STOPPED",f.engine.runtimeStatus().get("state"));
    }
    @Test public void manualTestCannotMasqueradeAsFreshGatewayTelemetry() throws Exception {
        Fixture f = new Fixture();
        assertTrue(f.engine.acceptAasValue("CPS_SM","Temperature","70"));
        assertEquals(-1L,f.engine.runtimeStatus().get("telemetryAgeMs"));
        try { f.engine.startRuntime(); fail("manual write is not live telemetry"); } catch (IllegalArgumentException expected) {}
    }
    @Test public void managedTransportFailureStillReachesErrorAndStopCancelsRecovery() throws Exception {
        Fixture f = new Fixture(); f.push("95"); f.engine.startRuntime();
        set(f.engine,"recoveryTimeoutMs",60000L);
        set(f.engine,"restTemplate",new RestTemplate() {
            @Override public <T> ResponseEntity<T> postForEntity(String url,Object body,Class<T> type,Object... vars) {
                throw new org.springframework.web.client.ResourceAccessException("Service offline");
            }
        });
        f.push("95"); assertEquals("ERROR",f.engine.getCurrentState());
        f.engine.stopRuntime(); assertEquals("STABLE",f.engine.getCurrentState());
        Field task=StateChartExecutor.class.getDeclaredField("recoveryTask");task.setAccessible(true);
        assertNull(task.get(f.engine));f.engine.stopRecoveryScheduler();
    }
    @Test public void repositoryRulesStayFrozenWhileRunningAndRefreshWhenStopped() throws Exception {
        Fixture f = new Fixture();
        List<Rule> original = new ArrayList<>(f.aas.getRules());
        RepositoryClient repository = new RepositoryClient() {
            @Override public boolean enabled() {return true;}
            @Override public List<Rule> readRules() {return List.of();}
            @Override public void mirrorLiveValues(Map<String,String> values) {}
        };
        f.push("70"); f.engine.startRuntime(); set(f.engine,"repository",repository);
        f.engine.synchronizeRepository(); assertEquals(original.size(),f.engine.getRuleCount());
        f.engine.stopRuntime(); f.engine.synchronizeRepository(); assertEquals(0,f.engine.getRuleCount());
    }
    @Test public void commissioningCanRemoveDemoRulesButStartStillRejectsMissingInputs() throws Exception {
        Fixture f = new Fixture();
        Rule first = f.aas.getRules().get(0), unavailable = new Rule();
        first.getAttributes().forEach(unavailable::setAttribute);
        unavailable.setAttribute("idShort", "MissingInputRule"); unavailable.setAttribute("targetElement", "UnmappedAxis");
        class CommissioningRepository extends RepositoryClient {
            List<Rule> stored = new ArrayList<>(List.of(first, unavailable)); int checks;
            @Override public boolean enabled() { return true; }
            @Override public List<Rule> readRules() { return new ArrayList<>(stored); }
            @Override public void saveRules(List<Rule> rules) { stored = new ArrayList<>(rules); }
            @Override public void validateExecutionTargets(List<Rule> rules, String submodel) {
                checks++; throw new IllegalArgumentException("Target property is not present in the live telemetry model");
            }
        }
        CommissioningRepository repository = new CommissioningRepository(); set(f.engine,"repository",repository);
        f.engine.mutateRuntimeRule(first.getIdShort(), null, false);
        assertEquals(1, repository.stored.size()); assertEquals("MissingInputRule", repository.stored.get(0).getIdShort());
        assertEquals(0, repository.checks); assertEquals(1, f.engine.getRuleCount());
        f.push("70");
        try { f.engine.startRuntime(); fail("unmapped rule must block Start"); } catch (IllegalArgumentException expected) {}
        assertEquals(1, repository.checks); assertEquals("STOPPED", f.engine.runtimeStatus().get("state"));
        try { f.engine.mutateRuntimeRule("MissingInputRule", unavailable.getAttributes(), false); fail("editing still validates targets"); }
        catch (IllegalArgumentException expected) {}
        assertEquals(2, repository.checks); assertEquals(1, repository.stored.size());
    }
}
