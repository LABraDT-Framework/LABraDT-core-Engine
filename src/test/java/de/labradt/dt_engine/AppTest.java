package de.labradt.dt_engine;

import de.labradt.dt_engine.engine.aas.AASManager;
import de.labradt.dt_engine.engine.aas.AASXImporter;
import de.labradt.dt_engine.engine.aas.StateChartLoader;
import de.labradt.dt_engine.engine.model.Rule;
import de.labradt.dt_engine.engine.model.StateChartDefinition;
import de.labradt.dt_engine.engine.model.StateDefinition;
import de.labradt.dt_engine.engine.model.TransitionDefinition;
import de.labradt.dt_engine.engine.StateChartExecutor;
import de.labradt.dt_engine.engine.controller.AASController;
import de.labradt.dt_engine.shared.dto.ExecutionResponseDTO;
import de.labradt.dt_engine.shared.dto.ExecutionStatus;
import org.junit.Before;
import org.junit.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class AppTest {

    private AASXImporter importer;
    private AASManager manager;

    @Before
    public void setUp() {
        importer = new AASXImporter();
        manager = new AASManager();
    }

    @Test
    public void loads_rules_from_aasx() throws Exception {
        InputStream xmlStream = getClass()
            .getClassLoader()
            .getResourceAsStream("MachineTool.aasx");

        assertNotNull("MachineTool.aasx not found in resources", xmlStream);

        InputStream rulesXml = importer.extractFromStream(
            xmlStream,
            "aasx/MachineTool-AAS/MachineTool-AAS.aas.xml"
        );

        assertNotNull("Rules XML entry not found in zip", rulesXml);

        // aasxBytes is null here: this test only exercises rule parsing,
        // not the hash marker.
        manager.loadRules(rulesXml, null);

        List<Rule> rules = manager.getRules();

        assertEquals("Expected 3 rules", 3, rules.size());

        // Values reflect the active CNC milling AASX.
        Rule rule1 = rules.get(0);
        assertEquals("Rule_1", rule1.getIdShort());
        assertEquals("CPS_SM", rule1.getTargetSubmodel());
        assertEquals("Temperature", rule1.getTargetElement());
        assertEquals("greater_than", rule1.getCondition());
        assertEquals("85.0", rule1.getThreshold());
        assertEquals("cooling-service", rule1.getAction());
        assertEquals(30000L, rule1.getCooldownMs());

        Rule rule2 = rules.get(1);
        assertEquals("Rule_2", rule2.getIdShort());
        assertEquals("CPS_SM", rule2.getTargetSubmodel());
        assertEquals("XAxisPos", rule2.getTargetElement());
        assertEquals("out_of_bounds", rule2.getCondition());
        assertEquals("-0.45,0.45,-0.45,0.45,-0.45,0.45", rule2.getThreshold());
        assertEquals("geometry-correction-service", rule2.getAction());
        assertEquals(15000L, rule2.getCooldownMs());

        Rule rule3 = rules.get(2);
        assertEquals("Rule_3", rule3.getIdShort());
        assertEquals("CPS_SM", rule3.getTargetSubmodel());
        assertEquals("XAxisLoad", rule3.getTargetElement());
        assertEquals("greater_than", rule3.getCondition());
        assertEquals("30.0", rule3.getThreshold());
        assertEquals("energy-monitoring-service", rule3.getAction());
        assertEquals(30000L, rule3.getCooldownMs());

        System.out.println("Rules loaded successfully:");
        rules.forEach(r -> System.out.println(
            "  " + r.getIdShort() + " -> " + r.getCondition()
            + " on " + r.getTargetElement()
            + " -> " + r.getAction()
        ));
    }

    @Test
    public void loads_statechart_from_aasx() throws Exception {
        InputStream xmlStream = getClass()
            .getClassLoader()
            .getResourceAsStream("MachineTool.aasx");

        assertNotNull("MachineTool.aasx not found", xmlStream);

        InputStream stateChartXml = importer.extractFromStream(
            xmlStream,
            "aasx/MachineTool-AAS/MachineTool-AAS.aas.xml"
        );

        assertNotNull("StateChart XML not found", stateChartXml);

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document doc = factory.newDocumentBuilder().parse(stateChartXml);

        NodeList submodels = doc.getElementsByTagNameNS(
            "https://admin-shell.io/aas/3/0", "submodel");

        boolean foundStateChart = false;
        boolean foundRules = false;

        for (int i = 0; i < submodels.getLength(); i++) {
            Element sm = (Element) submodels.item(i);
            NodeList idShorts = sm.getElementsByTagNameNS(
                "https://admin-shell.io/aas/3/0", "idShort");
            if (idShorts.getLength() > 0) {
                String id = idShorts.item(0).getTextContent();
                if (id.equals("StateChart_SM")) foundStateChart = true;
                if (id.equals("Rules_SM")) foundRules = true;
            }
        }

        assertTrue("Rules_SM not found", foundRules);
        assertTrue("StateChart_SM not found", foundStateChart);

        System.out.println("Both submodels found successfully:");
        System.out.println("  Rules_SM      found");
        System.out.println("  StateChart_SM found");
    }

    @Test
    public void loads_statechart_definition_from_aasx() throws Exception {
        InputStream xmlStream = getClass()
            .getClassLoader()
            .getResourceAsStream("MachineTool.aasx");

        assertNotNull("MachineTool.aasx not found", xmlStream);

        InputStream stateChartXml = importer.extractFromStream(
            xmlStream,
            "aasx/MachineTool-AAS/MachineTool-AAS.aas.xml"
        );

        StateChartLoader loader = new StateChartLoader();
        StateChartDefinition definition = loader.loadStateChart(stateChartXml);

        assertNotNull("StateChartDefinition is null", definition);
        assertFalse("No states loaded", definition.getStates().isEmpty());
        assertFalse("No transitions loaded", definition.getTransitions().isEmpty());
        assertNotNull("No initial state", definition.getInitialState());
        assertEquals("Initial state should be UNINITIALIZED",
            "UNINITIALIZED", definition.getInitialState());

        System.out.println("StateChart loaded successfully:");
        System.out.println("  Initial state : " + definition.getInitialState());
        System.out.println("  States        : " + definition.getStates().size());
        System.out.println("  Transitions   : " + definition.getTransitions().size());
        definition.getStates().forEach(s ->
            System.out.println("  State: " + s.getName() +
                (s.getEntryAction() != null ?
                    " entry=" + s.getEntryAction().getActionType() : "")));
    }

    @Test
    public void executor_smoke_test() throws Exception {
        InputStream xmlStream = getClass()
            .getClassLoader()
            .getResourceAsStream("MachineTool.aasx");

        InputStream stateChartXml = importer.extractFromStream(
            xmlStream,
            "aasx/MachineTool-AAS/MachineTool-AAS.aas.xml"
        );

        StateChartLoader loader = new StateChartLoader();
        StateChartDefinition definition = loader.loadStateChart(stateChartXml);

        List<String> stateNames = definition.getStates().stream()
            .map(StateDefinition::getName)
            .collect(Collectors.toList());

        assertTrue(stateNames.contains("UNINITIALIZED"));
        assertTrue(stateNames.contains("INITIALIZING"));
        assertTrue(stateNames.contains("STABLE"));
        assertTrue(stateNames.contains("SYNCHRONIZING"));
        assertTrue(stateNames.contains("DECIDING"));
        assertTrue(stateNames.contains("TRIGGERING"));
        assertTrue(stateNames.contains("TERMINATING"));
        assertTrue(stateNames.contains("ERROR"));

        assertEquals("UNINITIALIZED", definition.getInitialState());

        List<TransitionDefinition> fromUninitialized =
            definition.getTransitionsFrom("UNINITIALIZED");
        assertFalse(fromUninitialized.isEmpty());
        assertEquals("STARTUP", fromUninitialized.get(0).getTrigger());
        assertEquals("INITIALIZING", fromUninitialized.get(0).getTo());

        List<TransitionDefinition> fromStable =
            definition.getTransitionsFrom("STABLE");
        assertTrue(fromStable.stream()
            .anyMatch(t -> t.getTrigger().equals("DATA_PUSH_RECEIVED")));

        List<TransitionDefinition> fromTriggering =
            definition.getTransitionsFrom("TRIGGERING");
        assertTrue("TRIGGERING should have a RETRY_EXCEEDED transition to ERROR",
            fromTriggering.stream().anyMatch(t ->
                t.getTrigger().equals("RETRY_EXCEEDED") && t.getTo().equals("ERROR")));

        StateDefinition synchronizing = definition.getState("SYNCHRONIZING");
        assertNotNull(synchronizing.getEntryAction());
        assertEquals("GATEWAY_READ", synchronizing.getEntryAction().getActionType());

        StateDefinition deciding = definition.getState("DECIDING");
        assertNotNull(deciding.getEntryAction());
        assertEquals("RULE_EVALUATE", deciding.getEntryAction().getActionType());

        StateDefinition triggering = definition.getState("TRIGGERING");
        assertNotNull(triggering.getEntryAction());
        assertEquals("SERVICE_CALL", triggering.getEntryAction().getActionType());

        System.out.println("Executor smoke test passed:");
        System.out.println("  All 8 states verified");
        System.out.println("  All key transitions verified (incl. RETRY_EXCEEDED)");
        System.out.println("  All entry actions verified");
        System.out.println("  States: " + stateNames);
    }

    @Test
    public void full_mock_cycle_test() throws Exception {
        StateChartLoader loader = new StateChartLoader();
        AASManager aasManager = new AASManager();
        StateChartExecutor executor = new StateChartExecutor();

        wire(executor, importer, aasManager, loader);

        AASController controller = new AASController();
        setField(controller, "executor", executor);

        // Step 1 — startup
        controller.startup();
        assertEquals("STABLE", executor.getCurrentState());
        System.out.println("After startup        : " + executor.getCurrentState());

        // Step 2 — push a value that stays under every rule's threshold; engine stays STABLE
        controller.onDataPush("CPS_SM", "Temperature", "50.0");
        assertEquals("STABLE", executor.getCurrentState());
        System.out.println("After data push      : " + executor.getCurrentState());

        // Step 3 — shutdown
        controller.shutdown();
        System.out.println("After shutdown       : " + executor.getCurrentState());

        System.out.println("Full mock cycle completed successfully");
    }

    @Test
    public void full_triggering_cycle_test() throws Exception {
        StateChartLoader loader = new StateChartLoader();
        AASManager aasManager = new AASManager();
        StateChartExecutor executor = new StateChartExecutor();

        wire(executor, importer, aasManager, loader);

        // Inject a mock RestTemplate that simulates the external service manager:
        // accepts the trigger and fires a safe COMPLETED callback synchronously.
        injectMockRestTemplate(executor, "cooling-service", "72.0");

        AASController controller = new AASController();
        setField(controller, "executor", executor);

        // Step 1 — startup
        controller.startup();
        assertEquals("Engine should be STABLE after startup",
            "STABLE", executor.getCurrentState());

        // Step 2 — pre-register CPS_SM and seed a value for rule evaluation
        aasManager.registerEmptySubmodel("CPS_SM");
        aasManager.writeProperty("CPS_SM", "Temperature", "90.0");

        // Step 3 — push 90.0 -> Rule_1 MET (90.0 > 85.0) -> TRIGGERING -> mock
        //          returns safe correction 72.0 -> SERVICE_COMPLETE -> STABLE
        controller.onDataPush("CPS_SM", "Temperature", "90.0");

        assertEquals("Engine should return to STABLE after a successful, "
            + "validated triggering cycle",
            "STABLE", executor.getCurrentState());

        System.out.println("Full triggering cycle test passed:");
        System.out.println("  Temperature 90.0 > 85.0 -> Rule_1 MET");
        System.out.println("  DECIDING --CONDITION_MET--> TRIGGERING");
        System.out.println("  mock returned safe correction 72.0");
        System.out.println("  write-back validation accepted 72.0 (<= 85.0)");
        System.out.println("  TRIGGERING --SERVICE_COMPLETE--> STABLE");
        System.out.println("  Final state: " + executor.getCurrentState());
    }

    @Test
    public void nested_submodels_are_addressed_by_idshort_path() throws Exception {
        // Two leaves named "Value" in different collections plus one flat
        // property: paths keep them distinct, flat names stay untouched.
        String xml =
            "<environment xmlns=\"https://admin-shell.io/aas/3/0\">" +
            "<submodels><submodel><idShort>Nested_SM</idShort><submodelElements>" +
            "<submodelElementCollection><idShort>Spindle</idShort><value>" +
            "<property><idShort>Value</idShort><value>11.0</value></property>" +
            "</value></submodelElementCollection>" +
            "<submodelElementCollection><idShort>Axis</idShort><value>" +
            "<submodelElementCollection><idShort>X</idShort><value>" +
            "<property><idShort>Value</idShort><value>22.0</value></property>" +
            "</value></submodelElementCollection>" +
            "</value></submodelElementCollection>" +
            "<property><idShort>Flat</idShort><value>33.0</value></property>" +
            "</submodelElements></submodel></submodels></environment>";

        manager.loadRules(new ByteArrayInputStream(
            xml.getBytes(StandardCharsets.UTF_8)), null);

        assertEquals("11.0", manager.readProperty("Nested_SM", "Spindle.Value"));
        assertEquals("22.0", manager.readProperty("Nested_SM", "Axis.X.Value"));
        assertEquals("33.0", manager.readProperty("Nested_SM", "Flat"));
        assertNull("a bare leaf name must not resolve ambiguously",
            manager.readProperty("Nested_SM", "Value"));
    }

    @Test
    public void merged_behavior_submodel_yields_only_condition_bearing_rules() throws Exception {
        // Rules, States and Transitions merged into ONE submodel: only the
        // collection that declares a condition may become a rule.
        String xml =
            "<environment xmlns=\"https://admin-shell.io/aas/3/0\"><submodels>" +
            "<submodel><idShort>Rules_SM</idShort><submodelElements>" +
            "<submodelElementCollection><idShort>Rule_1</idShort><value>" +
            "<property><idShort>targetSubmodel</idShort><value>CPS_SM</value></property>" +
            "<property><idShort>targetElement</idShort><value>Temperature</value></property>" +
            "<property><idShort>condition</idShort><value>greater_than</value></property>" +
            "<property><idShort>threshold</idShort><value>85.0</value></property>" +
            "<property><idShort>action</idShort><value>cooling-service</value></property>" +
            "</value></submodelElementCollection>" +
            "<submodelElementCollection><idShort>States</idShort><value>" +
            "<submodelElementCollection><idShort>STABLE</idShort><value>" +
            "<property><idShort>isInitial</idShort><value>true</value></property>" +
            "</value></submodelElementCollection>" +
            "</value></submodelElementCollection>" +
            "<submodelElementCollection><idShort>Transitions</idShort><value>" +
            "<submodelElementCollection><idShort>T1</idShort><value>" +
            "<property><idShort>from</idShort><value>STABLE</value></property>" +
            "<property><idShort>guard</idShort><value>NONE</value></property>" +
            "</value></submodelElementCollection>" +
            "</value></submodelElementCollection>" +
            "</submodelElements></submodel></submodels></environment>";

        manager.loadRules(new ByteArrayInputStream(
            xml.getBytes(StandardCharsets.UTF_8)), null);

        assertEquals("only the condition-bearing collection is a rule",
            1, manager.getRules().size());
        assertEquals("Rule_1", manager.getRules().get(0).getIdShort());
    }

    // ─────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────

    /** Reflectively sets a declared field on the target. */
    static void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** Wires collaborators and standard config onto an executor. */
    static void wire(StateChartExecutor executor,
                     AASXImporter importer,
                     AASManager aasManager,
                     StateChartLoader loader) throws Exception {
        setField(executor, "aasxImporter", importer);
        setField(executor, "aasManager", aasManager);
        setField(executor, "stateChartLoader", loader);
        setField(executor, "rulesSubmodelEntry",
            "aasx/MachineTool-AAS/MachineTool-AAS.aas.xml");
        setField(executor, "aasxPath", "src/main/resources/MachineTool.aasx");
        setField(executor, "maxRetries", 3);
        setField(executor, "serviceExecutionUrl", "http://mock-service-manager");
    }

    /**
     * Replaces the executor's RestTemplate with a mock that simulates the
     * external service manager: returns HTTP 200 and synchronously fires
     * onServiceResult with the given serviceId and outputData.
     *
     * The callback fires INSIDE postForEntity so the entire
     * push -> TRIGGERING -> result -> STABLE/ERROR cycle completes
     * synchronously on one thread, with no timing races.
     */
    @SuppressWarnings("unchecked")
    static void injectMockRestTemplate(StateChartExecutor executor,
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
}
