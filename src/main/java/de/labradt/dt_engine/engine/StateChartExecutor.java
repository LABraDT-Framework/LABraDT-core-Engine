package de.labradt.dt_engine.engine;

import de.labradt.dt_engine.engine.model.*;
import de.labradt.dt_engine.shared.dto.ExecutionResponseDTO;
import de.labradt.dt_engine.shared.dto.ExecutionStatus;
import lombok.extern.slf4j.Slf4j;
import de.labradt.dt_engine.engine.aas.AASManager;
import de.labradt.dt_engine.engine.aas.AASXImporter;
import de.labradt.dt_engine.engine.aas.StateChartLoader;
import de.labradt.dt_engine.engine.aas.RepositoryClient;
import de.labradt.dt_engine.engine.aas.RuleValidator;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import de.labradt.dt_engine.engine.model.*;
import de.labradt.dt_engine.shared.FileStorageService;
import de.labradt.dt_engine.shared.db.DatabaseStorageService;
import de.labradt.dt_engine.shared.dto.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The StateChartExecutor is the core of the engine.
 * It reads the StateChart_SM from the AASX at startup,
 * then drives all state transitions and action calls
 * based purely on what is defined in the state chart.
 * No behavior is hardcoded here.
 */
@Slf4j
@Component
@EnableScheduling
public class StateChartExecutor {

    @Autowired private AASXImporter aasxImporter;
    @Autowired private AASManager aasManager;
    @Autowired private StateChartLoader stateChartLoader;
    @Autowired(required = false) private RepositoryClient repository;
    private final Map<String,String> pendingRepositoryValues = new LinkedHashMap<>();
    private volatile boolean repositoryReady = false;
    private volatile String repositoryError = "Not initialized";
    // Business lifecycle is independent of the AASX execution state (STABLE/TRIGGERING).
    private String lifecycle = "STOPPED";
    private String runId = "";
    private boolean lifecycleManaged;
    private long lastTelemetryAt;
    @Value("${engine.lifecycle.autoStart:false}") private boolean autoStart;

    public synchronized Map<String,Object> runtimeStatus() {
        return Map.of("state", lifecycle, "configurationLocked", !"STOPPED".equals(lifecycle),
            "executionState", Objects.toString(currentState, "INITIALIZING"), "runId", runId,
            "telemetryAgeMs", lastTelemetryAt == 0 ? -1L : Math.max(0L, System.currentTimeMillis() - lastTelemetryAt),
            "inFlightExecutions", expectedExecutionIds.size());
    }
    public synchronized void requireStopped() {
        if (!"STOPPED".equals(lifecycle)) throw new RuntimeLockedException("Stop Backend before changing configuration or testing.");
    }
    public synchronized Map<String,Object> validateRuntime() {
        requireStopped();
        if (stateChart == null || aasManager == null) throw new IllegalArgumentException("Engine model is not initialized; inspect Engine logs.");
        if (lastTelemetryAt == 0 || System.currentTimeMillis() - lastTelemetryAt > 15000)
            throw new IllegalArgumentException("No fresh telemetry for this Engine channel; check Gateway connection and machine ID.");
        List<Rule> rules = repository != null && repository.enabled() ? repository.readRules() : aasManager.getRules();
        RuleValidator.validate(rules);
        if (repository != null && repository.enabled()) repository.validateExecutionTargets(rules, dataSubmodelIdShort);
        return Map.of("valid", true, "rules", rules.size(), "channel", dataSubmodelIdShort);
    }
    public synchronized Map<String,Object> startRuntime() {
        if ("RUNNING".equals(lifecycle)) return runtimeStatus();
        validateRuntime();
        lifecycleManaged = true;
        lifecycle = "STARTING";
        try {
            if (repository != null && repository.enabled()) {
                List<Rule> rules = repository.readRules();
                RuleValidator.validate(rules); repository.validateExecutionTargets(rules, dataSubmodelIdShort);
                aasManager.replaceRules(rules);
            }
            clearExecutionAttempt();
            currentState = "STABLE";
            repositoryReady = true; repositoryError = "";
            runId = UUID.randomUUID().toString();
            lifecycle = "RUNNING";
            log.info("[Lifecycle] Backend RUNNING, runId={}", runId);
            return runtimeStatus();
        } catch (RuntimeException error) {
            lifecycle = "STOPPED";
            throw error;
        }
    }
    public synchronized Map<String,Object> stopRuntime() {
        lifecycleManaged = true;
        lifecycle = "STOPPING";
        clearExecutionAttempt();
        if (stateChart != null) currentState = "STABLE";
        lifecycle = "STOPPED";
        log.info("[Lifecycle] Backend STOPPED; remote jobs may finish, but their results cannot write back");
        return runtimeStatus();
    }
    private void clearExecutionAttempt() {
        cancelRecoveryTimer(); actionQueue.clear(); expectedExecutionIds.clear();
        inFlightRule = null; attemptConcluded = true; retryCount = 0;
        lastResponseStatus = null; syncConfirmed = false;
    }

    // Manual tests never enter the automatic state chart, cooldown or callback set.
    private final RuleTestRuns ruleTestRuns = new RuleTestRuns();

    public synchronized Map<String,Object> testRuntimeRule(String id, Map<String,String> overrides, boolean trigger) {
        requireStopped();
        if (stateChart == null || aasManager == null) throw new IllegalArgumentException("Engine model is not initialized");
        List<Rule> stored = repository != null && repository.enabled() ? repository.readRules() : aasManager.getRules();
        Rule source = stored.stream().filter(r -> id.equals(r.getIdShort())).findFirst()
            .orElseThrow(() -> new NoSuchElementException("Rule not found"));
        Rule rule = new Rule(); source.getAttributes().forEach(rule::setAttribute);
        RuleValidator.validate(List.of(rule));
        if (repository != null && repository.enabled()) repository.validateExecutionTargets(List.of(rule), dataSubmodelIdShort);
        if (!dataSubmodelIdShort.equals(rule.getTargetSubmodel())) throw new IllegalArgumentException("Rule is outside this Engine telemetry channel");
        Set<String> names = new LinkedHashSet<>();
        if (rule.getTargetElement() != null && !rule.getTargetElement().isBlank()) names.add(rule.getTargetElement());
        if ("wear_average".equals(rule.getCondition()))
            Arrays.stream(rule.getAttribute("inputs").split(",")).map(String::trim).forEach(names::add);
        RuleValidator.parameters(rule).elements().forEachRemaining(value->{if(value.isObject())names.add(value.path("input").asText());});
        if (!names.containsAll(overrides.keySet())) throw new IllegalArgumentException("Test overrides must name inputs consumed by this rule");
        boolean liveRequired = !overrides.keySet().containsAll(names);
        if (liveRequired && (lastTelemetryAt == 0 || System.currentTimeMillis() - lastTelemetryAt > 15000))
            throw new IllegalArgumentException("Live inputs are stale; check Gateway or supply every input as a test override");
        Map<String,String> values = new LinkedHashMap<>(), sources = new LinkedHashMap<>();
        for (String name : names) {
            if (aasManager.readProperty(rule.getTargetSubmodel(), name) == null)
                throw new IllegalArgumentException("Input property is not loaded: " + name);
            String raw = overrides.containsKey(name) ? overrides.get(name) : resolveValue(rule.getTargetSubmodel(), name);
            DataValidation check = validateAndNormalize(rule.getTargetSubmodel(), name, raw);
            if (!check.accepted) throw new IllegalArgumentException("Invalid input " + name + ": " + check.reason);
            String value = check.normalizedValue;
            String condition = rule.getCondition();
            boolean conditionInput = Objects.equals(name,rule.getTargetElement()) || "wear_average".equals(condition) && Arrays.stream(rule.getAttribute("inputs").split(",")).anyMatch(input -> name.equals(input.trim()));
            if ((conditionInput && Set.of("greater_than", "less_than", "wear_average").contains(condition) && !isParsableNumber(value))
                || (Objects.equals(name,rule.getTargetElement()) && "out_of_bounds".equals(condition) && (value.split(",", -1).length != 3
                    || Arrays.stream(value.split(",", -1)).anyMatch(part -> !isParsableNumber(part.trim())))))
                throw new IllegalArgumentException("Invalid input format for " + condition + ": " + name);
            values.put(name, value); sources.put(name, overrides.containsKey(name) ? "test override" : "live snapshot");
        }
        ConditionEvaluator evaluator = conditionEvaluators.get(rule.getCondition());
        if (evaluator == null) throw new IllegalArgumentException("Condition evaluator is unavailable");
        boolean matched;
        try { matched = evaluator.evaluate(new EvaluationContext(rule, values.get(rule.getTargetElement()), (sm, name) -> values.get(name))); }
        catch (Exception error) { throw new IllegalArgumentException("Condition evaluation failed: " + error.getMessage(), error); }
        Long previous = ruleLastTriggeredAt.get(ruleRuntimeKey(rule));
        long remaining = previous == null ? 0 : Math.max(0, rule.getCooldownMs() - (clock.getAsLong() - previous));
        Map<String,Object> plan = new LinkedHashMap<>();
        plan.put("mode", trigger ? "test-trigger" : "dry-run"); plan.put("ruleId", id);
        plan.put("rule", new LinkedHashMap<>(rule.getAttributes())); plan.put("inputs", values); plan.put("inputSources", sources);
        plan.put("conditionMet", matched); plan.put("enabled", !"false".equals(rule.getAttribute("enabled")));
        plan.put("cooldownRemainingMs", remaining);
        plan.put("wouldAutoTrigger", matched && !"false".equals(rule.getAttribute("enabled")) && remaining == 0);
        plan.put("serviceIds", rule.getActions()); plan.put("dynamicArgs", serviceArguments(rule, rule.getTargetElement(),values));
        if (repository != null && repository.enabled()) {
            Map<String,Object> effective = new LinkedHashMap<>();
            for (JsonNode service : repository.serviceRegistry()) {
                String serviceId = service.path("serviceId").asText(service.path("id").asText());
                if (!rule.getActions().contains(serviceId)) continue;
                Map<String,Object> args = new LinkedHashMap<>();
                service.path("defaultArgs").fields().forEachRemaining(entry -> args.put(entry.getKey(), entry.getValue()));
                args.putAll(serviceArguments(rule, rule.getTargetElement(),values)); effective.put(serviceId, args);
            }
            plan.put("effectiveServiceArgs", effective);
        }
        plan.put("writeBack", false); plan.put("evaluatedAt", System.currentTimeMillis());
        plan.put("message", matched ? "Condition matched. Manual tests ignore enabled/cooldown, never retry or write back. Service side effects are still possible."
            : "Condition not matched; no Service will be executed.");
        if (!trigger) return plan;
        return ruleTestRuns.submit(plan, rule, restTemplate, serviceExecutionUrl);
    }

    /** Results are polled outside the Engine monitor; Start/Stop need not wait for remote jobs. */
    public Map<String,Object> getRuleTest(String testId) {
        return ruleTestRuns.read(testId, restTemplate, serviceExecutionUrl, this::validTestCorrection);
    }
    private boolean validTestCorrection(Rule rule, String value) {
        if (Set.of("greater_than", "less_than").contains(rule.getCondition()) && !isParsableNumber(value)) return false;
        if ("out_of_bounds".equals(rule.getCondition()) && (value.split(",", -1).length != 3
            || Arrays.stream(value.split(",", -1)).anyMatch(part -> !isParsableNumber(part.trim())))) return false;
        return isCorrectionWithinBounds(rule, value);
    }

    private Map<String,String> serviceArguments(Rule rule, String fallbackElement) {
        return serviceArguments(rule,fallbackElement,null);
    }
    private Map<String,String> serviceArguments(Rule rule, String fallbackElement, Map<String,String> snapshot) {
        Map<String,String> args = new LinkedHashMap<>();
        args.put("submodelId", rule != null && rule.getTargetSubmodel() != null ? rule.getTargetSubmodel() : dataSubmodelIdShort);
        args.put("elementId", rule != null && rule.getTargetElement() != null && !rule.getTargetElement().isBlank()
            ? rule.getTargetElement() : Objects.toString(fallbackElement, "unknown"));
        args.put("callbackUrl", callbackUrl);
        if (rule != null) rule.getAttributes().forEach((key,value) -> {if (key.startsWith("arg.")) args.put(key.substring(4), value);});
        if(rule!=null) RuleValidator.parameters(rule).fields().forEachRemaining(entry->{
            JsonNode binding=entry.getValue();
            String value=binding.isObject()?(snapshot==null?resolveValue(rule.getTargetSubmodel(),binding.path("input").asText()):snapshot.get(binding.path("input").asText())):binding.asText();
            if(value==null)throw new IllegalArgumentException("Missing Service parameter input: "+entry.getKey());
            args.put(entry.getKey(),value);
        });
        return args;
    }

    public boolean isRepositoryReady() { return repository == null || !repository.enabled() || repositoryReady; }
    public String getRepositoryError() { return repositoryError; }
    public synchronized boolean isTelemetryReady() {
        if (stateChart == null || lastReceivedValue == null || aasManager == null) return false;
        for (Rule rule : aasManager.getRules()) {
            if (!dataSubmodelIdShort.equals(rule.getTargetSubmodel())) return false;
            if (rule.getTargetElement() != null && !rule.getTargetElement().isBlank() &&
                aasManager.readProperty(dataSubmodelIdShort, rule.getTargetElement()) == null) return false;
            if ("wear_average".equals(rule.getCondition())) for (String input : rule.getAttribute("inputs").split(","))
                if (aasManager.readProperty(dataSubmodelIdShort, input.trim()) == null) return false;
            Iterator<JsonNode> parameters = RuleValidator.parameters(rule).elements();
            while (parameters.hasNext()) {
                JsonNode parameter = parameters.next();
                if (parameter.isObject() && aasManager.readProperty(dataSubmodelIdShort, parameter.path("input").asText()) == null) return false;
            }
        }
        return true;
    }
    public synchronized List<Map<String,String>> getRuntimeRules() {
        return aasManager.getRules().stream().map(r -> new LinkedHashMap<>(r.getAttributes())).map(r -> (Map<String,String>) r).toList();
    }
    public synchronized void mutateRuntimeRule(String id, Map<String,String> attributes, boolean create) {
        requireStopped();
        if (id == null || !id.matches("[A-Za-z][A-Za-z0-9_]{0,127}")) throw new IllegalArgumentException("Invalid rule idShort");
        if (repository == null || !repository.enabled()) throw new IllegalStateException("AAS Repository is not configured");
        List<Rule> rules = new ArrayList<>(repository.readRules());
        boolean found = rules.stream().anyMatch(r -> id.equals(r.getIdShort()));
        if (create && found) throw new IllegalArgumentException("Rule already exists");
        if (!create && !found) throw new NoSuchElementException("Rule not found");
        rules.removeIf(r -> id.equals(r.getIdShort()));
        if (attributes != null) {
            Rule rule = new Rule(); attributes.forEach(rule::setAttribute); rule.setAttribute("idShort", id); rules.add(rule);
        }
        RuleValidator.validate(rules);
        // Removing a rule must remain possible while commissioning a machine
        // whose live inputs don't match the remaining bundled demo rules yet.
        // Start still validates the entire set before enabling execution.
        if (attributes != null) repository.validateExecutionTargets(rules, dataSubmodelIdShort);
        repository.saveRules(rules); // Never report success before durable storage succeeds.
        aasManager.replaceRules(rules);
        repositoryReady = true; repositoryError = "";
    }
    @Scheduled(fixedDelay = 1500, initialDelay = 5000)
    public synchronized void synchronizeRepository() {
        if (repository == null || !repository.enabled() || stateChart == null) return;
        try {
            // RUNNING uses the Start-time rule snapshot; Repository edits never hot-swap it.
            if ("STOPPED".equals(lifecycle)) {
                List<Rule> rules = repository.readRules(); RuleValidator.validate(rules);
                aasManager.replaceRules(rules);
            }
            repository.mirrorLiveValues(pendingRepositoryValues);
            pendingRepositoryValues.clear();
            repositoryReady = true; repositoryError = "";
        } catch (Exception error) {
            repositoryReady = false; repositoryError = error.getMessage();
            log.warn("[Repository] Sync failed; retaining runtime rules and queued telemetry: {}", error.getMessage());
        }
    }
    @Autowired(required = false)
    private FileStorageService storageService;

    @Value("${engine.rules.submodel.entry}")
    private String rulesSubmodelEntry;

    /**
     * Filesystem path to this instance's AASX file. Read from disk in
     * initializeEngine() rather than a hardcoded classpath resource name, so
     * that overriding this property per Spring profile (see
     * application-molder1.properties) actually determines which physical
     * AASX file this instance loads, not just documentation of intent.
     */
    @Value("${engine.aasx.path}")
    private String aasxPath;

    /**
     * idShort of the one submodel treated as the live writable channel for
     * machine data. It is the target of incoming gateway pushes and of
     * service corrections written back to the machine. Reuses
     * gateway.submodelId (already read by GatewayStreamClient to tag
     * incoming SSE pushes) instead of a second independent property, so
     * renaming this submodel only requires changing one value, and the
     * stream client and the write back path can never drift out of sync.
     * This only renames which single submodel plays the role. The engine
     * still assumes exactly one channel for live machine data.
     *
     * The field initializer below matches the @Value default so that a
     * plain "new StateChartExecutor()" (as the test suite does, without a
     * Spring context) still gets "CPS_SM" instead of null.
     */
    @Value("${gateway.submodelId:CPS_SM}")
    private String dataSubmodelIdShort = "CPS_SM";

    @Value("${engine.retry.max:3}")
    private int maxRetries;

    @Value("${engine.callback.url:http://localhost:8080/engine/result}")
    private String callbackUrl;

    @Value("${service.execution.url:http://localhost:8082/services/trigger}")
    private String serviceExecutionUrl;

    private final RestTemplate restTemplate = boundedServiceClient();
    private static RestTemplate boundedServiceClient() {
        // A dead Service Manager must not hold the Engine monitor indefinitely,
        // preventing a business Stop from acquiring it.
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
            new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000); factory.setReadTimeout(3000);
        return new RestTemplate(factory);
    }
    private final ObjectMapper objectMapper = new ObjectMapper();

    private StateChartDefinition stateChart;
    private String currentState;
    private final Queue<Rule> actionQueue = new LinkedList<>();
    private int retryCount = 0;
    private String lastReceivedValue = null;
    private String lastPushedElementId = null;

    /**
     * Last accepted dispatch time per rule. The AASX already declares a
     * cooldownMs value on each bundled rule; keeping the runtime timestamps
     * here makes that model field effective and prevents a continuously true
     * condition from flooding the Service Manager on every telemetry update.
     */
    private final Map<String, Long> ruleLastTriggeredAt = new HashMap<>();

    /** Replaceable in tests so cooldown behavior is deterministic. */
    private LongSupplier clock = System::currentTimeMillis;

    /**
     * The rule that owns the most recently dispatched service call. It is
     * captured once per dispatch instead of being read again from
     * actionQueue on a retry, so that a queue rebuild while the call is
     * still running (handleRuleEvaluate clears actionQueue on every data
     * push) cannot cause a retry or the eventual result to be attributed
     * to the wrong rule.
     *
     * This field is deliberately never reset to null when an attempt
     * finishes. A rule can dispatch several services at once (see
     * Rule.getActions()), so a second service's result can legitimately
     * arrive after the first one already fired SERVICE_COMPLETE, and it
     * still needs the correct rule for its own bounds check (as long as
     * attemptConcluded has not already ruled that second result out, see
     * below). Leaving it set between attempts is safe because it is only
     * ever read for a result whose executionId the expectedExecutionIds
     * check has already confirmed belongs to the current attempt. The
     * next real dispatch overwrites it regardless.
     */
    private Rule inFlightRule = null;

    /**
     * executionIds returned by the Runtime component for the
     * attempt that is currently running (one per service that was
     * triggered). This set is replaced completely on every new dispatch,
     * including a retry, since a retry is its own dispatch with its own
     * ids. onServiceResult() checks an incoming result's executionId
     * against this set before acting, so a late or stale response from an
     * attempt that has since been superseded is recognized and ignored
     * instead of being treated as if it belonged to the current one.
     */
    private final Set<String> expectedExecutionIds = new HashSet<>();

    /**
     * True once the outcome of the current attempt has already been
     * decided, meaning SERVICE_COMPLETE or RETRY_EXCEEDED has already
     * fired for it. Reset to false at the start of every dispatch in
     * handleServiceCall(), whether a fresh dispatch or a retry.
     *
     * This exists because a rule can dispatch several services at once
     * (see Rule.getActions()). Today, the first of those services to
     * report a terminal result decides the whole group's outcome. If a
     * sibling service reports afterward, its executionId can still be
     * one that expectedExecutionIds is tracking (it has not been
     * superseded by a new dispatch), so it would pass the staleness
     * check and be acted on again: reapplying the write back to the
     * gateway, or starting a confusing retry after the engine has
     * already moved on. attemptConcluded catches exactly that case in
     * onServiceResult(), so a sibling's late result is logged and
     * dropped instead of acted on a second time.
     *
     * Note that this only prevents the late result from being
     * misapplied. It does not make the engine wait for every dispatched
     * service before deciding the outcome, and it does not aggregate
     * several services' output data into one correction. Doing that
     * properly would need a way to say which service's output counts as
     * the correction to write back when more than one returns data,
     * which is a modeling decision for the AASX rather than a bug fix.
     * That fuller aggregation is left as future work. It is also not
     * currently reachable: neither MachineTool.aasx nor Molder.aasx
     * declares a rule with more than one action today.
     */
    private boolean attemptConcluded = false;

    /**
     * When true, a value that uses a comma as the decimal separator (for
     * example "76,5", common in sources formatted for a specific locale) is
     * normalized to "76.5" before validation. When false, such a value is
     * treated as not numeric and is rejected by a numeric rule. Configured
     * through engine.data.normalizeDecimalComma (default false).
     */
    @Value("${engine.data.normalizeDecimalComma:false}")
    private boolean normalizeDecimalComma;

    /**
     * Count of data pushes rejected at the validation boundary. Exposed
     * through getRejectedDataCount() for the monitor dashboard, so silent
     * data quality problems become visible instead of vanishing without a
     * trace.
     */
    private long rejectedDataCount = 0;

    /**
     * Timeout for automatic recovery from the ERROR state, in milliseconds.
     * When the engine enters ERROR it arms a single use timer for this
     * duration. When the timer expires it fires RECOVERY_TIMEOUT, which the
     * state chart routes back to INITIALIZING (transition
     * T10_ERROR_RECOVERY). Configured through
     * engine.error.recoveryTimeoutMs (default 30000, matching the AASX). A
     * value of 0 or less disables automatic recovery, so the engine stays
     * in ERROR until something external triggers a change.
     *
     * Note: the AASX ERROR state also declares recoveryTimeoutMs, but that
     * property is not yet exposed through StateDefinition, so the value is
     * taken from configuration here instead. Reading it from the state
     * model would keep the timeout fully declarative and is left as a
     * future improvement.
     */
    @Value("${engine.error.recoveryTimeoutMs:30000}")
    private long recoveryTimeoutMs;

    /**
     * Single thread scheduler for the ERROR recovery timer. Marked as a
     * daemon thread so it never blocks JVM shutdown.
     */
    private final ScheduledExecutorService recoveryScheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "error-recovery-timer");
            t.setDaemon(true);
            return t;
        });

    /** The recovery timer that is currently armed, if any. Cancelled when the engine leaves ERROR. */
    private ScheduledFuture<?> recoveryTask;

    /**
     * Runtime state inspected by guards, so that guard expressions declared
     * in the AASX are actually enforced instead of always assumed true.
     * lastResponseStatus holds the status of the most recent service
     * result. syncConfirmed records whether the latest gateway read
     * completed. Without these two fields, the guards
     * "response.status == COMPLETED" and "syncStatus == CONFIRMED" would
     * always pass without checking anything.
     */
    private ExecutionStatus lastResponseStatus = null;
    private boolean syncConfirmed = false;

    /**
     * Delay applied before each service retry, in milliseconds. The AASX
     * SERVICE_CALL action declares retryDelayMs as the intended spacing
     * between attempts, and that value is surfaced here through
     * configuration (engine.service.retryDelayMs). A value of 0 (the
     * default) applies no delay, which keeps unit tests instant. In
     * production a nonzero spacing can be set so a service that is
     * temporarily unavailable is not hammered with repeated calls.
     *
     * Note: like the ERROR recovery timeout, the value that should be
     * authoritative lives in the AASX, but it is not yet exposed through
     * the parsed action model, so it is read from configuration instead.
     * Reading it from the action would make the spacing fully declarative
     * and is left as a future improvement.
     */
    @Value("${engine.service.retryDelayMs:0}")
    private long retryDelayMs;

    @Autowired(required = false)
    private DatabaseStorageService dbStorageService;

    @Autowired(required = false)
    private GatewayWriteClient gatewayWriteClient;

    /**
     * Registry of condition evaluators, keyed by condition name (the value
     * of a rule's "condition" property). This is the extension point for
     * rule operators: each entry is an independent evaluator, so a new
     * operator is added by registering it in registerConditionEvaluators()
     * (or from elsewhere through registerCondition(...)) instead of editing
     * a switch statement. The set of operators is open for extension and
     * closed for modification. Populated once in
     * registerConditionEvaluators().
     */
    private final Map<String, ConditionEvaluator> conditionEvaluators = new HashMap<>();


    // ─────────────────────────────────────────
    // PUBLIC API (called by AASController)
    // ─────────────────────────────────────────

    /**
     * Called on system startup.
     * Loads the state chart and rules, then transitions to STABLE.
     */
    public synchronized void startup() {
        if (lifecycleManaged) { startRuntime(); return; }
        log.info("[StateChartExecutor] Starting up...");
        initializeEngine();
        lifecycle = "RUNNING"; // Legacy explicit startup, retained for existing integrations/tests.
    }

    /**
     * Initializes the model once Spring is ready. Gateway listening and live
     * telemetry stay online, but business execution defaults to STOPPED.
     * The runtime Start API validates and enables automatic execution explicitly.
     */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void onApplicationReady() {
        lifecycleManaged = true;
        initializeEngine();
        if (autoStart) lifecycle = "RUNNING";
        else stopRuntime();
    }

    /**
     * Called when the Gateway pushes new data.
     */
    public synchronized void onDataPush(String submodelId, String elementId, String value) {
        receiveTelemetry(submodelId, elementId, value, true);
    }

    public synchronized void onDataSnapshot(String submodelId, String elementId, String value) {
        receiveTelemetry(submodelId, elementId, value, false);
    }

    private void receiveTelemetry(String submodelId, String elementId, String value, boolean evaluateRules) {
        log.info("[StateChartExecutor] Data push received: {}:{} = {}",
            submodelId, elementId, value);

        // Validate and normalize at the single entry point. Both the SSE
        // stream client and the /engine/push endpoint funnel through here,
        // so one check covers every reception path. A rejected value is
        // recorded as a quarantined entry (visible in the data history)
        // and does NOT reach the live AAS model or fire an evaluation.
        // The engine never acts on data it could not validate.
        DataValidation result = validateAndNormalize(submodelId, elementId, value);
        if (!result.accepted) {
            rejectedDataCount++;
            log.warn("[StateChartExecutor] Data push REJECTED ({}): {}.{} = '{}' — quarantined, "
                + "total rejected={}", result.reason, submodelId, elementId, value, rejectedDataCount);
            if (dbStorageService != null) {
                // Reuse the existing data table. The marker prefix makes
                // the entry auditable without a schema change, and it is
                // never read back as a live value (only accepted pushes
                // update the model).
                dbStorageService.storeSubmodelData(submodelId, elementId,
                    "[REJECTED:" + result.reason + "] " + value);
            }
            return;
        }

        String accepted = result.normalizedValue;
        lastReceivedValue = accepted;
        if (submodelId.equals(dataSubmodelIdShort)) lastTelemetryAt = System.currentTimeMillis();
        lastPushedElementId = elementId;

        // Persist the accepted (normalized) value, then update the model.
        if (dbStorageService != null) {
            dbStorageService.storeSubmodelData(submodelId, elementId, accepted);
        }
        aasManager.writeProperty(submodelId, elementId, accepted);
        if (submodelId.equals(dataSubmodelIdShort)) pendingRepositoryValues.put(elementId, accepted);

        // Telemetry continues while an asynchronous service is running. Keep
        // persisting those fresh values, but do not send an event the AASX
        // state chart cannot handle in TRIGGERING. The next gateway update
        // after the callback returns the engine to STABLE will evaluate the
        // latest stored snapshot.
        if (!evaluateRules || !"RUNNING".equals(lifecycle) || "TRIGGERING".equals(currentState)) {
            log.debug("[StateChartExecutor] Stored {}.{} while service is in flight; "
                + "deferring rule evaluation", submodelId, elementId);
            return;
        }
        fireEvent("DATA_PUSH_RECEIVED");
    }

    public synchronized boolean acceptAasValue(String submodelId, String path, String value) {
        requireStopped();
        long before = rejectedDataCount;
        long telemetryBefore = lastTelemetryAt;
        onDataPush(submodelId, path, value);
        lastTelemetryAt = telemetryBefore; // Manual tests are not evidence of a live machine connection.
        return rejectedDataCount == before;
    }

    /** Total number of data pushes rejected at validation. For the dashboard. */
    public long getRejectedDataCount() {
        return rejectedDataCount;
    }

    /** Number of currently loaded rules. For the monitoring endpoint. */
    public int getRuleCount() {
        return aasManager != null ? aasManager.getRules().size() : 0;
    }

    /**
     * Outcome of boundary validation: whether the value was accepted, the
     * normalized form to use if so, and a short reason if not.
     */
    private static final class DataValidation {
        final boolean accepted;
        final String normalizedValue;
        final String reason;
        private DataValidation(boolean accepted, String normalizedValue, String reason) {
            this.accepted = accepted;
            this.normalizedValue = normalizedValue;
            this.reason = reason;
        }
        static DataValidation accept(String normalized) {
            return new DataValidation(true, normalized, null);
        }
        static DataValidation reject(String reason) {
            return new DataValidation(false, null, reason);
        }
    }

    /**
     * Validates and normalizes an incoming value at the reception boundary.
     *
     * Universal checks apply to every value regardless of rule type. A
     * value that is null or blank is rejected. Surrounding whitespace is
     * trimmed. If engine.data.normalizeDecimalComma is on, a decimal
     * comma is converted to a dot.
     *
     * A type check applies only when a numeric rule consumes this
     * element. If any rule targeting this element uses a condition that
     * expects a single number (greater_than or less_than) and the value
     * does not parse as a number, it is rejected. Conditions with their
     * own formats (out_of_bounds, equals, operators with several
     * inputs) are not forced to be single numbers here. They validate
     * their own input further downstream.
     */
    private DataValidation validateAndNormalize(String submodelId,
                                                String elementId,
                                                String value) {
        if (value == null || value.isBlank()) {
            return DataValidation.reject("empty");
        }

        String v = value.trim();

        if (normalizeDecimalComma && looksLikeDecimalComma(v)) {
            v = v.replace(',', '.');
        }

        if (numericRuleTargets(elementId) && !isParsableNumber(v)) {
            return DataValidation.reject("non_numeric");
        }
        for (Rule rule : aasManager.getRules()) {
            if (!Objects.equals(submodelId, rule.getTargetSubmodel())) continue;
            if ("out_of_bounds".equals(rule.getCondition()) && Objects.equals(elementId, rule.getTargetElement())) {
                String[] vector = v.split(",");
                if (vector.length != 3 || Arrays.stream(vector).anyMatch(part -> !isParsableNumber(part.trim())))
                    return DataValidation.reject("invalid_vector");
            }
            if ("wear_average".equals(rule.getCondition()) && rule.getAttribute("inputs") != null &&
                Arrays.stream(rule.getAttribute("inputs").split(",")).anyMatch(input -> input.trim().equals(elementId)) && !isParsableNumber(v))
                return DataValidation.reject("non_numeric_input");
        }

        return DataValidation.accept(v);
    }

    /** True if the string is a single decimal number written with a comma. */
    private boolean looksLikeDecimalComma(String v) {
        return v.matches("-?\\d+,\\d+");
    }

    /** True if the string parses as a double. */
    private boolean isParsableNumber(String v) {
        try {
            return Double.isFinite(Double.parseDouble(v));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * True if any loaded rule consumes this element through a strictly
     * numeric condition that expects a single value, so a value that is
     * not numeric would be a genuine error rather than a legitimate
     * string or CSV input meant for some other operator.
     */
    private boolean numericRuleTargets(String elementId) {
        if (elementId == null) return false;
        for (Rule rule : aasManager.getRules()) {
            String cond = rule.getCondition();
            boolean single = "greater_than".equals(cond) || "less_than".equals(cond);
            if (single && elementId.equals(rule.getTargetElement())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Called when a service execution result comes back, through
     * AASController, forwarded from the Runtime component.
     * Before acting on it, the result is checked against the attempt that
     * is currently running, so a result that belongs to an older,
     * superseded attempt is recognized and ignored instead of being
     * treated as current. See the executionId check near the top of this
     * method and the inFlightRule field for how that correlation works.
     *
     * If the triggering rule dispatched several services at once (see
     * Rule.getActions()), only the first of them to report a terminal
     * result decides this attempt's outcome. A sibling's result that
     * arrives afterward is recognized through the attemptConcluded field
     * and dropped instead of being acted on a second time. See
     * attemptConcluded for why fuller aggregation across every
     * dispatched service is left as future work rather than done here.
     */
    public synchronized void onServiceResult(ExecutionResponseDTO response) {
        if (!"RUNNING".equals(lifecycle) || lifecycleManaged && response.getExecutionId() == null) {
            log.info("[Lifecycle] Ignoring callback outside the active identified run");
            return;
        }
        String incomingExecutionId = response.getExecutionId();

        // Correlate against the attempt that is currently running before
        // touching any state. A response carrying an executionId that is
        // not one we are currently expecting belongs to an attempt that
        // has since been superseded (a stale response from before a
        // retry, or a result for a rule the queue has already moved
        // past). Such a response is logged and dropped instead of being
        // applied as if it were current. A null executionId (the caller
        // has not been updated yet to send one) is let through
        // unchecked, matching the behavior from before this check
        // existed, which simply trusted whatever was next in line.
        if (incomingExecutionId != null && !expectedExecutionIds.contains(incomingExecutionId)) {
            log.warn("[StateChartExecutor] Ignoring stale/stray service result: executionId={} "
                + "serviceId={} status={} does not match current in-flight attempt (expected={})",
                incomingExecutionId, response.getServiceId(), response.getStatus(),
                expectedExecutionIds);
            return;
        }

        log.info("[StateChartExecutor] Service result: {} executionId={} status={}",
            response.getServiceId(), incomingExecutionId, response.getStatus());

        // Ignore intermediate statuses. Stay in TRIGGERING and wait for
        // COMPLETED. Keep tracking the executionId, since more updates
        // for it are still expected. Do not let a late ACCEPTED/RUNNING
        // callback overwrite a terminal status used by the state-chart
        // guard. Service Manager callbacks are asynchronous and can arrive
        // on different HTTP worker threads.
        if (response.getStatus() == ExecutionStatus.ACCEPTED
                || response.getStatus() == ExecutionStatus.RUNNING) {
            log.info("[StateChartExecutor] Intermediate status: {} — waiting for COMPLETED",
                response.getStatus());
            return;
        }

        // Record only a terminal status. In particular, COMPLETED must remain
        // visible while SERVICE_COMPLETE evaluates its guard.
        lastResponseStatus = response.getStatus();

        // Terminal status: this executionId's job is done. Stop expecting
        // it, so that a later duplicate delivery of the same result is
        // recognized as stale instead of being applied again.
        if (incomingExecutionId != null) {
            expectedExecutionIds.remove(incomingExecutionId);
        }

        // If the triggering rule dispatched several services at once, a
        // sibling service's result may still arrive after another one
        // already decided this attempt's outcome (attemptConcluded is
        // set at each of the three points below where that happens).
        // Such a result is not stale by executionId (it may still be
        // one that expectedExecutionIds was tracking), but it must not
        // be run through the bounds check, write back, or retry logic a
        // second time.
        if (attemptConcluded) {
            log.warn("[StateChartExecutor] Ignoring result for {} (executionId={}, status={}): "
                + "this attempt was already concluded by a sibling service's result",
                response.getServiceId(), incomingExecutionId, response.getStatus());
            return;
        }

        if (response.getStatus() == ExecutionStatus.COMPLETED) {
            retryCount = 0;

            String corrected = response.getOutputData();

            // Validate the corrected value before writing it back to the
            // machine. A corrective service is meant to bring a value back
            // into a safe range, but a malfunctioning service could return
            // a value that is still out of range (or wildly wrong), and
            // writing that to the gateway could harm the machine. The
            // corrected value is checked against the inverse of the rule
            // that fired. For example, a rule "greater_than 80.0" should
            // be corrected to a value less than or equal to 80.0. If the
            // check fails, the write is skipped and the engine diverts to
            // ERROR (where automatic recovery applies) instead of pushing
            // an unsafe value.
            Rule triggeredRule = inFlightRule;
            if (corrected != null && triggeredRule != null
                    && !isCorrectionWithinBounds(triggeredRule, corrected)) {
                log.error("[StateChartExecutor] Corrected value {} fails inverse check for rule {} "
                    + "({} {}) — skipping write-back, diverting to ERROR",
                    corrected, triggeredRule.getIdShort(),
                    triggeredRule.getCondition(), triggeredRule.getThreshold());
                attemptConcluded = true;
                fireEvent("RETRY_EXCEEDED");  // this transition takes the engine from TRIGGERING to ERROR
                return;
            }

            // Write corrected value back to machine through the Gateway
            String correctionSubmodel = triggeredRule != null
                && triggeredRule.getTargetSubmodel() != null
                && !triggeredRule.getTargetSubmodel().isBlank()
                    ? triggeredRule.getTargetSubmodel()
                    : dataSubmodelIdShort;
            String correctionElement = triggeredRule != null
                && triggeredRule.getTargetElement() != null
                && !triggeredRule.getTargetElement().isBlank()
                    ? triggeredRule.getTargetElement()
                    : lastPushedElementId;

            if (gatewayWriteClient != null
                    && corrected != null
                    && correctionElement != null) {
                log.info("[StateChartExecutor] Writing correction back to gateway: {}.{} = {}",
                    correctionSubmodel, correctionElement, corrected);
                gatewayWriteClient.writeValue(
                    correctionSubmodel,
                    correctionElement,
                    corrected);
            }

            attemptConcluded = true;
            fireEvent("SERVICE_COMPLETE");
        } else {
            // Actual failure (FAILED, TIMEOUT and so on).
            // Important: do not fire SERVICE_COMPLETE here. That is the
            // success transition, from TRIGGERING to STABLE, and firing it
            // on a failure would make it look like the call had
            // succeeded. Instead, on a retryable failure the service is
            // invoked again, staying in TRIGGERING for a genuine new
            // attempt. Only once retries are exhausted does the engine
            // fire RETRY_EXCEEDED, which takes it from TRIGGERING to
            // ERROR.
            if (retryCount >= maxRetries) {
                log.error("[StateChartExecutor] Retry limit reached ({}/{}) — firing RETRY_EXCEEDED",
                    retryCount, maxRetries);
                retryCount = 0;  // reset for the next independent trigger
                attemptConcluded = true;
                fireEvent("RETRY_EXCEEDED");
            } else {
                retryCount++;
                log.warn("[StateChartExecutor] Service failed, retry {}/{} — re-invoking service",
                    retryCount, maxRetries);
                // Space out the retry by the configured delay, so that a
                // service that is temporarily unavailable is not hammered
                // with repeated calls. Skipped entirely when the delay is
                // 0 (the default), which keeps tests instant.
                if (retryDelayMs > 0) {
                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("[StateChartExecutor] Retry delay interrupted — re-invoking immediately");
                    }
                }
                // Attempt the same service again. handleServiceCall(null)
                // uses the rule captured in inFlightRule instead of
                // actionQueue.peek(), so this retry still targets the rule
                // that triggered the original call, even if a concurrent
                // RULE_EVALUATE has since rebuilt actionQueue. It also
                // issues a fresh trigger call and replaces
                // expectedExecutionIds with the new attempt's ids, so a
                // late response from this failed attempt is not mistaken
                // for the retry's own response.
                handleServiceCall(null);
            }
        }
    }

    /**
     * Checks whether a corrected value returned by a service is
     * acceptable to write back, by testing it against the inverse of
     * the rule that fired. The idea is that a rule defines the
     * condition that constitutes a problem, so a valid correction is
     * one that no longer satisfies that condition.
     *
     *   greater_than T  → corrected must be less than or equal to T
     *   less_than    T  → corrected must be greater than or equal to T
     *   out_of_bounds   → corrected (x, y, z) must be back within the bounds
     *   equals or others → no numeric inverse is defined, so the value is accepted as it is
     *
     * Returns true when the correction is within safe bounds, or when no
     * check applies. Returns false when it should be rejected. A value
     * that is not numeric or cannot be parsed is rejected for numeric
     * conditions, since it cannot be shown to be safe. This is
     * "Approach A": the bounds are derived from the existing rule, so no
     * new AASX fields are required. A more declarative alternative
     * (explicit correctedMin and correctedMax properties on the rule) is
     * noted as a possible future improvement.
     *
     * This method is public instead of private so it can be tested
     * directly from the test package (see StateChartExecutorTest), since
     * it is a pure function of the rule and the value, and it is the
     * core of the write back safety check.
     */
    public boolean isCorrectionWithinBounds(Rule rule, String corrected) {
        String condition = rule.getCondition();
        String threshold = rule.getThreshold();
        if (condition == null) return true;  // nothing to check against

        try {
            switch (condition) {
                case "greater_than" -> {
                    return Double.parseDouble(corrected) <= Double.parseDouble(threshold);
                }
                case "less_than" -> {
                    return Double.parseDouble(corrected) >= Double.parseDouble(threshold);
                }
                case "out_of_bounds" -> {
                    String[] bounds = threshold.split(",");
                    String[] values = corrected.split(",");
                    if (bounds.length < 6 || values.length < 3) return false;
                    double x = Double.parseDouble(values[0].trim());
                    double y = Double.parseDouble(values[1].trim());
                    double z = Double.parseDouble(values[2].trim());
                    double xMin = Double.parseDouble(bounds[0].trim());
                    double xMax = Double.parseDouble(bounds[1].trim());
                    double yMin = Double.parseDouble(bounds[2].trim());
                    double yMax = Double.parseDouble(bounds[3].trim());
                    double zMin = Double.parseDouble(bounds[4].trim());
                    double zMax = Double.parseDouble(bounds[5].trim());
                    // Valid correction is fully back inside the bounds.
                    return x >= xMin && x <= xMax
                        && y >= yMin && y <= yMax
                        && z >= zMin && z <= zMax;
                }
                default -> {
                    // No numeric inverse is defined for this condition (for
                    // example equals, or a custom operator with several
                    // inputs), so the correction is accepted.
                    return true;
                }
            }
        } catch (NumberFormatException e) {
            // A numeric condition received a correction that is not
            // numeric. It cannot be shown to be safe, so it is rejected.
            log.warn("[StateChartExecutor] Correction '{}' not numeric for rule {} — rejecting",
                corrected, rule.getIdShort());
            return false;
        }
    }

    /**
     * Called on system shutdown.
     */
    public synchronized void shutdown() {
        if (lifecycleManaged) { stopRuntime(); return; }
        log.info("[StateChartExecutor] Shutdown signal received");
        fireEvent("SHUTDOWN_SIGNAL");
    }

    public String getCurrentState() { return currentState; }
    public StateChartDefinition getStateChart() { return stateChart; }

    // ─────────────────────────────────────────
    // INITIALIZATION
    // ─────────────────────────────────────────

    private void initializeEngine() {
        try {
            log.info("[StateChartExecutor] Loading AASX...");

            // Register the condition operators that come with the engine
            // before any rule is evaluated. New operators can be added in
            // this method (or through registerCondition) without touching
            // evaluateCondition().
            registerConditionEvaluators();

            // Read the AASX bytes once, from the filesystem path this
            // instance is configured with (engine.aasx.path), instead of a
            // hardcoded classpath resource name. This is what makes a
            // Spring profile's override (for example
            // application-molder1.properties) actually select a different
            // physical AASX file, instead of every instance always
            // loading the same one regardless of profile. These bytes are
            // reused for every extraction below (through
            // ByteArrayInputStream) and are passed to the AASManager so
            // the initialization marker can record a content hash.
            // Hashing the file lets the engine detect whether the AASX
            // changed since the last run and import the rules again only
            // when it did, which removes the earlier need to manually
            // delete the storage folder.
            byte[] aasxBytes = Files.readAllBytes(Paths.get(aasxPath));

            // Step 1: read behavior model location from AASX
            InputStream locationXml = aasxImporter.extractFromStream(
                new ByteArrayInputStream(aasxBytes), rulesSubmodelEntry);

            AASXImporter.BehaviorModelLocation location =
                aasxImporter.loadBehaviorModelLocation(locationXml);

            String behaviorPath = (location != null)
                ? location.path()
                : rulesSubmodelEntry;

            log.info("[StateChartExecutor] Behavior model path: {}", behaviorPath);

            // Step 2: load rules.
            // The check based on the content hash decides whether the
            // cached rules in storage are still valid for the current
            // AASX. On a match the rules are still parsed into memory
            // (the model kept in memory does not survive a restart), but
            // we know the cache is consistent. On a mismatch the AASX has
            // changed, so the freshly parsed rules overwrite the stale
            // ones and the marker is rewritten with the new hash.
            boolean storageValid = aasManager.isStorageValidFor(aasxBytes);
            if (storageValid) {
                log.info("[StateChartExecutor] AASX unchanged — cached rules valid, reloading into memory");
            } else {
                log.info("[StateChartExecutor] First run or AASX changed — importing rules from AASX");
            }

            InputStream xmlContent = aasxImporter.extractFromStream(
                new ByteArrayInputStream(aasxBytes), behaviorPath);
            aasManager.loadRules(xmlContent, aasxBytes);
            aasManager.registerEmptySubmodel(dataSubmodelIdShort);
            if (repository != null && repository.enabled()) {
                repository.ensureLiveModel(dataSubmodelIdShort);
                List<Rule> durableRules = repository.readRules(); RuleValidator.validate(durableRules);
                aasManager.replaceRules(durableRules);
                repositoryReady = true; repositoryError = "";
            }

            // Seed service Dockerfiles. This is idempotent (each write is
            // guarded by an existence check), so it is safe to run on
            // every startup, whether it is the first run, an unchanged
            // AASX, or a changed AASX.
            seedServiceDockerfiles();

            // Step 3: load state chart
            InputStream xmlContent2 = aasxImporter.extractFromStream(
                new ByteArrayInputStream(aasxBytes), behaviorPath);
            stateChart = stateChartLoader.loadStateChart(xmlContent2);
            currentState = stateChart.getInitialState();

            log.info("[StateChartExecutor] Initialized. Current state: {}", currentState);
            fireEvent("STARTUP");
            fireEvent("READY");

        } catch (Exception e) {
            log.error("[StateChartExecutor] Initialization failed: {}", e.getMessage());
            currentState = "ERROR";
        }
    }

    // ─────────────────────────────────────────
    // CORE STATE MACHINE
    // ─────────────────────────────────────────

    private void fireEvent(String event) {
        if (stateChart == null) {
            log.error("[StateChartExecutor] State chart not loaded");
            return;
        }

        log.info("[StateChartExecutor] Event: {} | State: {}", event, currentState);

        List<TransitionDefinition> candidates =
            stateChart.getTransitionsFrom(currentState);

        for (TransitionDefinition transition : candidates) {
            if (!event.equals(transition.getTrigger())) continue;
            if (!evaluateGuard(transition.getGuard())) continue;

            log.info("[StateChartExecutor] Transitioning: {} --{}--> {}",
                currentState, event, transition.getTo());

            StateDefinition current = stateChart.getState(currentState);
            if (current != null && current.getExitAction() != null) {
                executeAction(current.getExitAction());
            }

            currentState = transition.getTo();

            // Any state change cancels a pending recovery timer so a stale
            // timer can never fire after the engine has already left ERROR.
            // Entering ERROR then arms a fresh one.
            cancelRecoveryTimer();
            if ("RUNNING".equals(lifecycle) && "ERROR".equals(currentState)) {
                armRecoveryTimer();
            }

            if (dbStorageService != null) {
                dbStorageService.storeStateTransition(
                    current != null ? current.getName() : "UNKNOWN",
                    currentState,
                    event);
            }

            if (transition.getAction() != null) {
                executeAction(transition.getAction());
            }

            StateDefinition next = stateChart.getState(currentState);
            if (next != null && next.getEntryAction() != null) {
                executeAction(next.getEntryAction());
            }

            log.info("[StateChartExecutor] Now in state: {}", currentState);
            return;
        }

        log.warn("[StateChartExecutor] No transition found for event '{}' in state '{}'",
            event, currentState);
    }

    // ─────────────────────────────────────────
    // AUTOMATIC RECOVERY TIMER FOR THE ERROR STATE
    // ─────────────────────────────────────────

    /**
     * Arms a single use timer that fires RECOVERY_TIMEOUT after
     * recoveryTimeoutMs, letting the state chart carry the engine out of
     * ERROR (from T10_ERROR_RECOVERY to INITIALIZING). When the timer
     * expires it checks again that the engine is still in ERROR before
     * firing, so a race with a manual transition cannot disturb a
     * healthy state. A timeout of zero or less disables automatic
     * recovery.
     */
    private void armRecoveryTimer() {
        if (recoveryTimeoutMs <= 0) {
            log.info("[StateChartExecutor] ERROR auto-recovery disabled (recoveryTimeoutMs={})",
                recoveryTimeoutMs);
            return;
        }
        log.warn("[StateChartExecutor] Entered ERROR — auto-recovery in {} ms", recoveryTimeoutMs);
        recoveryTask = recoveryScheduler.schedule(() -> {
            // Guard against a stale fire: only recover if still in ERROR.
            synchronized (this) {
                if ("RUNNING".equals(lifecycle) && "ERROR".equals(currentState)) {
                    log.info("[StateChartExecutor] Recovery timeout elapsed — firing RECOVERY_TIMEOUT");
                    fireEvent("RECOVERY_TIMEOUT");
                }
            }
        }, recoveryTimeoutMs, TimeUnit.MILLISECONDS);
    }

    /** Cancels a pending recovery timer, if one is armed. */
    private void cancelRecoveryTimer() {
        if (recoveryTask != null && !recoveryTask.isDone()) {
            recoveryTask.cancel(false);
            log.info("[StateChartExecutor] Recovery timer cancelled (left ERROR before timeout)");
        }
        recoveryTask = null;
    }

    /** Stops the recovery scheduler cleanly on application shutdown. */
    @javax.annotation.PreDestroy
    public void stopRecoveryScheduler() {
        recoveryScheduler.shutdownNow();
    }

    // ─────────────────────────────────────────
    // ACTION DISPATCHER
    // ─────────────────────────────────────────

    private void executeAction(StateAction action) {
        if (action == null || "NONE".equals(action.getActionType())) return;

        log.info("[StateChartExecutor] Action: {} | Component: {}",
            action.getActionType(), action.getComponent());

        switch (action.getActionType()) {
            case "GATEWAY_READ"        -> handleGatewayRead(action);
            case "AAS_WRITE"           -> handleAasWrite(action);
            case "AAS_SNAPSHOT"        -> handleAasSnapshot(action);
            case "RULE_EVALUATE"       -> handleRuleEvaluate(action);
            case "SERVICE_CALL"        -> handleServiceCall(action);
            case "FILE_EXTRACT"        -> log.info(
                "[StateChartExecutor] FILE_EXTRACT — handled at startup");
            case "FILE_REPACK"         -> log.info(
                "[StateChartExecutor] FILE_REPACK — import/export handled separately");
            case "GATEWAY_CONNECT"     -> log.info(
                "[StateChartExecutor] GATEWAY_CONNECT — mock: gateway connected");
            case "GATEWAY_DISCONNECT"  -> log.info(
                "[StateChartExecutor] GATEWAY_DISCONNECT — mock: gateway disconnected");
            case "SERVICE_REGISTER"    -> log.info(
                "[StateChartExecutor] SERVICE_REGISTER — mock: service registered");
            case "SERVICE_DEREGISTER"  -> log.info(
                "[StateChartExecutor] SERVICE_DEREGISTER — mock: service deregistered");
            default -> log.warn(
                "[StateChartExecutor] Unknown action type: {}", action.getActionType());
        }
    }

    // ─────────────────────────────────────────
    // ACTION HANDLERS
    // ─────────────────────────────────────────

    private void handleGatewayRead(StateAction action) {
        String submodelId = action.getParams().get("submodelId");
        String elementId  = action.getParams().get("elementId");
        log.info("[StateChartExecutor] GATEWAY_READ: {}.{} (mock — firing SYNC_COMPLETE)",
            submodelId, elementId);
        // The read completed, so the subsequent SYNC_COMPLETE transition's
        // guard "syncStatus == CONFIRMED" is now satisfied. A real gateway
        // integration would set this based on the actual read outcome.
        syncConfirmed = true;
        fireEvent("SYNC_COMPLETE");
    }

    private void handleAasWrite(StateAction action) {
        String submodelId  = action.getParams().get("submodelId");
        String description = action.getParams().get("description");
        log.info("[StateChartExecutor] AAS_WRITE: submodel={} desc={}",
            submodelId, description);
        // DB write is handled directly in onDataPush, so there is nothing
        // to do here.
    }

    private void handleAasSnapshot(StateAction action) {
        String submodelId = action.getParams().get("submodelId");
        log.info("[StateChartExecutor] AAS_SNAPSHOT: submodel={}", submodelId);
    }

    private void handleRuleEvaluate(StateAction action) {
        log.info("[StateChartExecutor] RULE_EVALUATE: checking {} rules",
            aasManager.getRules().size());

        actionQueue.clear();

        for (Rule rule : aasManager.getRules()) {
            if ("false".equals(rule.getAttribute("enabled"))) continue;
            // Resolve the single target element in advance if the rule
            // names one. Rules with several inputs (those using "inputs"
            // instead of a single targetElement) leave this null and
            // resolve their inputs inside the evaluator through the
            // context.
            String currentValue = null;
            if (rule.getTargetElement() != null) {
                currentValue = resolveValue(
                    rule.getTargetSubmodel(), rule.getTargetElement());
            }

            String inputs = rule.getAttribute("inputs");
            boolean multiInput = (inputs != null && !inputs.isBlank());

            log.info("[StateChartExecutor] Evaluating rule {}: condition={} target={} inputs={} threshold={} value={}",
                rule.getIdShort(), rule.getCondition(),
                rule.getTargetElement(), inputs, rule.getThreshold(), currentValue);

            // A rule with a single target that has no resolvable value
            // cannot be evaluated. A rule with several inputs is allowed
            // to proceed, since its inputs are resolved inside the
            // evaluator.
            if (currentValue == null && !multiInput) {
                log.warn("[StateChartExecutor] No value found for {}.{} — skipping rule",
                    rule.getTargetSubmodel(), rule.getTargetElement());
                continue;
            }

            boolean conditionMet = evaluateCondition(rule, currentValue);

            if (conditionMet) {
                long cooldownMs = rule.getCooldownMs();
                Long lastTriggeredAt = ruleLastTriggeredAt.get(ruleRuntimeKey(rule));
                long now = clock.getAsLong();
                if (cooldownMs > 0 && lastTriggeredAt != null) {
                    long elapsed = Math.max(0L, now - lastTriggeredAt);
                    if (elapsed < cooldownMs) {
                        log.debug("[StateChartExecutor] Condition still met for rule {} but cooldown "
                            + "is active ({} ms remaining) — skipping dispatch",
                            rule.getIdShort(), cooldownMs - elapsed);
                        continue;
                    }
                }
                log.info("[StateChartExecutor] Condition MET for rule: {} → {}",
                    rule.getIdShort(), rule.getAction());
                actionQueue.add(rule);
            } else {
                log.info("[StateChartExecutor] Condition NOT met for rule: {}",
                    rule.getIdShort());
            }
        }

        if (actionQueue.isEmpty()) {
            log.info("[StateChartExecutor] No conditions met → NO_CONDITION_MET");
            fireEvent("NO_CONDITION_MET");
        } else {
            log.info("[StateChartExecutor] {} condition(s) met → CONDITION_MET",
                actionQueue.size());
            // Only the head of actionQueue is ever serviced this cycle
            // (see handleServiceCall and onServiceResult, which peek but
            // never poll). If more than one rule matched, the rest are
            // left in the queue until the next evaluation clears it. This
            // is logged here so the gap is visible instead of silently
            // disappearing.
            if (actionQueue.size() > 1) {
                List<String> skipped = actionQueue.stream()
                    .skip(1)
                    .map(Rule::getIdShort)
                    .toList();
                log.warn("[StateChartExecutor] {} rule(s) also matched this cycle but will NOT "
                    + "be serviced (only the first match is triggered): {}",
                    skipped.size(), skipped);
            }
            fireEvent("CONDITION_MET");
        }
    }

    /**
     * Sends a rule's condition to the matching registered evaluator and
     * returns whether the condition is met.
     *
     * <p><b>Extension point for new condition types.</b> This method is a
     * plain dispatch mechanism. It looks up the condition name (for
     * example {@code "greater_than"} or {@code "wear_average"}) in the
     * {@link #conditionEvaluators} registry and delegates to the
     * matching {@link ConditionEvaluator}. Adding a new condition type
     * only requires registering an evaluator in
     * {@link #registerConditionEvaluators()} (or through the public
     * {@link #registerCondition(String, ConditionEvaluator)} method).
     * No change to this method or to the evaluation loop is needed.
     *
     * <p><b>Support for several inputs.</b> The evaluator receives an
     * {@link EvaluationContext} that carries the rule, the primary
     * sensor value, and a {@link ValueResolver} function. Evaluators
     * that need values from multiple elements (for example
     * {@code wear_average}, which reads three axis loads) call
     * {@link EvaluationContext#resolve(String)} to look up each
     * additional input by element name. The resolver checks the
     * database first and falls back to the AAS model, so an evaluator
     * with several inputs sees the same data as one with a single input,
     * without any special plumbing.
     *
     * <p><b>Failure mode.</b> If no evaluator is registered for a
     * condition name, or if the evaluator throws (for example a
     * {@code NumberFormatException} on a value that is not numeric),
     * the condition is treated as not met and the rule is skipped for
     * this evaluation cycle. This is a safe default that avoids
     * triggering a service on data that could not be validated.
     *
     * @param rule         the rule being evaluated
     * @param currentValue the latest value of the rule's target element
     * @return true if the condition is met and the rule should trigger
     */
    private boolean evaluateCondition(Rule rule, String currentValue) {
        String condition = rule.getCondition();
        if (condition == null) {
            log.warn("[StateChartExecutor] Rule {} has no condition — skipping",
                rule.getIdShort());
            return false;
        }
        ConditionEvaluator evaluator = conditionEvaluators.get(condition);
        if (evaluator == null) {
            log.warn("[StateChartExecutor] No evaluator registered for condition type: {} "
                + "— add one in registerConditionEvaluators()", condition);
            return false;
        }
        try {
            EvaluationContext ctx =
                new EvaluationContext(rule, currentValue, this::resolveValue);
            return evaluator.evaluate(ctx);
        } catch (Exception e) {
            log.error("[StateChartExecutor] Condition '{}' evaluation failed for rule {}: {}",
                condition, rule.getIdShort(), e.getMessage());
            return false;
        }
    }

    /**
     * Resolves the latest value of an element. The database is checked
     * first, then the AAS model kept in memory. This is the same lookup
     * the evaluation loop uses for a rule's single target element,
     * exposed as a ValueResolver so an evaluator with several inputs can
     * pull each of its declared inputs the same way.
     */
    private String resolveValue(String submodelId, String elementId) {
        String value = null;
        if (dbStorageService != null) {
            value = dbStorageService.readLatestValue(submodelId, elementId);
        }
        if (value == null) {
            value = aasManager.readProperty(submodelId, elementId);
        }
        return value;
    }

    /**
     * Registers a condition evaluator under a name, making it available
     * to rules whose "condition" property matches. Exposed so that new
     * operators can be added without modifying this class. Calling it
     * again with an existing name overrides that operator.
     */
    public void registerCondition(String name, ConditionEvaluator evaluator) {
        conditionEvaluators.put(name, evaluator);
        log.info("[StateChartExecutor] Registered condition evaluator: {}", name);
    }

    /**
     * Populates the registry with the operators that come with the
     * engine. Adding a new operator only takes one
     * registerCondition(...) line here (or a call to registerCondition
     * from outside). There is no switch statement to edit, and the
     * existing operators stay untouched.
     *
     * Two kinds of operator are shown here. The first kind is
     * comparisons with a single input (greater_than, less_than, equals,
     * out_of_bounds) that read the rule's value, resolved earlier, and
     * its threshold. The second kind is an operator that computes from
     * several inputs (wear_average), which reads several element values
     * named by the rule and computes over them. This second kind is the
     * pattern to follow for conditions that must be tailored to a
     * machine: the rule lists whichever inputs that machine exposes, and
     * the formula lives entirely inside the evaluator.
     *
     * Called once during initialization (see initializeEngine()).
     */
    private void registerConditionEvaluators() {
        registerCondition("greater_than", ctx ->
            Double.parseDouble(ctx.getCurrentValue()) > Double.parseDouble(ctx.getThreshold()));

        registerCondition("less_than", ctx ->
            Double.parseDouble(ctx.getCurrentValue()) < Double.parseDouble(ctx.getThreshold()));

        registerCondition("equals", ctx ->
            ctx.getCurrentValue().trim().equals(ctx.getThreshold().trim()));

        registerCondition("out_of_bounds", ctx -> {
            String[] bounds = ctx.getThreshold().split(",");
            String[] values = ctx.getCurrentValue().split(",");
            if (bounds.length < 6 || values.length < 3) {
                log.warn("[StateChartExecutor] out_of_bounds: invalid format");
                return false;
            }
            double x = Double.parseDouble(values[0].trim());
            double y = Double.parseDouble(values[1].trim());
            double z = Double.parseDouble(values[2].trim());
            double xMin = Double.parseDouble(bounds[0].trim());
            double xMax = Double.parseDouble(bounds[1].trim());
            double yMin = Double.parseDouble(bounds[2].trim());
            double yMax = Double.parseDouble(bounds[3].trim());
            double zMin = Double.parseDouble(bounds[4].trim());
            double zMax = Double.parseDouble(bounds[5].trim());
            boolean xOut = x < xMin || x > xMax;
            boolean yOut = y < yMin || y > yMax;
            boolean zOut = z < zMin || z > zMax;
            return xOut || yOut || zOut;
        });

        // Operator that computes from several inputs:
        // Demonstrates a condition computed from several element values,
        // as raised in supervisor review (an estimate of wear over three
        // axes).
        //
        // The rule declares its inputs in the AASX, for example:
        //   targetSubmodel = CPS_SM
        //   inputs         = XAxisLoad,YAxisLoad,ZAxisLoad
        //   condition      = wear_average
        //   threshold      = 15.0
        //   action         = maintenance-alert-service
        //
        // The formula below is one interpretation of the example that
        // was discussed: pairwise absolute differences between the
        // axes, scaled and then averaged, compared against the
        // threshold. The point is the location of the code: the whole
        // calculation lives here and can be replaced with the machine's
        // real wear formula without touching any other part of the
        // engine. Only the body needs to change. The registration,
        // dispatch and rule plumbing stay the same.
        registerCondition("wear_average", ctx -> {
            String inputsCsv = ctx.getRule().getAttribute("inputs");
            if (inputsCsv == null || inputsCsv.isBlank()) {
                log.warn("[StateChartExecutor] wear_average: rule has no 'inputs' property");
                return false;
            }
            String[] elementIds = inputsCsv.split(",");
            if (elementIds.length < 3) {
                log.warn("[StateChartExecutor] wear_average: expected at least 3 inputs, got {}",
                    elementIds.length);
                return false;
            }

            // Resolve each declared input to a number through the context.
            double[] axis = new double[elementIds.length];
            for (int i = 0; i < elementIds.length; i++) {
                String raw = ctx.resolve(elementIds[i].trim());
                if (raw == null) {
                    log.warn("[StateChartExecutor] wear_average: no value for input {}",
                        elementIds[i].trim());
                    return false;
                }
                axis[i] = Double.parseDouble(raw.trim());
            }

            // ==== formula specific to this machine (replace with the real one) ====
            // Pairwise absolute differences, scaled by 100, then averaged.
            double a = axis[0], b = axis[1], c = axis[2];
            double dAB = Math.abs(a - b) / 100.0;
            double dBC = Math.abs(b - c) / 100.0;
            double dAC = Math.abs(a - c) / 100.0;
            double wear = (dAB + dBC + dAC) / 3.0;
            // ================================================================

            double threshold = Double.parseDouble(ctx.getThreshold());
            log.info("[StateChartExecutor] wear_average: computed wear={} threshold={}",
                wear, threshold);
            return wear > threshold;
        });
    }

    /**
     * Builds the trigger command for the Service Manager and posts it.
     *
     * <p>Knowledge about service execution is split between registration
     * time and trigger time. Services are registered with the Service
     * Manager in advance, together with their execution artifacts and
     * technology, so this method only needs to send what registration
     * cannot know: the service identifiers named by the triggering rule,
     * and the dynamic context of the situation. The dynamic arguments
     * combine context supplied by the engine (target submodel and
     * element, callback address) with every property of the triggering
     * rule whose name starts with {@code arg.}. The rule model is not
     * tied to specific fields, so it forwards these automatically
     * without any code change here.
     *
     * <p>A rejected request, or a Service Manager that cannot be
     * reached, is converted into a synthetic FAILED result for each
     * affected service (see {@link #fireGroupFailure}), so all failure
     * handling funnels through the retry path in
     * {@link #onServiceResult}.
     *
     * @param action the state chart action that triggered the call, or
     *               null when this call is invoked again during a
     *               retry, in which case the triggering rule is the one
     *               captured in inFlightRule when this attempt was
     *               first dispatched, not whatever is currently at the
     *               head of the action queue
     */
    private void handleServiceCall(StateAction action) {
        if (!"RUNNING".equals(lifecycle)) return;
        // On a fresh dispatch, read the triggering rule from the queue as
        // before. On a retry (action == null), use the rule captured when
        // this attempt started instead of reading actionQueue again,
        // since actionQueue may have been rebuilt by a concurrent
        // RULE_EVALUATE in the meantime.
        Rule triggeredRule = (action == null) ? inFlightRule : actionQueue.peek();
        List<String> serviceIds;
        if (triggeredRule != null) {
            serviceIds = triggeredRule.getActions();
        } else if (action != null && action.getParams().get("serviceId") != null) {
            serviceIds = List.of(action.getParams().get("serviceId"));
        } else {
            serviceIds = List.of();
        }

        if (serviceIds.isEmpty()) {
            log.warn("[StateChartExecutor] SERVICE_CALL with no resolvable serviceId — skipping");
            return;
        }

        inFlightRule = triggeredRule;
        // A new attempt, whether a fresh dispatch or a retry, starts
        // here. Whatever ids were expected for a previous attempt are no
        // longer relevant, and this attempt's outcome has not been
        // decided yet.
        expectedExecutionIds.clear();
        attemptConcluded = false;

        String submodelId = triggeredRule != null
            && triggeredRule.getTargetSubmodel() != null
            && !triggeredRule.getTargetSubmodel().isBlank()
                ? triggeredRule.getTargetSubmodel()
                : dataSubmodelIdShort;
        String elementId = triggeredRule != null
            && triggeredRule.getTargetElement() != null
            && !triggeredRule.getTargetElement().isBlank()
                ? triggeredRule.getTargetElement()
                : (lastPushedElementId != null ? lastPushedElementId : "unknown");

        Map<String, String> dynamicArgs = serviceArguments(triggeredRule, elementId);

        log.info("[StateChartExecutor] SERVICE_CALL: triggering {} service(s) {} via {}",
            serviceIds.size(), serviceIds, serviceExecutionUrl);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serviceIds",   serviceIds);
        body.put("dynamicArgs",  dynamicArgs);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> resp = restTemplate.postForEntity(
                serviceExecutionUrl, entity, String.class);
            if (resp.getStatusCode().is2xxSuccessful()) {
                if (triggeredRule != null) {
                    ruleLastTriggeredAt.put(
                        ruleRuntimeKey(triggeredRule), clock.getAsLong());
                }
                log.info("[StateChartExecutor] ServiceManager accepted trigger for {}",
                    serviceIds);
                trackExpectedExecutionIds(resp.getBody(), serviceIds);
                if (lifecycleManaged && expectedExecutionIds.isEmpty() && !attemptConcluded)
                    fireGroupFailure(serviceIds, "Service Manager did not return execution IDs");
            } else {
                fireGroupFailure(serviceIds,
                    "ServiceManager rejected: " + resp.getStatusCode());
            }
        } catch (Exception e) {
            fireGroupFailure(serviceIds,
                "ServiceManager unreachable: " + e.getMessage());
        }
    }

    private String ruleRuntimeKey(Rule rule) {
        if (rule.getIdShort() != null && !rule.getIdShort().isBlank()) {
            return rule.getIdShort();
        }
        return String.join("|",
            Objects.toString(rule.getTargetSubmodel(), ""),
            Objects.toString(rule.getTargetElement(), ""),
            Objects.toString(rule.getCondition(), ""),
            Objects.toString(rule.getAction(), ""));
    }

    /**
     * Parses the response body of POST /services/trigger, shaped like
     * {@code { "triggered": [ { "executionId": "...", "serviceId": "...",
     * "status": "RUNNING" } ], "message": "..." } }, and records each
     * executionId in expectedExecutionIds, so that onServiceResult() can
     * recognize which incoming results belong to this attempt.
     *
     * <p>If the body cannot be parsed, or it carries no executionIds (for
     * example an older deployment of the Runtime component
     * that has not rolled this out yet), expectedExecutionIds is left
     * empty for this attempt, and onServiceResult() falls back to
     * accepting any result whose executionId is null. This matches the
     * behavior from before this correlation check existed, which simply
     * trusted whatever was next in line.
     */
    private void trackExpectedExecutionIds(String responseBody, List<String> serviceIds) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode triggered = root.path("triggered");
            if (triggered.isArray()) {
                for (JsonNode entry : triggered) {
                    String executionId = entry.path("executionId").asText(null);
                    if (executionId != null && !executionId.isBlank()) {
                        expectedExecutionIds.add(executionId);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[StateChartExecutor] Could not parse trigger response for executionId(s): {} "
                + "— result correlation will be skipped for this attempt", e.getMessage());
            return;
        }
        if (expectedExecutionIds.isEmpty()) {
            log.warn("[StateChartExecutor] Trigger response carried no executionId(s) for {} — "
                + "result correlation will be skipped for this attempt", serviceIds);
        } else {
            log.info("[StateChartExecutor] Tracking executionId(s) for this attempt: {}",
                expectedExecutionIds);
        }
    }

    private void fireGroupFailure(List<String> serviceIds, String message) {
        for (String sid : serviceIds) {
            ExecutionResponseDTO failed = new ExecutionResponseDTO();
            if (lifecycleManaged) {
                String id = "local-failure-" + UUID.randomUUID();
                failed.setExecutionId(id); expectedExecutionIds.add(id);
            }
            failed.setServiceId(sid);
            failed.setStatus(ExecutionStatus.FAILED);
            failed.setSuccess(false);
            failed.setOutputData(message);
            onServiceResult(failed);
        }
    }

    // ─────────────────────────────────────────
    // GUARD EVALUATION
    // ─────────────────────────────────────────

    private boolean evaluateGuard(String guard) {
        if (guard == null || guard.equals("NONE")) return true;

        return switch (guard) {
            case "rulesLoaded == true"            -> !aasManager.getRules().isEmpty();
            case "actionQueue.isEmpty() == false" -> !actionQueue.isEmpty();
            case "actionQueue.isEmpty() == true"  -> actionQueue.isEmpty();
            case "syncStatus == CONFIRMED"        -> syncConfirmed;
            case "response.status == COMPLETED"   ->
                lastResponseStatus == ExecutionStatus.COMPLETED;
            default -> {
                log.warn("[StateChartExecutor] Unknown guard: '{}' — defaulting true", guard);
                yield true;
            }
        };
    }


    private void seedServiceDockerfiles() {
        if (storageService == null) {
            log.warn("[StateChartExecutor] No storage service — skipping Dockerfile seeding");
            return;
        }

        Map<String, String> dockerfiles = Map.of(
            "services/cooling-service/Dockerfile",
                "FROM alpine:latest\nCMD echo \"70.0\"",
            "services/geometry-correction-service/Dockerfile",
                "FROM alpine:latest\nCMD echo \"0.0,0.0,0.0\"",
            "services/energy-monitoring-service/Dockerfile",
                "FROM alpine:latest\nCMD echo \"25.0\""
        );

        for (Map.Entry<String, String> entry : dockerfiles.entrySet()) {
            if (!storageService.exists(entry.getKey())) {
                storageService.store(entry.getKey(), entry.getValue());
                log.info("[StateChartExecutor] Seeded Dockerfile: {}", entry.getKey());
            }
        }
    }
}
