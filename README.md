# LABraDT Engine — AAS-Based Rule Execution Engine

**Component:** AAS Engine Layer  
**Port:** 8080  
**Overview of all LABraDT projects and the architecture diagram:** [README.md](https://github.com/LABraDT-Framework)

The Engine provides the central logic for executing rules and interacting with AAS-based data models. Its Rule Engine integrates the AAS submodels `CPS_SM` (providing live values) and `Rules_SM` (containing rule definitions). The `ConditionEvaluator` interface enables the evaluation of rule conditions using configurable operators. The Engine communicates with the Gateway to retrieve live machine values and write updated values back.

The Engine is not tied to any specific machine. All behavior — states, transitions, rules, and services — is defined in the AASX file. Deploying the engine on a different machine requires zero Java code changes and only a new AASX file.

---

## Quick Start

**Prerequisites:** Java 21+, Maven 3.8+, Git Bash or any terminal. Docker Desktop is required only if the Runtime component runs container-based services.

### 1. Build

Clone the **engine** project into a folder named `engine`, then build it:

```bash
cd engine
mvn clean install -DskipTests
```

### 2. Configure Connectivity

Edit `src/main/resources/application.properties`:

```properties
server.port=8080
gateway.url=http://localhost:8085/api/submodel/stream
gateway.write.url=http://localhost:8085
gateway.write.connect-timeout-ms=3000
gateway.write.read-timeout-ms=10000
service.execution.url=http://localhost:8082/services/trigger
engine.callback.url=http://localhost:8080/engine/result
engine.aasx.path=src/main/resources/MachineTool.aasx
```

`engine.aasx.path` is read from disk at startup (not a classpath resource), so this is what determines which AASX file this instance governs. Overriding it per Spring profile is what makes a second instance govern a different machine (see [ADDING_A_NEW_MACHINE.md](ADDING_A_NEW_MACHINE.md)).

Optional tuning properties:

```properties
engine.error.recoveryTimeoutMs=30000     # ms before automatic recovery from ERROR (default 30000)
engine.service.retryDelayMs=0            # ms between service retries (default 0)
engine.data.normalizeDecimalComma=false  # convert "85,0" to "85.0" before parsing (default false)
engine.rules.submodel.idShort=Rules_SM          # idShort of the rules container (default Rules_SM)
engine.statechart.submodel.idShort=StateChart_SM # idShort of the state chart container (default StateChart_SM)
gateway.submodelId=CPS_SM               # idShort of the live data submodel (default CPS_SM)
```

The shipped `MachineTool.aasx` spells these idShorts `Rules-SM`, `StateChart-SM` and `CPS-SM`. The Engine renames them to `Rules_SM`, `StateChart_SM` and `CPS_SM` when it imports the AASX (`AASXImporter.java:43`), so both spellings load; the code, the configuration and the REST API use the underscore form.

### 3. Start the Engine

**Terminal 1 — Runtime Component (services project):**
```bash
cd services
mvn clean install -DskipTests
cd service-manager-app
mvn spring-boot:run
```

**Terminal 2 — Gateway (gateway project):**
```bash
cd gateway
mvn clean install -DskipTests
mvn -pl dt-gateway-app spring-boot:run
```

**Terminal 3 — Engine:**
```bash
cd engine
mvn spring-boot:run
```

Or run as a jar (start from the engine project folder so relative paths resolve correctly):
```bash
mvn clean package -DskipTests
java -jar target/machinetool-dt-1.0-SNAPSHOT.jar
```

The model initializes automatically and the Gateway listener stays online. Business execution defaults to **STOPPED**: telemetry updates the live model, but automatic rule dispatch waits for an explicit start:

```bash
curl -X POST http://localhost:8080/engine/runtime/validate
curl -X POST http://localhost:8080/engine/runtime/start
curl -X POST http://localhost:8080/engine/runtime/stop
```

### 4. Open the Monitor Dashboard

```
http://localhost:8080/monitor.html
```

The page calls the engine at its own address (`window.location.origin`), so it also works on another port or host. Open it through the engine, not as a local file.

---

## Tutorial

A complete first session, from zero to the Engine reacting to a real rule firing and a correction reaching the machine.

1. **Build and start dependencies.** Follow step 3 above to bring up the Runtime Component and Gateway.

2. **Start the Engine** in a third terminal and confirm it is alive:
   ```bash
   mvn spring-boot:run
   curl http://localhost:8080/engine/state
   ```

3. **Push a value that does not fire a rule** and watch it pass through:
   ```bash
   curl -X POST http://localhost:8080/engine/push \
     -H "Content-Type: application/json" \
     -d '{"submodelId":"CPS_SM","elementId":"Temperature","value":"50.0"}'
   curl http://localhost:8080/engine/history
   ```
   The history should show `STABLE → SYNCHRONIZING → DECIDING → STABLE` (a `NO_CONDITION_MET` cycle) with nothing dispatched. For a stream of changing values that crosses all three default rules, run `python3 demo_bot.py` instead (needs `pip install requests`; set `LABRADT_ENGINE_URL` for a non-default engine address).

4. **Push a value that fires a rule:**
   ```bash
   curl -X POST http://localhost:8080/engine/push \
     -H "Content-Type: application/json" \
     -d '{"submodelId":"CPS_SM","elementId":"Temperature","value":"90.0"}'
   ```
   This crosses Rule_1's threshold. The engine moves to TRIGGERING and calls the Runtime Component. Confirm with `curl http://localhost:8080/engine/history`.

5. **See the correction land.** Once the corrective service completes, the Engine validates the result against the inverse of the rule that fired, then writes it back to the Gateway. Watch the Engine log for `Writing correction back to gateway`.

6. **See a stale result get dropped.** The Engine only ever acts on a result whose `executionId` matches the attempt it currently considers live:
   ```bash
   curl -X POST http://localhost:8080/engine/result \
     -H "Content-Type: application/json" \
     -d '{"serviceId":"cooling-service","executionId":"not-the-real-one","status":"COMPLETED","success":true,"outputData":"70.0"}'
   ```
   The Engine logs this as stale and drops it without changing state.

7. **Onboard a machine.** [ADDING_A_NEW_MACHINE.md](ADDING_A_NEW_MACHINE.md) walks through building a new AASX from scratch and running a second instance side by side.

---

## How It Works

### State Machine

The engine runs a state machine defined entirely in the AASX file. The default lifecycle is:

```
UNINITIALIZED --STARTUP--> INITIALIZING --READY--> STABLE
STABLE --DATA_PUSH_RECEIVED--> SYNCHRONIZING --SYNC_COMPLETE--> DECIDING
DECIDING --CONDITION_MET--> TRIGGERING --SERVICE_COMPLETE--> STABLE
DECIDING --NO_CONDITION_MET--> STABLE
TRIGGERING --RETRY_EXCEEDED--> ERROR --RECOVERY_TIMEOUT--> STABLE
```

This is a snapshot of the states and transitions declared in the bundled AASX, not something hardcoded in Java. `StateChartLoader` parses whatever `StateChart_SM` actually contains, so editing the AASX changes this picture directly.

### Rule Evaluation Cycle

Every time the gateway pushes a sensor value, the engine:

1. Validates the incoming value (rejects values that are non-numeric, empty or whitespace; quarantines them in the database with a reason tag)
2. Stores valid values in the BaSyx model kept in memory, in file storage and in the H2 database
3. Evaluates all rules against current sensor values
4. If a rule fires, transitions to TRIGGERING and calls the corresponding service through the Runtime Component, tracking the `executionId`(s) the Runtime Component returns
5. Retries on failure up to `engine.retry.max` times (default 3) with an optional delay; each retry gets its own `executionId` so a late response from a previous attempt cannot be mistaken for the current one
6. On receiving a COMPLETED callback, confirms its `executionId` still belongs to the current attempt, then validates the corrected value against the inverse of the triggering rule (write back validation) before writing it to the gateway
7. If the correction fails validation or retries are exhausted, enters ERROR and arms the automatic recovery timer
8. Returns to STABLE after a successful write back or after the recovery timeout fires

### Boundary Validation

| Input | Outcome |
|-------|---------|
| Valid numeric string (`"80.0"`, `"7.5e1"`) | Accepted, stored, evaluated |
| Non-numeric string (`"hot"`) | Rejected, tagged `[REJECTED:non_numeric]`, quarantined |
| Empty or whitespace | Rejected, tagged `[REJECTED:empty]`, quarantined |
| Comma decimal separator (`"85,0"`) | Rejected by default; accepted if `normalizeDecimalComma=true` |

The rejection counter is exposed through `/engine/state` and visible on the dashboard.

### Write Back Validation

After a service returns a corrected value, the engine checks it against the inverse of the triggering rule:

| Triggering condition | Corrected value must satisfy |
|----------------------|------------------------------|
| `greater_than T` | value <= T |
| `less_than T` | value >= T |
| `out_of_bounds` | value back inside declared bounds |
| `equals` or multi-input condition | accepted as is |

If the corrected value fails this check, the write back is skipped and the engine enters ERROR.

### Is It Static?

**The contracts are static.** The REST API surface (`AASController`), the two inbound interfaces (`IDataReceiver`, `IEngineStatusListener`), and the shapes of `ExecutionResponseDTO` and `ExecutionStatus` do not change between machines or between runs. That stability is what lets the Gateway and the Runtime Component be built once and reused against any machine this Engine governs.

**The behavior is not static.** Rules, thresholds, target elements, the state machine itself, and condition operators are all loaded from the AASX at startup. Adding, removing or editing a rule never touches Java. A SHA-256 hash check reimports them automatically if the AASX changed since the last run.

---

## Extension Points

There are three independent extension points. Each is isolated so changing one does not require touching the others.

### 1. Adding a New Condition Operator

**Where:** `StateChartExecutor.java`, method `registerConditionEvaluators()`

**When:** you need a condition type that does not exist yet, such as `rate_of_change` or `below_average`.

A `ConditionEvaluator` is a functional interface with one method, `evaluate(EvaluationContext ctx)`, registered under a name in a plain `Map<String, ConditionEvaluator>`. The registry is open for new entries at any time through the public `registerCondition(name, evaluator)` method.

```java
registerCondition("rate_of_change", ctx -> {
    double current = Double.parseDouble(ctx.getCurrentValue());
    String prev    = ctx.resolve("PreviousReading");
    if (prev == null) return false;
    double rate    = Math.abs(current - Double.parseDouble(prev));
    return rate > Double.parseDouble(ctx.getThreshold());
});
```

Then declare `condition = rate_of_change` in the AASX rule. Nothing else changes. If the new condition has a meaningful inverse, add a corresponding case to `isCorrectionWithinBounds()` in `StateChartExecutor.java`.

### 2. Adding a New Rule Property

**Where:** the AASX XML only. No Java change needed.

A rule is not a fixed set of typed fields. `Rule` stores every property it was parsed with in a plain attribute map keyed by idShort, and `AASManager.parseRule()` copies every `<property>` it finds into that map. Add a new `<property>` element to the rule in the AASX; it becomes immediately available through `ctx.getRule().getAttribute("yourPropertyName")`.

To pass it as an argument to the service, prefix it with `arg.` in the AASX:

```xml
<property>
  <idShort>arg.targetTemperature</idShort>
  <value>65.0</value>
</property>
```

This arrives in the dynamic arguments sent to the Runtime Component as `"targetTemperature": "65.0"` automatically.

### 3. Adding a New Field to the Service Trigger Contract

**Where:** `StateChartExecutor.handleServiceCall()` and the Runtime Component.

`dynamicArgs` is assembled from context the engine already knows plus every rule property prefixed with `arg.`, so most new arguments only need a new `arg.` property in the AASX. If the value cannot come from a rule property, add it directly in `handleServiceCall()`:

```java
dynamicArgs.put("priority", "high");
```

Then coordinate with whoever owns the Runtime Component; it must read and act on the new argument.

---

## Adapting to a New Machine

Only the AASX file changes when deploying the engine on a different machine. Each rule in `Rules_SM` follows this structure:

```xml
<submodelElementCollection>
  <idShort>Rule_1</idShort>
  <value>
    <property><idShort>targetSubmodel</idShort><value>CPS_SM</value></property>
    <property><idShort>targetElement</idShort><value>YourSensorName</value></property>
    <property><idShort>condition</idShort><value>greater_than</value></property>
    <property><idShort>threshold</idShort><value>75.0</value></property>
    <property><idShort>action</idShort><value>your-service-name</value></property>
    <property><idShort>cooldownMs</idShort><value>30000</value></property>
    <property><idShort>description</idShort><value>Description for a person reading the AASX</value></property>
  </value>
</submodelElementCollection>
```

A rule's `action` can name more than one service, separated by commas (e.g. `cooling-service,energy-monitoring-service`). Only the first terminal result decides the write back outcome.

For conditions with several inputs such as `wear_average`, add an `inputs` property:

```xml
<property><idShort>condition</idShort><value>wear_average</value></property>
<property><idShort>inputs</idShort><value>SpindleLoad,ToolWearIndex,CuttingTemperature</value></property>
<property><idShort>threshold</idShort><value>70.0</value></property>
```

For a full walkthrough, see [ADDING_A_NEW_MACHINE.md](ADDING_A_NEW_MACHINE.md).

### Editing the AASX File

**Unzip:**
```bash
cd src/main/resources
python -c "import zipfile; zipfile.ZipFile('MachineTool.aasx').extractall('aasx_edit')"
```

Edit `src/main/resources/aasx_edit/aasx/MachineTool-AAS/MachineTool-AAS.aas.xml`, then rezip:

```bash
python -c "
import zipfile, shutil
src = 'MachineTool.aasx'; tmp = 'MachineTool.aasx.tmp'
edited = 'aasx_edit/aasx/MachineTool-AAS/MachineTool-AAS.aas.xml'
new = open(edited, 'rb').read()
zin = zipfile.ZipFile(src, 'r'); zout = zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED)
for item in zin.infolist():
    data = new if item.filename.replace('\\\\', '/').lower().endswith('machinetool-aas.aas.xml') else zin.read(item.filename)
    zout.writestr(item, data)
zin.close(); zout.close(); shutil.move(tmp, src); print('Done.')
"
```

Clean up with `rm -rf aasx_edit/`.

---

## Default CNC Milling Machine Configuration

The included `MachineTool.aasx` is preconfigured for an ISW Stuttgart CNC milling machine:

| Rule | Element | Condition | Threshold | Service |
|------|---------|-----------|-----------|---------|
| Rule_1 | Temperature | greater_than | 85.0 | cooling-service |
| Rule_2 | XAxisPos | out_of_bounds | -0.45,0.45,-0.45,0.45,-0.45,0.45 | geometry-correction-service |
| Rule_3 | XAxisLoad | greater_than | 30.0 | energy-monitoring-service |

Service Dockerfiles for these rules are seeded automatically at startup. No manual file placement is required for the default configuration.

---

## REST API Reference

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/engine/startup` | Manually reinitialize the engine and reload the AASX |
| `GET` | `/engine/shutdown` | Fire SHUTDOWN_SIGNAL and terminate gracefully |
| `GET` | `/engine/state` | Current state, loaded rule count and rejection counter |
| `GET` | `/engine/history` | Last 10 state transitions |
| `GET` | `/engine/submodel-data` | Last 8 sensor data writes |
| `GET` | `/engine/storage` | List the top-level contents of the storage folder |
| `GET` | `/engine/storage/{key}` | Retrieve a file from storage (used by the Runtime Component) |
| `POST` | `/engine/storage/upload` | Upload a file into storage (multipart form; returns the storage key) |
| `DELETE` | `/engine/storage/{key}` | Delete a stored file |
| `POST` | `/engine/push` | Push a sensor value as flat JSON (gateway or manual testing) |
| `POST` | `/engine/push/{submodelId}` | Push a value as a BaSyx SubmodelElement body |
| `POST` | `/engine/result` | Receive a service execution result (called by the Runtime Component) |
| `POST` | `/engine/rules/{id}/dry-run` | Evaluate a rule without side effects (requires STOPPED) |
| `POST` | `/engine/rules/{id}/test-trigger` | Execute services once if condition matches (requires STOPPED) |
| `GET` | `/engine/rule-tests/{testId}` | Poll independently correlated test results |

**Manual data push (testing without the gateway):**
```bash
curl -X POST http://localhost:8080/engine/push \
  -H "Content-Type: application/json" \
  -d '{"submodelId":"CPS_SM","elementId":"Temperature","value":"80.0"}'
```

**Simulate a service result (testing without the Runtime Component):**
```bash
curl -X POST http://localhost:8080/engine/result \
  -H "Content-Type: application/json" \
  -d '{"serviceId":"cooling-service","executionId":"test-1","status":"COMPLETED","success":true,"outputData":"72.0"}'
```

---

## Integration Contracts

This section is a quick reference. For the full contract in each direction, a connect-and-run tutorial, and troubleshooting specific to that boundary, see [CONNECTING_TO_THE_GATEWAY.md](CONNECTING_TO_THE_GATEWAY.md) and [CONNECTING_TO_SERVICE_EXECUTION.md](CONNECTING_TO_SERVICE_EXECUTION.md).

### Gateway to Engine (SSE Stream Format)

Three event types: `snapshot` (on connect, one per element), `update` (one changed value) and `keyframe` (periodic re-send). Each event carries one AAS Property wrapped with its routing key `<machineId>.<idShort>`:

```
event: update
data: {"key":"cnc-line1.Temperature","property":{"modelType":"Property","idShort":"Temperature","valueType":"xs:double","value":"76.1","qualifiers":[{"type":"StatusCode","value":"OK"}]}}
```

Only `update` events evaluate rules; `snapshot` and `keyframe` only refresh the model.

### Engine to Runtime Component (Trigger Request)

```json
{
  "serviceIds": ["cooling-service"],
  "dynamicArgs": {
    "submodelId": "CPS_SM",
    "elementId": "Temperature",
    "callbackUrl": "http://localhost:8080/engine/result"
  }
}
```

Posted to `service.execution.url` (`POST /services/trigger`). On success the Runtime Component answers with HTTP 202 and one `executionId` per triggered service.

### Runtime Component to Engine (Result Callback)

```json
{
  "serviceId": "cooling-service",
  "executionId": "3f9c2e1a-...",
  "status": "COMPLETED",
  "success": true,
  "outputData": "72.0"
}
```

`executionId` must match the value the Runtime Component returned when the service was triggered. A result whose `executionId` does not match the attempt currently running is logged and dropped. `status` values: `ACCEPTED` and `RUNNING` are in progress; `COMPLETED` is the only status that leads to a write back; any other terminal status triggers a retry.

---

## How to Run the Tests

```bash
mvn test
```

All engine tests run offline (no gateway, service manager, Docker or network). Coverage includes the state machine's rule evaluation and retry logic, the write back safety check, the `executionId` correlation fix, and the storage layer's path-escape protection.

---

## Plugins Folder and Storage

The Engine serves service artifacts (scripts, Dockerfiles) from its `storage/` folder. The Runtime Component fetches them at execution time:

```
GET http://localhost:8080/engine/storage/services/cooling-service/Dockerfile
```

`storage/` is runtime data (created on first start, ignored by `.gitignore`). The `data/` folder contains the H2 database (persists across restarts). The H2 console is available at `http://localhost:8080/h2-console` (JDBC URL: `jdbc:h2:file:./data/machinetool-db`).

---

## Where Is X?

| I am looking for | It is here |
|---|---|
| The state machine and the rule evaluation loop | `engine/StateChartExecutor.java` |
| The condition operator extension point | `engine/model/ConditionEvaluator.java` |
| The rule data model | `engine/model/Rule.java` |
| The state chart data model | `engine/model/StateChartDefinition.java` |
| Where the AASX is parsed into rules and the state chart | `engine/aas/AASManager.java` and `engine/aas/StateChartLoader.java` |
| The REST API, every endpoint | `engine/controller/AASController.java` |
| The storage abstraction | `shared/IStorageService.java` |
| The Gateway connection (both directions) | `engine/GatewayStreamClient.java` and `engine/GatewayWriteClient.java` |
| The write back safety check | `StateChartExecutor.isCorrectionWithinBounds()` |
| The executionId correlation logic | `StateChartExecutor.onServiceResult()` |
| The default machine configuration | `src/main/resources/MachineTool.aasx` |

All paths above are relative to `src/main/java/org/example/machinetool/`.

---

## Troubleshooting

| Problem | Cause | Fix |
|---------|-------|-----|
| `Entry not found in stream` | AASX repacked with backslash paths | Use the Python rezip command above |
| `Stream disconnected`, retrying every 5 seconds | Gateway not running | Start the Gateway on port 8085 first |
| Rules show `[?]` in logs | Element name mismatch | Confirm the element name matches what the gateway pushes |
| Write back failed | Gateway unreachable or wrong value format | Check port 8085 is running; ensure `outputData` is numeric |
| Rules not updated after an AASX edit | Old hash marker still cached | The engine detects the change automatically on next startup |
| Engine stuck in TRIGGERING | No COMPLETED callback received, or `executionId` mismatch | Check the Runtime Component terminal, or inject a test result with a matching `executionId` |
| Engine stuck in INITIALIZING after recovery | AASX missing or unreadable | Verify `MachineTool.aasx` exists in `src/main/resources/` |
| Data push returns STABLE but rule did not fire | Value below threshold or element name wrong | Check the logs for `No value found for CPS_SM.X` or `Condition NOT met` |

