package de.labradt.dt_engine;

import com.fasterxml.jackson.databind.*;
import de.labradt.dt_engine.engine.*;
import de.labradt.dt_engine.engine.controller.RulesController;
import de.labradt.dt_engine.engine.model.Rule;
import org.junit.Test;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.lang.reflect.Field;
import java.util.*;
import static org.junit.Assert.*;

public class RuleTestTest {
    @Test public void declaredParametersUseConstantsAndSnapshotInputs() throws Exception {
        var f=new RuntimeLifecycleTest.Fixture();f.push("70");
        f.aas.getRules().get(0).setAttribute("parameters","{\"targetTemperature\":65,\"value\":{\"input\":\"Temperature\"}}");
        var report=f.engine.testRuntimeRule("Rule_1",Map.of("Temperature","95"),false);
        var args=(Map<?,?>)report.get("dynamicArgs");
        assertEquals("65",args.get("targetTemperature"));assertEquals("95",args.get("value"));
        assertEquals("70",f.aas.readProperty("CPS_SM","Temperature"));assertEquals(0,f.calls.get());
    }
    @Test public void malformedAndReservedParametersAreRejected() throws Exception {
        var f=new RuntimeLifecycleTest.Fixture();f.push("70");
        for(String json:List.of("[]","{\"testContent\":\"return 1\"}","{\"value\":null}","{\"value\":{\"input\":\"Temperature\",\"other\":1}}")){
            f.aas.getRules().get(0).setAttribute("parameters",json);
            try{f.engine.testRuntimeRule("Rule_1",Map.of("Temperature","95"),false);fail(json);}catch(IllegalArgumentException expected){}
        }
    }
    private static void set(Object target,String name,Object value) throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    @Test public void dryRunUsesSameOperatorButDoesNotWriteOrDispatch() throws Exception {
        var f=new RuntimeLifecycleTest.Fixture();f.push("70");
        var report=f.engine.testRuntimeRule("Rule_1",Map.of("Temperature","95"),false);
        assertEquals(true,report.get("conditionMet"));assertEquals("dry-run",report.get("mode"));
        assertEquals(false,report.get("writeBack"));assertEquals("70",f.aas.readProperty("CPS_SM","Temperature"));
        assertEquals(0,f.calls.get());assertEquals("STOPPED",f.engine.runtimeStatus().get("state"));
        assertEquals("STABLE",f.engine.getCurrentState());assertEquals(0,f.engine.runtimeStatus().get("inFlightExecutions"));
        assertEquals(false,f.engine.testRuntimeRule("Rule_1",Map.of("Temperature","70"),false).get("conditionMet"));
    }
    @Test public void staleLiveValuesRejectedButFullOverridesDoNotFakeTelemetry() throws Exception {
        var f=new RuntimeLifecycleTest.Fixture();f.push("70");set(f.engine,"lastTelemetryAt",System.currentTimeMillis()-16000);
        try {f.engine.testRuntimeRule("Rule_1",Map.of(),false);fail();} catch(IllegalArgumentException expected) {}
        assertEquals(true,f.engine.testRuntimeRule("Rule_1",Map.of("Temperature","95"),false).get("conditionMet"));
        assertTrue((Long)f.engine.runtimeStatus().get("telemetryAgeMs")>=16000);
    }
    @Test public void invalidUnknownAndNonFiniteInputsAreRejected() throws Exception {
        var f=new RuntimeLifecycleTest.Fixture();f.push("70");
        for (Map<String,String> input:List.of(Map.of("unknown","5"),Map.of("Temperature","bad"),Map.of("Temperature","NaN"),Map.of("Temperature","Infinity"),Map.of("Temperature",""))) {
            try {f.engine.testRuntimeRule("Rule_1",input,false);fail(input.toString());} catch(IllegalArgumentException expected) {}
        }
        try {f.engine.testRuntimeRule("Missing",Map.of(),false);fail();} catch(NoSuchElementException expected) {}
        assertEquals(0,f.calls.get());
    }
    @Test public void conditionFalseSkipsServicesAndDisabledRulesCanBeExplicitlyTested() throws Exception {
        var f=new RuntimeLifecycleTest.Fixture();f.push("70");
        assertEquals("SKIPPED",f.engine.testRuntimeRule("Rule_1",Map.of("Temperature","70"),true).get("status"));
        assertEquals(0,f.calls.get());
        f.aas.getRules().get(0).setAttribute("enabled","false");
        var report=f.engine.testRuntimeRule("Rule_1",Map.of("Temperature","95"),true);
        assertEquals(false,report.get("wouldAutoTrigger"));assertEquals("RUNNING",report.get("status"));assertEquals(1,f.calls.get());
    }
    @Test public void controllerRequiresConfirmationAndRejectsWriteBackOrMalformedOverrides() throws Exception {
        var f=new RuntimeLifecycleTest.Fixture();f.push("70");var controller=new RulesController(f.engine);
        for(Map<String,Object> body:List.<Map<String,Object>>of(Map.of(),Map.of("confirm","true"),Map.of("confirm",true,"writeBack",true),Map.of("confirm",true,"values",List.of(95)),Map.of("confirm",true,"force",true))) {
            try {controller.testTrigger("Rule_1",body);fail();} catch(ResponseStatusException expected) {assertEquals(400,expected.getStatus().value());}
        }
        assertEquals(0,f.calls.get());
        controller.testTrigger("Rule_1",Map.of("confirm",true,"values",Map.of("Temperature",95)));
        assertEquals(1,f.calls.get());
    }
    @Test public void runningRejectsBothTestsIncludingDirectJavaApi() throws Exception {
        var f=new RuntimeLifecycleTest.Fixture();f.push("70");f.engine.startRuntime();
        var controller=new RulesController(f.engine);
        try {controller.dryRun("Rule_1",Map.of());fail();} catch(ResponseStatusException expected) {assertEquals(409,expected.getStatus().value());}
        try {controller.testTrigger("Rule_1",Map.of("confirm",true));fail();} catch(ResponseStatusException expected) {assertEquals(409,expected.getStatus().value());}
        assertEquals(0,f.calls.get());
    }
    @SuppressWarnings("unchecked") private void resultClient(RuntimeLifecycleTest.Fixture f,String output,boolean fail) throws Exception {
        set(f.engine,"restTemplate",new RestTemplate(){
            @Override public <T> T getForObject(String url,Class<T> type,Object... vars){
                try {return (T)new ObjectMapper().readTree("{\"executionId\":\"exec-1\",\"status\":\""+(fail?"FAILED":"COMPLETED")+"\",\"success\":"+!fail+",\"outputData\":\""+output+"\"}");}
                catch(Exception e){throw new RuntimeException(e);}
            }
        });
    }
    @Test public void manualResultIsInspectedWithoutWriteBackAndSurvivesLaterStart() throws Exception {
        var f=new RuntimeLifecycleTest.Fixture();f.push("75");
        var report=f.engine.testRuntimeRule("Rule_1",Map.of("Temperature","95"),true);
        String id=(String)report.get("testId");f.callback("exec-1");assertEquals("75",f.aas.readProperty("CPS_SM","Temperature"));
        f.engine.startRuntime();f.callback("exec-1");assertEquals("75",f.aas.readProperty("CPS_SM","Temperature"));
        resultClient(f,"70",false);var result=f.engine.getRuleTest(id);
        assertEquals("COMPLETED",result.get("status"));assertEquals("75",f.aas.readProperty("CPS_SM","Temperature"));
        assertEquals("STABLE",f.engine.getCurrentState());
        ((Map<String,Object>)result.get("inputs")).put("Temperature","mutated");
        assertNotEquals(result.get("inputs"),f.engine.getRuleTest(id).get("inputs"));
    }
    @Test public void outputBoundsAndServiceFailureAreReportedWithoutRetry() throws Exception {
        for(String output:List.of("95","Infinity","NaN","bad")) {
            var f=new RuntimeLifecycleTest.Fixture();f.push("75");
            String id=(String)f.engine.testRuntimeRule("Rule_1",Map.of("Temperature","95"),true).get("testId");
            resultClient(f,output,false);assertEquals("FAILED",f.engine.getRuleTest(id).get("status"));
            assertEquals(1,f.calls.get());assertEquals("75",f.aas.readProperty("CPS_SM","Temperature"));
        }
        var f=new RuntimeLifecycleTest.Fixture();f.push("75");
        String id=(String)f.engine.testRuntimeRule("Rule_1",Map.of("Temperature","95"),true).get("testId");
        resultClient(f,"70",true);assertEquals("FAILED",f.engine.getRuleTest(id).get("status"));assertEquals(1,f.calls.get());
    }
    @Test public void manualDispatchDoesNotConsumeAutomaticCooldown() throws Exception {
        var f=new RuntimeLifecycleTest.Fixture();f.push("70");f.engine.testRuntimeRule("Rule_1",Map.of("Temperature","95"),true);
        f.engine.startRuntime();f.push("95");assertEquals(2,f.calls.get());
    }
    @Test public void malformedVectorIsRejectedAndMultiInputUsesSnapshot() throws Exception {
        var f=new RuntimeLifecycleTest.Fixture();f.push("70");
        Rule vector=f.aas.getRules().stream().filter(r -> "out_of_bounds".equals(r.getCondition())).findFirst().get();
        f.engine.onDataPush("CPS_SM",vector.getTargetElement(),"0,0,0");
        try {f.engine.testRuntimeRule(vector.getIdShort(),Map.of(vector.getTargetElement(),"1,2"),false);fail();} catch(IllegalArgumentException expected) {}
        assertEquals(false,f.engine.testRuntimeRule(vector.getIdShort(),Map.of(vector.getTargetElement(),"0,0,0"),false).get("conditionMet"));
        Rule wear=new Rule();Map.of("idShort","WearTest","targetSubmodel","CPS_SM","condition","wear_average","threshold","0.1","inputs","XAxisLoad,YAxisLoad,ZAxisLoad","action","cooling-service").forEach(wear::setAttribute);
        f.aas.replaceRules(List.of(wear));
        for(String axis:List.of("XAxisLoad","YAxisLoad","ZAxisLoad")) f.engine.onDataPush("CPS_SM",axis,"10");
        assertEquals(true,f.engine.testRuntimeRule("WearTest",Map.of("XAxisLoad","0","YAxisLoad","100","ZAxisLoad","200"),false).get("conditionMet"));
        assertEquals("10",f.aas.readProperty("CPS_SM","YAxisLoad"));
    }
    @Test public void lostAcknowledgementDoesNotSilentlyRetry() throws Exception {
        var f=new RuntimeLifecycleTest.Fixture();f.push("70");
        set(f.engine,"restTemplate",new RestTemplate(){
            @Override public <T> ResponseEntity<T> postForEntity(String url,Object body,Class<T> type,Object... vars){throw new IllegalStateException("lost connection");}
        });
        var result=f.engine.testRuntimeRule("Rule_1",Map.of("Temperature","95"),true);
        assertEquals("FAILED",result.get("status"));assertTrue(result.get("error").toString().contains("may have started"));
        assertEquals("STABLE",f.engine.getCurrentState());
    }
}
