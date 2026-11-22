# Connecting to the Gateway

This document is the contract between the Engine and the external Gateway, in both
directions, plus a tutorial for standing up that connection from nothing. It is
written for whoever owns the Gateway side, and for anyone building a replacement
or a second Gateway implementation against this Engine. If you are integrating
a new component and want the Engine's own perspective on this boundary rather
than the Gateway's, this is the file to read.

The other external boundary, the Runtime component, has its own
document: [CONNECTING_TO_SERVICE_EXECUTION.md](CONNECTING_TO_SERVICE_EXECUTION.md).
For everything else about the Engine itself, see [README.md](README.md).

---

## 1. Where this fits

```
Gateway (external)  --SSE stream, sensor updates-->  Engine (this component)
Gateway (external)  <--HTTP PATCH, corrected values--  Engine (this component)
```

The Engine never polls the Gateway. It opens one long lived SSE connection at
startup and reacts to whatever the Gateway sends. The only traffic the Engine
initiates toward the Gateway is a write back PATCH, and only after a service
has actually corrected a value.

The class that owns the inbound side is `GatewayStreamClient`. The class that
owns the outbound side is `GatewayWriteClient`. Neither talks to the other, and
neither is aware of the state machine, they only carry data in and out.

---

## 2. Direction 1: Gateway to Engine (live sensor data)

### Connecting

`GatewayStreamClient` opens a plain HTTP connection to `gateway.url` and
reads it as a Server-Sent Events stream, on a background thread started
automatically once the application boots (no manual step). If the connection
drops, for any reason, including the Gateway not being up yet, it reconnects
automatically every 5 seconds, forever, logging each attempt. There is no
limit on retries and no backoff beyond the flat 5 second interval.

```properties
gateway.url=http://localhost:8085/api/submodel/stream
gateway.machineId=cnc-line1
```

`gateway.machineId` matters even when only one machine is on the shared
stream: every event's `key` field is expected to be prefixed
`<machineId>.<idShort>`, and the Engine silently ignores any event whose prefix
does not match this value. If your Gateway does not tag keys this way, or tags
them with a different machine id, events will appear to vanish with nothing
obviously wrong, this filter is the first thing to check.

### Event format

The Gateway sends three event types. Each `data` line is one JSON object: the AAS
Property of one element, wrapped with its routing key `<machineId>.<idShort>`:

```
event: snapshot
data: {"key":"cnc-line1.Temperature","property":{"modelType":"Property","idShort":"Temperature","valueType":"xs:double","value":"72.5","qualifiers":[{"type":"StatusCode","value":"OK"}, ...]}}

event: update
data: {"key":"cnc-line1.Temperature","property":{"modelType":"Property","idShort":"Temperature","valueType":"xs:double","value":"76.1","qualifiers":[{"type":"StatusCode","value":"OK"}, ...]}}

event: keyframe
data: {"key":"cnc-line1.Temperature","property":{ ... }}
```

The full Property format (all qualifiers) is in `REST_CONTRACT.md` in the gateway project.

- **`snapshot`**: on connect, one event per cached element. The Engine writes the
  value into its model (`onDataSnapshot`) but does not evaluate rules.
- **`update`**: one changed value. Only `update` events evaluate rules: each calls
  `onDataPush(submodelId, idShort, value)`, which validates the value, stores it and
  checks the rules. `submodelId` is always `gateway.submodelId` (default `CPS_SM`);
  the stream itself carries no submodel id.
- **`keyframe`**: periodic re-send of unchanged values, handled like `snapshot`
  (keeps values fresh, no rule evaluation).

Any other event type is ignored (`GatewayStreamClient.java:149-161`).

### The `key` field and machine filtering

The text before the first dot in `key` is compared against
`gateway.machineId`. A mismatch means the event is silently dropped,
before `idShort` or `value` are even read. A `StatusCode` qualifier other than
`OK` (for example `STALE`) also causes the event to be dropped, logged as a
warning rather than applied.

### What a malformed event does

If `idShort` or `value` cannot be found in the event body, the event is logged
as unparsable and dropped. Nothing downstream ever sees a null value from this
path, boundary validation inside the Engine is a second, independent layer
for a different purpose (rejecting numbers that parse but are not sensible),
not a backstop for malformed JSON.

---

## 3. Direction 2: Engine to Gateway (write back)

After a corrective service returns a value and that value passes the write
back safety check (see [README.md](README.md#write-back-validation)), the
Engine sends it to the Gateway as a PATCH:

```
PATCH http://localhost:8085/api/submodel/<machineId>.<elementId>/$value
Content-Type: application/json

72.0
```

The body is the raw corrected value as a plain string, not a JSON object. The
routing key is built the same way incoming keys are read, `machineId` (from
`gateway.machineId`) followed by a dot and the element id, so a Gateway
implementation only needs to understand one key format for both directions.

```properties
gateway.write.url=http://localhost:8085
```

If `gateway.write.url` is left unset, the Engine logs a warning and skips
the write, it does not fail the whole result. If the Gateway is unreachable or
rejects the PATCH, the failure is logged and nothing retries the write
automatically, the corrective service itself already ran and reported success,
so this only affects whether the physical machine actually received the
correction.

The Engine uses Apache HttpClient under the hood for this call specifically
because Java's default RestTemplate does not support PATCH. This is purely an
implementation detail on the Engine side, a Gateway only needs to accept a
standard HTTP PATCH request.

---

## 4. Developing against the Engine without a real Gateway

You do not need a running Gateway to build or test anything on the Engine
side. Two REST endpoints stand in for it:

**Simulate an update event** (equivalent to the SSE `update` event above):
```bash
curl -X POST http://localhost:8080/engine/push \
  -H "Content-Type: application/json" \
  -d '{"submodelId":"CPS_SM","elementId":"Temperature","value":"80.0"}'
```

**Watch for the write back** by tailing the Engine's log for
`Writing correction back to gateway`, or by pointing `gateway.write.url`
at a throwaway HTTP listener (for example `python -m http.server`) to see the
PATCH arrive without needing a real Gateway to accept it.

---

## 5. Tutorial: connecting a Gateway from nothing

This walks through standing up the connection end to end, assuming you have a
Gateway that can serve the SSE stream described in section 2 and accept the
PATCH described in section 3.

1. **Point the Engine at your Gateway.** In `src/main/resources/application.properties`:
   ```properties
   gateway.url=http://localhost:8085/api/submodel/stream
   gateway.write.url=http://localhost:8085
   gateway.machineId=cnc-line1
   ```
   `gateway.machineId` must match whatever prefix your Gateway actually
   puts on its event keys, confirm this with whoever owns the Gateway before
   assuming a default.

2. **Start the Gateway first** (gateway project; build and run
   steps are in its README), so the Engine's very first connection attempt
   succeeds instead of needing to wait for its 5 second retry.

3. **Start the Engine:**
   ```bash
   mvn spring-boot:run
   ```
   The engine initializes itself automatically, no separate startup call
   needed (see [README.md](README.md#quick-start)).

4. **Confirm the connection in the log.** You should see:
   ```
   [GatewayStreamClient] Connecting to: http://localhost:8085/api/submodel/stream
   ```
   with no immediately following `Stream disconnected` line. If you do see a
   disconnect and retry loop, the Gateway is not reachable at that URL, check
   the port and that the Gateway process is actually up.

5. **Send a real value from your Gateway** for an element one of the loaded
   rules targets (see the rule table in [README.md](README.md#default-cnc-milling-machine-configuration)
   for the bundled defaults), and confirm the Engine reacted:
   ```bash
   curl http://localhost:8080/engine/history
   ```
   You should see a fresh `STABLE --DATA_PUSH_RECEIVED--> SYNCHRONIZING` entry,
   and further transitions if the value was high enough to fire a rule.

6. **Confirm the write back path** by pushing a value that fires a rule and
   watching your Gateway receive the PATCH once the corrective service
   completes. If nothing arrives, check `gateway.write.url` is set and
   check the Engine's log for `Write back failed` or `No write URL configured`.

---

## 6. Configuration reference

| Property | Meaning | Used by |
|---|---|---|
| `gateway.url` | SSE stream URL the Engine connects to | `GatewayStreamClient` |
| `gateway.write.url` | Base URL for the write back PATCH | `GatewayWriteClient` |
| `gateway.write.connect-timeout-ms` | Max time to connect for the write back PATCH (default `3000`, env `GATEWAY_WRITE_CONNECT_TIMEOUT_MS`) | `GatewayWriteClient` |
| `gateway.write.read-timeout-ms` | Max time to wait for the Gateway's answer (default `10000`, above the Gateway's own 5 s write timeout; env `GATEWAY_WRITE_READ_TIMEOUT_MS`). A timeout is logged as `Write back failed`. | `GatewayWriteClient` |
| `gateway.machineId` | Prefix expected on every event's `key` field, and used to build the outgoing routing key | both |
| `gateway.submodelId` | idShort of the live data submodel data is written into (default `CPS_SM`) | `StateChartExecutor` |

---

## 7. Troubleshooting

| Symptom | Likely cause | What to check |
|---|---|---|
| `Stream disconnected`, retrying every 5 seconds, forever | Gateway not running, or wrong URL or port | Confirm the Gateway process is up and `gateway.url` matches its actual address |
| Events arrive at the Gateway but nothing happens in the Engine | `machineId` prefix mismatch | Confirm `gateway.machineId` matches the prefix your Gateway actually puts on `key` |
| `Could not parse idShort/value from event`, event skipped | Event body does not match the expected shape | Compare your event's JSON against section 2 exactly, field names are case sensitive |
| `Write back failed` | Gateway unreachable, wrong `gateway.write.url` or the PATCH endpoint rejects the request | Confirm the URL and that the endpoint accepts PATCH with a plain value body |
| `No write URL configured, skipping write back` | `gateway.write.url` left empty | Set it, even for local testing against a throwaway listener |
