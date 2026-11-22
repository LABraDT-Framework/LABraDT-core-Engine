# Connecting to the Runtime component

This document is the contract between the Engine and the external Service
Execution Component, in both directions, plus a
tutorial for standing up that connection from nothing. It is written for
whoever owns the Runtime component side, and for anyone building a replacement
or a second service runtime against this Engine.

The other external boundary, the Gateway, has its own document:
[CONNECTING_TO_THE_GATEWAY.md](CONNECTING_TO_THE_GATEWAY.md). For everything
else about the Engine itself, see [README.md](README.md).

---

## 1. Where this fits

```
Engine (this component)  --HTTP POST, trigger request-->  Runtime component (external)
Engine (this component)  <--HTTP POST, result callback--  Runtime component (external)
```

Every call in this relationship is initiated by whichever side has news for
the other: the Engine triggers a service when a rule fires, and the Service
Execution Component reports back when that service finishes, whenever that happens to be.
Neither side polls the other.

All of this lives in one method on the Engine side,
`StateChartExecutor.handleServiceCall()` for the outgoing trigger, and
`StateChartExecutor.onServiceResult()` for the incoming result. There is no
separate client class for this, the HTTP call is made directly with a
RestTemplate inside `handleServiceCall()`.

---

## 2. Direction 1: Engine to Runtime component (trigger request)

### Connecting

```properties
service.execution.url=http://localhost:8082/services/trigger
```

The Engine posts here every time a rule's condition is met and it needs to
dispatch a service, and again for every retry, each retry being its own
independent HTTP call with its own identity (see executionId below).

### Request body

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

`serviceIds` is normally a single element, but a rule can name more than one
service at once, separated by commas in its `action` property, in which case
all of them appear here and are dispatched together as one request.
`dynamicArgs` always carries `submodelId`, `elementId` and `callbackUrl`, plus
any rule property prefixed `arg.` in the AASX (see
[README.md](README.md#extension-points) for how to add one).

`callbackUrl` tells the Runtime component exactly where to send the result. It
always matches this instance's own `engine.callback.url`, so a Runtime component
implementation does not need to know the Engine's address in advance, it
reads it from every request.

### Expected response

A `2xx` response is read as accepted. The Engine parses the response body
looking for this shape:

```json
{
  "triggered": [
    { "executionId": "3f9c2e1a-...", "serviceId": "cooling-service", "status": "RUNNING" }
  ],
  "message": "Queued for execution"
}
```

`triggered[].executionId` is what makes the rest of this contract work: the
Engine records one executionId per service named in the request
(`StateChartExecutor.trackExpectedExecutionIds()`) and will only accept a
later result whose `executionId` matches one of these. **This field is not
optional in practice.** If the response cannot be parsed as this shape, or
carries no executionId at all, the Engine falls back to accepting any result
unconditionally, which reopens exactly the correlation problems executionId
exists to prevent (a stale result from a previous retry, or a result meant
for a rule that is no longer the one in flight, being applied as if it were
current). A Runtime component implementation should always populate this field.

A non `2xx` response, or the request failing outright (connection refused,
timeout), is treated as an immediate failure for every named service, the
same as if each had individually called back with `FAILED`. This funnels
through the same retry logic as a real failure response, see section 3.

---

## 3. Direction 2: Runtime component to Engine (result callback)

### Connecting

The Runtime component calls back to whatever `callbackUrl` it received in the
trigger request, which is always:

```
POST http://localhost:8080/engine/result
```

### Request body

```json
{
  "serviceId": "cooling-service",
  "executionId": "3f9c2e1a-...",
  "status": "COMPLETED",
  "success": true,
  "outputData": "72.0"
}
```

| Field | Required | Meaning |
|---|---|---|
| `serviceId` | yes | Which service this result is for |
| `executionId` | strongly recommended | Must match the value returned in the trigger response for this specific dispatch, see below |
| `status` | yes | One of `ACCEPTED`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED` |
| `success` | yes | True only for a genuinely successful completion |
| `outputData` | on `COMPLETED` | The corrected sensor value, as a numeric string |
| `errorMessage` | on failure | Free text, logged, not otherwise interpreted |

### Why executionId matters

The same `serviceId` can be dispatched more than once, concurrently or across
a retry. `executionId` is what tells two such dispatches apart. On every
incoming result, the Engine checks the `executionId` against the attempt it
currently considers in flight (`StateChartExecutor.onServiceResult()`):

- A result whose `executionId` does not match anything currently expected is
  logged as stale or stray and dropped without changing any state. This is
  what protects against a late response from an attempt that has since been
  retried, or from a rule the Engine has already moved on from.
- A `null` `executionId` (a Runtime component that does not populate this
  field) is accepted unconditionally, for compatibility. This is a
  deliberate fallback, not a recommendation: without a real executionId, the
  Engine has no way to distinguish a current result from a stale one.
- If a rule dispatched several services at once and more than one of them
  returns a result, only the first terminal result decides the outcome.
  A later sibling's result is recognized and dropped rather than applied a
  second time, see [README.md](README.md#write-back-validation) for the
  reasoning.

### Status values and what each does

`ACCEPTED` and `RUNNING` are read as still in progress. They update the
Engine's record of the latest status but do not conclude anything, no retry,
no write back, the Engine just keeps waiting.

`COMPLETED` is the only status that leads to a write back. Its `outputData`
is checked against the inverse of the rule that fired
(`StateChartExecutor.isCorrectionWithinBounds()`) before being sent to the
Gateway. A `COMPLETED` result with a value that fails this check is treated
as if the attempt had failed outright.

Any other status, `FAILED` or `CANCELLED`, is treated as a failure. If
retries remain (`engine.retry.max`, default 3), the Engine waits
`engine.service.retryDelayMs` (default 0) and dispatches the same service or
services again, with a fresh trigger request and a fresh executionId. Once
retries are exhausted, the Engine enters `ERROR`.

### Manual testing without a live Runtime component

```bash
curl -X POST http://localhost:8080/engine/result \
  -H "Content-Type: application/json" \
  -d '{"serviceId":"cooling-service","executionId":"test-1","status":"COMPLETED","success":true,"outputData":"72.0"}'
```

`executionId` can be any string for a manual test like this, since nothing
else generated it first. To exercise the correlation logic properly, trigger
a real rule first (see the tutorial below) and read the real executionId out
of the Engine's log before crafting the callback.

---

## 4. The service artifact handoff

Some services need a file to run, a script for a Lua service, a build file
for a container service. The Engine is where those files live. It exposes a
small key or value file store, and the Runtime component's
runtimes fetch from it just before execution:

```
GET http://localhost:8080/engine/storage/<fileReference>
```

`fileReference` is an arbitrary storage key, not a fixed path convention. It
is recorded on the service definition in the Runtime component's
registry when the service is registered, and travels to the runtime on the
trigger request.

The three services bundled with the default configuration
(`cooling-service`, `geometry-correction-service`, `energy-monitoring-service`)
are seeded automatically at startup, nothing needs to be uploaded for them.
For a new service, upload its script or build file once:

```bash
# a script
curl -X POST http://localhost:8080/engine/storage/upload \
  -F "file=@cooling.lua" \
  -F "key=services/your-service-name/script"

# a build file
curl -X POST http://localhost:8080/engine/storage/upload \
  -F "file=@Dockerfile" \
  -F "key=services/your-service-name/Dockerfile"
```

The Runtime component then fetches it using whatever key was
chosen at registration, the same way as the bundled services.

---

## 5. Tutorial: connecting a Runtime component from nothing

This walks through standing up the connection end to end, assuming you have a
Runtime component that can accept the trigger request in section 2 and send the
callback described in section 3.

1. **Point the Engine at your Runtime component.** In
   `src/main/resources/application.properties`:
   ```properties
   service.execution.url=http://localhost:8082/services/trigger
   engine.callback.url=http://localhost:8080/engine/result
   ```
   `engine.callback.url` must be reachable from wherever the Runtime component
   actually runs, if it runs on a different machine or container, `localhost`
   will not resolve to the Engine from there.

2. **Start the Runtime component first** (services project;
   run steps are in its README), so it is ready before any rule fires.

3. **Start the Engine:**
   ```bash
   mvn spring-boot:run
   ```
   The Engine initializes itself automatically (see
   [README.md](README.md#quick-start)).

4. **Fire a rule manually** to trigger a real dispatch, using a value that
   exceeds one of the loaded rules' thresholds (see the table in
   [README.md](README.md#default-cnc-milling-machine-configuration)):
   ```bash
   curl -X POST http://localhost:8080/engine/push \
     -H "Content-Type: application/json" \
     -d '{"submodelId":"CPS_SM","elementId":"Temperature","value":"90.0"}'
   ```
   This is also the connectivity check: if the Runtime component is not
   reachable at `service.execution.url`, the Engine's log will show the
   trigger request failing right after this push.

5. **Confirm the trigger request arrived** on the Runtime component side, and
   note the executionId it returned in its response.

6. **Send the real result back**, using that executionId:
   ```bash
   curl -X POST http://localhost:8080/engine/result \
     -H "Content-Type: application/json" \
     -d '{"serviceId":"cooling-service","executionId":"<the real id>","status":"COMPLETED","success":true,"outputData":"72.0"}'
   ```

7. **Confirm the full cycle completed:**
   ```bash
   curl http://localhost:8080/engine/history
   ```
   You should see the engine return to `STABLE`
   (`TRIGGERING --SERVICE_COMPLETE--> STABLE`), and, if a write back URL is
   configured, the corrected value should have reached the Gateway (see
   [CONNECTING_TO_THE_GATEWAY.md](CONNECTING_TO_THE_GATEWAY.md)).

8. **Try a deliberate failure** to see the retry path: send the same result
   with `"status":"FAILED","success":false` instead, and watch the Engine
   dispatch a new trigger request with a new executionId, up to
   `engine.retry.max` times before entering `ERROR`.

---

## 6. Configuration reference

| Property | Meaning | Used by |
|---|---|---|
| `service.execution.url` | Where the Engine posts trigger requests | `StateChartExecutor.handleServiceCall()` |
| `engine.callback.url` | The URL the Engine tells the Runtime component to call back, must match this instance's own port | same |
| `engine.retry.max` | How many times a failed service is retried before entering `ERROR` (default 3) | `StateChartExecutor.onServiceResult()` |
| `engine.service.retryDelayMs` | Delay before each retry, in milliseconds (default 0) | same |

---

## 7. Troubleshooting

| Symptom | Likely cause | What to check |
|---|---|---|
| Trigger request never arrives | Wrong `service.execution.url`, or Runtime component not running | Confirm the URL and that the process is up |
| Engine stuck in `TRIGGERING` indefinitely | No callback received, or the callback's `executionId` does not match the current attempt | Check the Runtime component logs for the actual call, and confirm the `executionId` sent back matches the one returned in the trigger response |
| Callback arrives but nothing happens, logged as stale or stray | `executionId` belongs to a superseded attempt, for example a retry has since replaced it | This is usually correct behavior, not a bug, confirm which attempt is actually current before assuming otherwise |
| `COMPLETED` result received but no write back happened | `outputData` failed the bounds check against the triggering rule, or the Gateway write URL is not configured | Check the Engine log for `fails inverse check`, and see [CONNECTING_TO_THE_GATEWAY.md](CONNECTING_TO_THE_GATEWAY.md) |
| `Entry not found` fetching a service artifact | Script or build file never uploaded under the expected `fileReference` | Upload it as shown in section 4, and confirm the `fileReference` recorded at registration matches the storage key exactly |
