# AAS Based Digital Twin Execution Engine

**Component:** AAS Engine Layer
**Port:** 8080

---

## What This Is

This is a generic execution engine for Digital Twins based on the Asset Administration Shell (AAS), not tied to any specific machine. It connects a physical machine's data stream to the containerized Runtime, driven entirely by a declarative AASX configuration file.

The engine monitors live sensor data, evaluates rules defined in the AAS and autonomously triggers corrective services when thresholds are breached, without any logic hardcoded for a specific machine.

> **Core thesis contribution:** all behavior (states, transitions, rules, services) is defined in the AASX file. Deploying the engine on a different machine requires zero Java code changes and only a new AASX file.

---

## System Architecture

```
Physical Machine
      │
      │  any protocol
      ▼
Gateway Layer (external)        ← port 8085
      │
      │  SSE stream (live sensor data)
      ▼
AAS Engine (this component)     ← port 8080
      │
      │  HTTP POST (service trigger command)
      ▼
Runtime component (external)   ← port 8082
      │
      │  Executes the corrective service
      ▼
Corrective Services
      │
      │  HTTP POST callback (ExecutionResponseDTO)
      ▼
AAS Engine (this component)
      │
      │  HTTP PATCH (corrected value)
      ▼
Gateway Layer (external)
      │
      │  write
      ▼
Physical Machine
```

---

## Prerequisites

- Java 21 or higher
- Maven 3.8 or later
- Docker Desktop (required only if the Runtime component runs container based services)
- Git Bash or any terminal

---

## Quick Start

### 1. Clone and build

Clone the **engine** project into a folder named `engine`, then build it:

```bash
cd engine
mvn clean install -DskipTests
```

### 2. Configure connectivity

Edit `src/main/resources/application.properties`:

```properties
server.port=8080
gateway.url=http://localhost:8085/api/submodel/stream
gateway.write.url=http://localhost:8085
# write back PATCH timeouts (ms), see CONNECTING_TO_THE_GATEWAY.md
gateway.write.connect-timeout-ms=3000
gateway.write.read-timeout-ms=10000
service.execution.url=http://localhost:8082/services/trigger
engine.callback.url=http://localhost:8080/engine/result
engine.aasx.path=src/main/resources/MachineTool.aasx
```

`engine.aasx.path` is read from disk at startup (not a classpath resource), so this is what
actually determines which physical AASX file this instance governs. Overriding it per Spring
profile is what makes a second instance govern a different machine (see
[ADDING_A_NEW_MACHINE.md](ADDING_A_NEW_MACHINE.md)).

Optional tuning properties:

```properties
engine.error.recoveryTimeoutMs=30000   # ms before automatic recovery from ERROR (default 30000)
engine.service.retryDelayMs=0          # ms between service retries (default 0)
engine.data.normalizeDecimalComma=false # convert "85,0" to "85.0" before parsing (default false)
engine.rules.submodel.idShort=Rules_SM        # idShort of the rules container (default Rules_SM)
engine.statechart.submodel.idShort=StateChart_SM # idShort of the state chart container (default StateChart_SM)
gateway.submodelId=CPS_SM              # idShort of the live data submodel the stream values are stored in (default CPS_SM)
```

The shared Gateway stream is filtered by `gateway.machineId`, not by the submodel id.
The shipped `MachineTool.aasx` spells these idShorts `Rules-SM`, `StateChart-SM`
and `CPS-SM`. The Engine renames them to `Rules_SM`, `StateChart_SM` and `CPS_SM`
when it imports the AASX (`AASXImporter.java:43`), so both spellings load; the
code, the configuration and the REST API use the underscore form.

### 3. Start the engine

**Terminal 1: Runtime component (services project)**
```bash
cd services          # from the folder that holds the project folders
mvn clean install -DskipTests
cd service-manager-app
mvn spring-boot:run
```

**Terminal 2: Engine**
```bash
cd engine            # from the folder that holds the project folders
mvn spring-boot:run
```

Or run the engine as a jar. Start it from the engine project folder, because
`engine.aasx.path` (`src/main/resources/MachineTool.aasx`), `storage/` and `data/`
are resolved relative to the working directory:
```bash
mvn clean package -DskipTests
java -jar target/machinetool-dt-1.0-SNAPSHOT.jar
```
From another folder, pass the AASX path, for example
`--engine.aasx.path=<engine folder>/src/main/resources/MachineTool.aasx` or the
environment variable `ENGINE_AASX_PATH`.

The model initializes automatically and the Gateway listener stays online.
Business execution defaults to **STOPPED**: telemetry updates the live model,
but automatic rule dispatch waits for an explicit Start. Once fresh telemetry
is available, validate and start with the runtime API (the UI in the ui project additionally
checks the loaded Gateway configuration):
```bash
curl -X POST http://localhost:8080/engine/runtime/validate
curl -X POST http://localhost:8080/engine/runtime/start
curl -X POST http://localhost:8080/engine/runtime/stop
```

### 4. Open the monitor dashboard

```
http://localhost:8080/monitor.html
```

The page calls the engine at its own address (`window.location.origin`), so it also works
on another port or host. Open it through the engine, not as a local file.

---

## Tutorial

A complete first session, from zero to the Engine reacting to a real rule
firing and a correction reaching the machine.

1. Build.
   ```bash
   mvn clean install -DskipTests
   ```

2. Start the two components this Engine depends on, each in its own
   terminal. The Gateway (gateway project; Java version and details in its README):
   ```bash
   cd gateway           # the gateway project folder, next to this one
   mvn clean install -DskipTests
   mvn -pl dt-gateway-app spring-boot:run
   ```
   The Runtime component (services project):
   ```bash
   cd services          # the services project folder, next to this one
   mvn clean install -DskipTests
   cd service-manager-app
   mvn spring-boot:run
   ```
   Full connection details for each are in
   [CONNECTING_TO_THE_GATEWAY.md](CONNECTING_TO_THE_GATEWAY.md) and
   [CONNECTING_TO_SERVICE_EXECUTION.md](CONNECTING_TO_SERVICE_EXECUTION.md).

3. Start the Engine, in a third terminal:
   ```bash
   mvn spring-boot:run
   ```
   It initializes itself automatically once Spring Boot finishes booting.
   The log shows the AASX loading, the rules and state chart parsing, and
   the engine reaching STABLE, with no separate startup call needed.

4. Confirm it is alive.
   ```bash
   curl http://localhost:8080/engine/state
   ```

5. Push a value that does not fire a rule, and watch it pass straight
   through.
   ```bash
   curl -X POST http://localhost:8080/engine/push \
     -H "Content-Type: application/json" \
     -d '{"submodelId":"CPS_SM","elementId":"Temperature","value":"50.0"}'
   curl http://localhost:8080/engine/history
   ```
   The history should show `STABLE -> SYNCHRONIZING -> DECIDING -> STABLE`
   (a `NO_CONDITION_MET` cycle), with nothing dispatched.
   For a stream of changing values that crosses all three default rules, run
   `python3 demo_bot.py` instead (needs `pip install requests`; set
   `LABRADT_ENGINE_URL` for an engine that is not on `http://localhost:8080`).

6. Push a value that does fire a rule.
   ```bash
   curl -X POST http://localhost:8080/engine/push \
     -H "Content-Type: application/json" \
     -d '{"submodelId":"CPS_SM","elementId":"Temperature","value":"90.0"}'
   ```
   This crosses Rule_1's threshold (see the default rule table further
   below). The engine moves to TRIGGERING and calls the Runtime component.
   Confirm with:
   ```bash
   curl http://localhost:8080/engine/history
   ```

7. See the correction land. Once the corrective service completes, the
   Engine validates the result against the inverse of the rule that fired,
   then writes it back to the Gateway. Watch the Engine log for
   `Writing correction back to gateway`, and the Gateway's own log for the
   incoming PATCH.

8. See a stale or duplicate result get ignored. This is the one behavior a
   naive integration could not tell apart from a real result: an
   executionId is minted for every dispatch, including every retry, and
   the Engine only ever acts on a result whose executionId matches the
   attempt it currently considers live.
   ```bash
   curl -X POST http://localhost:8080/engine/result \
     -H "Content-Type: application/json" \
     -d '{"serviceId":"cooling-service","executionId":"not-the-real-one","status":"COMPLETED","success":true,"outputData":"70.0"}'
   ```
   The Engine logs this as stale or stray and drops it without changing
   state, since `not-the-real-one` was never returned by an actual
   trigger. The full mechanics are in
   [CONNECTING_TO_SERVICE_EXECUTION.md](CONNECTING_TO_SERVICE_EXECUTION.md).

9. Onboard a machine. [ADDING_A_NEW_MACHINE.md](ADDING_A_NEW_MACHINE.md)
   walks through building a new AASX from scratch and running a second
   instance side by side with the first, with no Java changes.

10. Run the tests.
    ```bash
    mvn test
    ```
    All engine tests run offline (no gateway, service manager, Docker or
    network), so there are no test groups or profiles to choose.
    Coverage today: the state machine's rule evaluation and retry logic,
    the write back safety check, the executionId correlation fix under
    both races it is meant to close and the storage layer's protection
    against a key escaping its root. What is not yet covered by an
    automated test is called out directly in the class level notes on
    `StateChartExecutor`.

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

This is a snapshot of the states and transitions declared in the bundled AASX, not something
hardcoded in Java. `StateChartLoader` parses whatever `StateChart_SM` actually contains, so
editing the AASX changes this picture directly. See [ADDING_A_NEW_MACHINE.md](ADDING_A_NEW_MACHINE.md)
before touching it for a new machine, since the eight state lifecycle is meant to stay the same
across machines.

### Rule Evaluation Cycle

Every time the gateway pushes a sensor value, the engine:

1. Validates the incoming value (rejects values that are not numeric, empty or whitespace only, and quarantines them in the database with a reason tag)
2. Stores valid values in the BaSyx model kept in memory, in file storage and in the H2 database
3. Evaluates all rules against current sensor values
4. If a rule fires, transitions to TRIGGERING and calls the corresponding service through the Runtime component, tracking the executionId(s) the Runtime component returns
5. Retries on failure up to `engine.retry.max` times (default 3) with an optional delay, each retry getting its own executionId so a late response from a previous attempt cannot be mistaken for the current one
6. On receiving a COMPLETED callback, first confirms its executionId still belongs to the attempt currently running, then validates the corrected value against the inverse of the triggering rule (write back validation) before writing it to the gateway
7. If the correction fails validation or retries are exhausted, enters ERROR and arms the automatic recovery timer
8. Returns to STABLE after a successful write back or after the recovery timeout fires

### Boundary Validation

All incoming data is validated before reaching rule evaluation:

| Input | Outcome |
|-------|---------|
| Valid numeric string (`"80.0"`, `"7.5e1"`) | Accepted, stored, evaluated |
| A string that is not numeric (`"hot"`) | Rejected, tagged `[REJECTED:non_numeric]`, quarantined |
| Empty or whitespace | Rejected, tagged `[REJECTED:empty]`, quarantined |
| A value using a comma as the decimal separator (`"85,0"`) | Rejected by default, accepted if `normalizeDecimalComma=true` |

The rejection counter is exposed through `/engine/state` and visible on the dashboard.

### Write Back Validation

After a service returns a corrected value, the engine checks it against the inverse of the triggering rule before writing it to the gateway:

| Triggering condition | Corrected value must satisfy |
|----------------------|------------------------------|
| `greater_than T` | value <= T |
| `less_than T` | value >= T |
| `out_of_bounds` | value back inside declared bounds |
| `equals` or a condition with several inputs | accepted as it is |

If the corrected value fails this check, the write back is skipped and the engine enters ERROR.

If the rule that fired named several services at once (see `Rule.getActions()` and the
Extension Points section below), only the first of them to report a terminal result decides
this outcome. A sibling service's result that arrives afterward is recognized and dropped
instead of being applied a second time. Waiting for every dispatched service before deciding
is not implemented, since no rule shipped with this engine currently names more than one
service.

### Condition Operators

The engine uses a registry based condition evaluator. The following operators are registered at startup:

| Operator | Description |
|----------|-------------|
| `greater_than` | value > threshold |
| `less_than` | value < threshold |
| `equals` | exact string match |
| `out_of_bounds` | 3D boundary check using six bound values separated by commas |
| `wear_average` | averages several sensor inputs named by the `inputs` property and compares the result against threshold |

New operators are added by calling `registerCondition(name, evaluator)`, with no changes to existing evaluation logic.

---

## Extension Points

There are three independent places where the engine can be extended. Each one is isolated so changing one does not require touching the others.

### 1. Adding a new condition operator (behavioral extensibility)

**Where:** `StateChartExecutor.java`, method `registerConditionEvaluators()`

**When:** you need a condition type that does not exist yet, such as `rate_of_change` or `below_average`.

**How the interface is defined:** a `ConditionEvaluator` is a functional interface with one method, `evaluate(EvaluationContext ctx)`, returning true or false. It is registered under a name in a plain `Map<String, ConditionEvaluator>`, not declared as a fixed set of implementations, so the registry is open for new entries at any time, including from outside `StateChartExecutor` through the public `registerCondition(name, evaluator)` method. Nothing about it is static: each registered evaluator is an instance (typically a lambda) held in that map.

**What to do:** add one `registerCondition(...)` call. The engine dispatches to it automatically.

```java
registerCondition("rate_of_change", ctx -> {
    double current  = Double.parseDouble(ctx.getCurrentValue());
    String prev     = ctx.resolve("PreviousReading");
    if (prev == null) return false;
    double rate     = Math.abs(current - Double.parseDouble(prev));
    return rate > Double.parseDouble(ctx.getThreshold());
});
```

Then in the AASX declare `condition = rate_of_change` in the rule. Nothing else changes.

**What does NOT need to change:** `evaluateCondition()`, `parseRule()`, `AASManager`, the state chart, the DTO, `isCorrectionWithinBounds()`.

Note: if the new condition has a meaningful inverse (meaning a corrected value should satisfy some bound), add a corresponding case to `isCorrectionWithinBounds()` in `StateChartExecutor.java`. For conditions with no numeric inverse, the engine accepts the corrected value as it is by default.

---

### 2. Adding a new rule property (configuration extensibility)

**Where:** the AASX XML only. No Java change needed.

**When:** you want to pass a new piece of configuration to a rule, such as a unit, a severity level, a URL or an extra service argument.

**How the interface is defined:** a rule is not a fixed set of typed fields. `Rule` stores every property it was parsed with in a plain attribute map keyed by idShort, and `AASManager.parseRule()` copies every `<property>` it finds into that map without a switch statement or a hardcoded field list. This means the rule schema is expandable by construction: any property name you add in the AASX is captured automatically, with no parser change required.

**What to do:** add a new `<property>` element to the rule in the AASX. The property becomes immediately available in `handleServiceCall()` and in any evaluator through `ctx.getRule().getAttribute("yourPropertyName")`.

To pass it as an argument to the service, prefix it with `arg.` in the AASX:

```xml
<property>
  <idShort>arg.targetTemperature</idShort>
  <value>65.0</value>
</property>
```

This arrives in the dynamic arguments sent to the Runtime component as `"targetTemperature": "65.0"` automatically.

**What does NOT need to change:** `parseRule()`, `Rule.java`, `AASManager`, any evaluator.

---

### 3. Adding a new field to the service trigger contract

**Where:** two places in coordination, in this order.

**When:** the service needs a new piece of information that the engine currently does not send, such as a priority level or a target unit.

**How the interface is defined:** there is no separate request DTO class on the engine's side. `StateChartExecutor.handleServiceCall()` builds a plain `Map<String, Object>` body (`serviceIds` and `dynamicArgs`) and posts it directly to `service.execution.url`. `dynamicArgs` is assembled from context the engine already knows (target submodel, target element, callback URL) plus every rule property prefixed with `arg.` (see extension point 2 above), so most new arguments do not need a Java change at all, only a new `arg.` property in the AASX.

**Step 1: if the value cannot come from a rule property**, add it directly in `handleServiceCall()` where `dynamicArgs` is built:
```java
dynamicArgs.put("priority", "high");
```

**Step 2: coordinate with whoever owns the Runtime component.** It must read and act on the new argument. Without this step the argument is sent but silently ignored on the receiving end.

**What does NOT need to change:** `evaluateCondition()`, the state chart, `parseRule()`, `trackExpectedExecutionIds()`.

### AASX Change Detection

At startup, the engine computes a SHA-256 hash of the AASX file and compares it against a stored marker. If the AASX has changed since the last run, rules are imported again automatically. No manual cache clearing is needed.

### Storage

The engine uses two storage layers:

**File Storage (`storage/` folder):**
```
storage/
├── Rules_SM/            ← rules loaded from AASX at startup (folder named after the rules submodel, default Rules_SM)
├── services/            ← service artifacts (scripts, build files) served to the Runtime component
└── engine/              ← initialization marker and AASX hash
```

This folder is runtime data. It is not in the repository (this project's
`.gitignore` ignores it) and is created on the first start: the rules come from the AASX,
and the three default CNC Dockerfiles under `services/` are seeded if missing.

Every key is resolved against this folder as an absolute root, and any key that would resolve
outside it (through `../` segments or an absolute path of its own) is rejected. This matters
because the storage endpoints below take a key straight from the request.

Note: the `storage/` folder is scoped to the current run under `mvn spring-boot:run`. Contents written during a run are not guaranteed to persist across process restarts, depending on the launch environment.

**H2 Embedded Database (`data/machinetool-db`):**
- `ENGINE_STATE` table: full history of every state transition with timestamps
- `SUBMODEL_DATA` table: full history of every sensor value received, including rejected values

The H2 console is available at `http://localhost:8080/h2-console` (JDBC URL: `jdbc:h2:file:./data/machinetool-db`).

---

## Is It Static?

The contracts are static. The REST API surface (`AASController`), the two
inbound interfaces (`IDataReceiver`, `IEngineStatusListener`), and the
shapes of `ExecutionResponseDTO` and `ExecutionStatus` do not change
between machines or between runs. That stability is what lets the Gateway
and the Runtime component be built once and reused against any machine this
Engine governs.

The behavior is not static. Nothing about what the Engine actually does is
hardcoded.

- Rules, thresholds, target elements and which service each rule calls
  come entirely from the AASX, parsed at startup by `AASManager`. Adding,
  removing or editing a rule never touches Java.
- The state machine itself, states, transitions, guards, entry and exit
  actions, is also parsed from the AASX at startup, by `StateChartLoader`.
  It is not a fixed enum or a switch statement.
- Condition operators are held in a registry
  (`StateChartExecutor.registerConditionEvaluators()`) rather than a fixed
  set. A new operator is one registration call, not a new code path added
  to an existing method.

One honest boundary: rules and the state chart are both loaded once at
startup, with a SHA-256 hash check that reimports them automatically if the
AASX changed since the last run, so a rule edit crosses a restart. A
machine already running is not disturbed by editing a different machine's
AASX. Reloading rules on a live instance without restarting it is not
implemented, since nothing in the current scope requires changing rules on
a machine while it is producing.

---

## REST API Reference

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/engine/startup` | Manually reinitialize the engine and reload the AASX (happens automatically at boot already) |
| `GET` | `/engine/shutdown` | Fire SHUTDOWN_SIGNAL and terminate gracefully |
| `GET` | `/engine/state` | Current state, loaded rule count and rejection counter |
| `GET` | `/engine/history` | Last 10 state transitions |
| `GET` | `/engine/submodel-data` | Last 8 sensor data writes |
| `GET` | `/engine/storage` | List the top level contents of the storage folder |
| `GET` | `/engine/storage/{key}` | Retrieve a file from storage (used by the Runtime component) |
| `POST` | `/engine/storage/upload` | Upload a file into storage (multipart form, returns the storage key) |
| `DELETE` | `/engine/storage/{key}` | Delete a stored file |
| `POST` | `/engine/push` | Push a sensor value as flat JSON (gateway or manual testing) |
| `POST` | `/engine/push/{submodelId}` | Push a value as a BaSyx SubmodelElement body |
| `POST` | `/engine/result` | Receive a service execution result (called by the Runtime component) |

### Manual data push (testing without the gateway)

```bash
curl -X POST http://localhost:8080/engine/push \
  -H "Content-Type: application/json" \
  -d '{"submodelId":"CPS_SM","elementId":"Temperature","value":"80.0"}'
```

### Simulate a service result (testing without the Runtime component)

```bash
curl -X POST http://localhost:8080/engine/result \
  -H "Content-Type: application/json" \
  -d '{"serviceId":"cooling-service","executionId":"test-1","status":"COMPLETED","success":true,"outputData":"72.0"}'
```

`executionId` can be any string for a manual test like this. In a real run it comes from the
Runtime component's trigger response, see the Integration Contracts section below.

---

## Editing the AASX File

### Unzip

```bash
cd src/main/resources
python -c "import zipfile; zipfile.ZipFile('MachineTool.aasx').extractall('aasx_edit')"
```

Open and edit:
```
src/main/resources/aasx_edit/aasx/MachineTool-AAS/MachineTool-AAS.aas.xml
```

### Rezip

```bash
python -c "
import zipfile, shutil
src = 'MachineTool.aasx'
tmp = 'MachineTool.aasx.tmp'
edited = 'aasx_edit/aasx/MachineTool-AAS/MachineTool-AAS.aas.xml'
new = open(edited, 'rb').read()
zin = zipfile.ZipFile(src, 'r')
zout = zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED)
for item in zin.infolist():
    data = new if item.filename.replace('\\\\', '/').lower().endswith('machinetool-aas.aas.xml') else zin.read(item.filename)
    zout.writestr(item, data)
zin.close()
zout.close()
shutil.move(tmp, src)
print('Done.')
"
```

### Verify

```bash
python -c "
import zipfile, re
z = zipfile.ZipFile('MachineTool.aasx')
xml = z.read([n for n in z.namelist() if n.endswith('.aas.xml')][0]).decode()
rules = re.findall(r'<idShort>(Rule_\d+)</idShort>.*?<idShort>targetElement</idShort>.*?<value>(.*?)</value>', xml, re.DOTALL)
for r in rules: print(r[0], '->', r[1])
"
```

### Clean up

```bash
rm -rf aasx_edit/
```

---

## Adapting to a New Machine

The engine is not tied to any specific machine. To deploy it on a different machine, only the AASX file changes. The Java code stays untouched.

### Rule structure

Each rule in `Rules_SM` follows this structure:

```xml
<submodelElementCollection>
  <idShort>Rule_1</idShort>
  <value>
    <property>
      <idShort>targetSubmodel</idShort>
      <value>CPS_SM</value>
    </property>
    <property>
      <idShort>targetElement</idShort>
      <value>YourSensorName</value>
    </property>
    <property>
      <idShort>condition</idShort>
      <value>greater_than</value>       <!-- greater_than, less_than, equals, out_of_bounds, wear_average -->
    </property>
    <property>
      <idShort>threshold</idShort>
      <value>75.0</value>
    </property>
    <property>
      <idShort>action</idShort>
      <value>your-service-name</value>
    </property>
    <property>
      <idShort>cooldownMs</idShort>
      <value>30000</value>
    </property>
    <property>
      <idShort>description</idShort>
      <value>Description meant for a person reading the AASX</value>
    </property>
  </value>
</submodelElementCollection>
```

A rule's `action` can also name more than one service, separated by commas (see `Rule.getActions()`),
for example `<value>cooling-service,energy-monitoring-service</value>`. This dispatches every
named service from a single rule firing. Read the write back validation note above before relying
on more than one of them returning a correction, since only the first terminal result decides
the outcome today.

For conditions with several inputs such as `wear_average`, add an `inputs` property listing the element names to average:

```xml
<property>
  <idShort>condition</idShort>
  <value>wear_average</value>
</property>
<property>
  <idShort>inputs</idShort>
  <value>SpindleLoad,ToolWearIndex,CuttingTemperature</value>
</property>
<property>
  <idShort>threshold</idShort>
  <value>70.0</value>
</property>
```

### Nested submodels and idShort paths

The AASX may contain any additional submodels (for example IDTA templates) next to
the engine's own containers. All submodels are parsed and their elements are
addressed by **idShort path**: an element nested in collections is referenced
as `CollectionA.CollectionB.ElementName`, while top level elements keep their
bare name. Rules can target any submodel and any path:

```xml
<property><idShort>targetSubmodel</idShort><value>TechnicalData</value></property>
<property><idShort>targetElement</idShort><value>OperationalData.Spindle.Temperature</value></property>
```

Flat submodels such as `CPS_SM` are unaffected: the Gateway keeps pushing bare
element names and those resolve exactly as before. Note that data can only
arrive at a nested path if the pusher addresses that path. The current Gateway
emits flat names only.

### Merging or splitting the behavior submodels

Rules are identified by **shape**, not by container: a collection is a rule if
and only if it declares a `condition` property. Combined with the configurable
container idShorts (`engine.rules.submodel.idShort`,
`engine.statechart.submodel.idShort`), this means the split into `Rules_SM`
and `StateChart_SM` is a convention, not a requirement. Pointing both
properties at the same submodel lets rules, `States` and `Transitions` live in
one merged submodel. How to split the behavior definition is the integrator's
choice.

### Reusability demonstration

The engine was validated against two different machine configurations with zero code changes. Swapping `MachineTool.aasx` from the CNC milling configuration to a conveyor belt configuration and restarting the engine produced the expected rule evaluation behavior for the new machine. The AASX hash change was detected automatically and rules were imported again without manual intervention.

> **Full walkthrough, one step at a time:** see [ADDING_A_NEW_MACHINE.md](ADDING_A_NEW_MACHINE.md) for building a new AASX file from scratch, configuring a second Engine instance and running multiple machines side by side.

---

## Integration Contracts

This section is a quick reference. For the full contract in each direction,
a connect and run tutorial, and troubleshooting specific to that boundary,
see [CONNECTING_TO_THE_GATEWAY.md](CONNECTING_TO_THE_GATEWAY.md) and
[CONNECTING_TO_SERVICE_EXECUTION.md](CONNECTING_TO_SERVICE_EXECUTION.md).

### Gateway to Engine (SSE stream format)

Three event types: `snapshot` (on connect, one per element), `update` (one changed
value) and `keyframe` (periodic re-send). Each event carries one AAS Property, wrapped
with its routing key `<machineId>.<idShort>`:
```
event: update
data: {"key":"cnc-line1.Temperature","property":{"modelType":"Property","idShort":"Temperature","valueType":"xs:double","value":"76.1","qualifiers":[{"type":"StatusCode","value":"OK"}, ...]}}
```

Only `update` events evaluate rules; `snapshot` and `keyframe` only refresh the model.
Details: [CONNECTING_TO_THE_GATEWAY.md](CONNECTING_TO_THE_GATEWAY.md#event-format).

### Engine to Runtime component (trigger request)

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

Posted to `service.execution.url` (`POST /services/trigger`). On success the Runtime component
answers with a 202 and a body listing one executionId per triggered service:

```json
{
  "triggered": [
    { "executionId": "3f9c2e1a-...", "serviceId": "cooling-service", "status": "RUNNING" }
  ],
  "message": "Queued for execution"
}
```

The engine records these executionIds (`trackExpectedExecutionIds()`) and uses them to recognize
which later result belongs to this attempt. If this body cannot be parsed or carries no
executionId, the engine falls back to accepting any result unconditionally, the same behavior
it had before this correlation check existed.

The Runtime component's runtimes fetch a service's artifact using whatever
`fileReference` was recorded on the service definition at registration, for example:
```
GET http://localhost:8080/engine/storage/services/cooling-service/Dockerfile
```
See [CONNECTING_TO_SERVICE_EXECUTION.md](CONNECTING_TO_SERVICE_EXECUTION.md) for the full
mechanism, `fileReference` is an arbitrary storage key, not a fixed path convention.

### Runtime component to Engine (result callback)

```json
{
  "serviceId": "cooling-service",
  "executionId": "3f9c2e1a-...",
  "status": "COMPLETED",
  "success": true,
  "outputData": "72.0"
}
```

`executionId` must match the value the Runtime component returned when the service was
triggered, since the same `serviceId` can run more than once at the same time or across a
retry. A result whose `executionId` does not match the attempt currently running is logged
and dropped rather than applied. A `null` executionId (an older Runtime component deployment
that does not populate this field yet) is accepted unconditionally, for compatibility.

`status` is one of `ACCEPTED`, `RUNNING`, `COMPLETED`, `FAILED` or `CANCELLED`. `ACCEPTED` and
`RUNNING` are read as still in progress and leave the engine waiting. Any other status is
treated as a failure that can trigger a retry, except `COMPLETED`, which is the only status
that leads to a write back.

`outputData` must be a numeric string representing the corrected sensor value on a `COMPLETED`
result. The engine validates it against the triggering rule before writing it to the gateway.

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

## Project Structure

```
engine/
├── src/main/java/org/example/machinetool/
│   ├── Main.java                        ← Spring Boot entry point
│   ├── CorsConfig.java                  ← allows any origin, needed since the Gateway and dashboard run elsewhere
│   ├── engine/
│   │   ├── StateChartExecutor.java      ← the state machine: rule evaluation, service calls, write back validation
│   │   ├── GatewayStreamClient.java     ← SSE stream reader, filters by machine id
│   │   ├── GatewayWriteClient.java      ← PATCH write back to the gateway
│   │   ├── aas/
│   │   │   ├── AASXImporter.java        ← extracts entries from the AASX zip
│   │   │   ├── AASManager.java          ← parses the AAS XML and loads rules
│   │   │   └── StateChartLoader.java    ← parses the state and transition definitions
│   │   ├── model/                       ← Rule, StateAction, StateChartDefinition and the ConditionEvaluator extension point
│   │   └── controller/
│   │       └── AASController.java       ← the one REST entry point, implements IDataReceiver and IEngineStatusListener
│   └── shared/
│       ├── FileStorageService.java      ← storage on disk, one JSON file per key
│       ├── db/
│       │   └── DatabaseStorageService.java ← H2 audit log
│       └── dto/                         ← ExecutionResponseDTO, ExecutionStatus
├── src/main/resources/
│   ├── MachineTool.aasx                 ← AAS behavior definition (source of truth)
│   ├── application.properties
│   └── static/
│       └── monitor.html                 ← live monitoring dashboard
├── storage/                             ← runtime data (created automatically, scoped to the current run)
└── data/                                ← H2 database (persists across restarts)
```

Note: there is no separate `ServiceExecutionClient` class. The HTTP call to the Runtime
Component is made directly inside `StateChartExecutor.handleServiceCall()`.

---

## Where Is X?

| I am looking for | It is here |
|---|---|
| The state machine and the rule evaluation loop | `engine/StateChartExecutor.java` |
| The condition operator extension point | `engine/model/ConditionEvaluator.java` |
| The rule data model, not tied to a fixed set of fields | `engine/model/Rule.java` |
| The state chart data model (states, transitions, entry and exit actions) | `engine/model/StateChartDefinition.java` and the classes next to it |
| Where the AASX is parsed into rules and the state chart | `engine/aas/AASManager.java` and `engine/aas/StateChartLoader.java` |
| The REST API, every endpoint | `engine/controller/AASController.java` |
| The storage abstraction, for a different backing store | `shared/IStorageService.java` |
| The Gateway connection, both directions | `engine/GatewayStreamClient.java` and `engine/GatewayWriteClient.java` |
| The write back safety check | `StateChartExecutor.isCorrectionWithinBounds()` |
| The executionId correlation logic | `StateChartExecutor.onServiceResult()` |
| The default machine configuration | `src/main/resources/MachineTool.aasx` |

All paths above are relative to `src/main/java/org/example/machinetool/`.

---

## Troubleshooting

### Manual Rule tests from the UI

Use `POST /engine/rules/{id}/dry-run` with `{"values":{"Temperature":95}}`
to evaluate a saved rule without side effects. Use
`POST /engine/rules/{id}/test-trigger` with the same `values` plus
`"confirm":true,"writeBack":false` to execute registered Services once if the
condition matches. Both require business STOPPED, including direct Java requests.
Overrides only affect condition evaluation; Service parameters remain those of
the saved rule plus registry defaults. Disabled/cooldown-blocked rules can be
explicitly tested, but the report distinguishes automatic eligibility.

`GET /engine/rule-tests/{testId}` polls the independently correlated results and
checks the existing runtime correction bounds. It never writes values, retries,
changes automatic cooldowns or enters the automatic state chart. Service-owned
side effects remain possible. Read results while RUNNING if needed. Records are
temporary and bounded; restart clears them. This is not a physical write-back
test or a general typed Service-contract checker.

| Problem | Cause | Fix |
|---------|-------|-----|
| `Entry not found in stream` | AASX repacked with backslash paths | Use the Python rezip command above |
| `Stream disconnected`, retrying every 5 seconds | Gateway not running | Start the Gateway on port 8085 first, or ignore if you are not testing the data push path yet |
| Rules show `[?]` in logs | Element name mismatch | Confirm the element name matches what the gateway pushes |
| Write back failed | Gateway unreachable or wrong value format | Check port 8085 is running, ensure `outputData` is numeric |
| Rules not updated after an AASX edit | Old hash marker still cached | The engine detects the change automatically on next startup through the SHA-256 hash comparison |
| Engine stuck in TRIGGERING | No COMPLETED callback received, or the callback's executionId does not match | Check the Runtime component terminal, or use the `/engine/result` curl example above to inject a test result with a matching executionId |
| Engine stuck in INITIALIZING after recovery | AASX missing or unreadable | Verify `MachineTool.aasx` exists in `src/main/resources/` |
| Data push returns STABLE but the rule did not fire | Value below threshold or element name wrong | Check the logs for `No value found for CPS_SM.X` or `Condition NOT met` |

---

## Team

| Component | Owner | Port |
|-----------|--------|------|
| Gateway (SSE) | External | 8085 |
| AAS Engine (this) | This Component | 8080 |
| Runtime component | External | 8082 |

---

## Documentation Map

The documentation of this component lives in this project, next to the code it describes.
The overview of all LABraDT projects and the architecture diagram are in the
**documentation** project.

- [README.md](README.md): what this component is, how it works, the extension points, the REST API and integration contracts and this map.
- [ADDING_A_NEW_MACHINE.md](ADDING_A_NEW_MACHINE.md): bringing a new machine under this Engine's governance, building its AASX, configuring a second instance, verifying it end to end.
- [CONNECTING_TO_THE_GATEWAY.md](CONNECTING_TO_THE_GATEWAY.md): the full contract with the Gateway in both directions and a connection tutorial.
- [CONNECTING_TO_SERVICE_EXECUTION.md](CONNECTING_TO_SERVICE_EXECUTION.md): the full contract with the Runtime component in both directions, including the executionId correlation rules, and a connection tutorial.
- Javadoc on `ConditionEvaluator` and `IStorageService` states each extension point's contract directly in the source, including whether it is static and how to add to it.

---

## Packaging for Submission

Build artifacts and runtime data are not part of the source submission. A
clean copy of this component contains `src/`, `pom.xml`, and the four
documentation files listed above, and nothing else. Leave out:

- `target/`, the build output, regenerated by `mvn clean install`
- `data/`, `storage/`, `storage-molder1/`, runtime state created automatically when the Engine runs (all three are ignored by this project's `.gitignore`)
- `.venv/`, local Python tooling unrelated to the Java build

---
