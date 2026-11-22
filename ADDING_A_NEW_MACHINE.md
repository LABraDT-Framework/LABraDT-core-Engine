# Adding a New Machine

This guide walks through everything needed to bring a new machine under the Engine's
governance: a new AASX file, a new instance configuration and a running process
alongside any machines already deployed. It assumes no prior familiarity with this
repository beyond what is in the main [README.md](README.md).

If you only need the short version: **the Java code never changes.** Every step below
is either writing an AASX file or writing a properties file. If you find yourself about
to edit a `.java` file to support a new machine, stop, that almost certainly means a
rule, condition or service contract is missing, not that the new machine itself needs
special handling. See [Extension Points](README.md#extension-points) in the main README
for the three places where the engine is meant to be extended, and confirm the gap is
really there before writing code.

---

## 1. The model: one engine process per machine

The Engine is single machine by design: one process loads one AASX file, holds one
rule set and runs one state machine instance. There is no per request machine
parameter and no multi tenant routing inside the JVM.

To govern several machines at once, you run **several copies of the same JAR**, each
as its own OS process, each pointed at its own AASX file and its own port through Spring
configuration. Nothing is duplicated in source, only configuration and data differ
between processes. This is also the cleanest way to demonstrate the thesis's core
claim: the same code, never recompiled, governs a different machine the moment you
hand it a different AASX file.

Each running instance needs to be unique in exactly three places:

| What | Why it must be unique | Example for a 3rd machine |
|------|------------------------|-----------------------------|
| `server.port` | Two processes cannot bind the same TCP port | `8082` is taken by the Service Manager, pick for example `8083` |
| `spring.datasource.url` (H2 file path) | An H2 database in file mode locks its file, sharing one across processes causes startup failures or corrupted state | `jdbc:h2:file:./data/machinetool-db-<machine-id>` |
| `engine.aasx.path` and `engine.rules.submodel.entry` | This is what actually makes the instance govern a *different* machine | `src/main/resources/<Machine>.aasx` |

Everything else in the properties file (gateway URL, service execution URL, retry
settings) can usually stay the same if all machines share the same Gateway and Service
Manager deployment. Only `engine.callback.url` needs to match the instance's own port,
since that is where the Service Manager sends results back to.

---

## 2. Step by step: building the new machine's AASX file

The AASX file is the single source of truth for what the new machine's Engine instance
does. It is a ZIP archive (the official AAS packaging format) containing an XML
environment with two submodels the Engine actually reads, `Rules_SM` and
`StateChart_SM`, plus some bookkeeping XML that makes the package well formed.
The shipped `MachineTool.aasx` and `Molder.aasx` still spell them `Rules-SM` and
`StateChart-SM` (and `CPS-SM` as `targetSubmodel`); the Engine renames them to the
underscore form on import (`AASXImporter.java:43`), so both spellings work.

### 2.1 Decide on a machine identity

Pick a short, stable identifier, for example `molder-1` or `cnc-2`. This string is used:

- as `gateway.machineId` in the properties file (it must match what the Gateway
  actually streams, for example data keys like `molder-1.MeltTemperature`)
- as a naming convention for the AASX file, the internal AAS folder name and the H2
  database file, purely for your own clarity. The Engine does not parse the filename.

### 2.2 Extract an existing AASX as a template

The fastest correct starting point is always an existing AASX, not a blank file.
Starting from one guarantees the ZIP structure and XML schema are valid.

```bash
cd src/main/resources
python -c "import zipfile; zipfile.ZipFile('MachineTool.aasx').extractall('aasx_template')"
```

You will see:

```
aasx_template/
├── _rels/.rels
├── aasx/
│   ├── aasx-origin
│   ├── _rels/aasx-origin.rels
│   └── MachineTool-AAS/MachineTool-AAS.aas.xml   ← the file you actually edit
└── [Content_Types].xml
```

Only `MachineTool-AAS.aas.xml` needs editing. Everything else is packaging
boilerplate that stays structurally identical.

### 2.3 Edit the rules

Open `MachineTool-AAS.aas.xml` and find the `Rules_SM` submodel. Each rule is a
`submodelElementCollection` with this shape:

```xml
<submodelElementCollection>
  <idShort>Rule_1</idShort>
  <value>
    <property><idShort>targetSubmodel</idShort><value>CPS_SM</value></property>
    <property><idShort>targetElement</idShort><value>MeltTemperature</value></property>
    <property><idShort>condition</idShort><value>greater_than</value></property>
    <property><idShort>threshold</idShort><value>250.0</value></property>
    <property><idShort>action</idShort><value>melt-cooling-service</value></property>
    <property><idShort>cooldownMs</idShort><value>30000</value></property>
    <property><idShort>description</idShort><value>Melt temperature overload protection</value></property>
  </value>
</submodelElementCollection>
```

Replace `targetElement` with the sensor name your machine's Gateway actually emits,
pick a `condition` from the registered operators (`greater_than`, `less_than`,
`equals`, `out_of_bounds`, `wear_average`, see the main README for each one's exact
syntax), and give `action` a service name that the Service Manager will recognize.
Add or remove `Rule_N` blocks freely, the parser works with any number of rules.

**Do not touch `StateChart_SM`** unless you specifically want different behavioral
logic for this machine (a different number of retries, a different recovery timeout,
and so on). The eight state lifecycle is not tied to any specific machine by design
and can be copied across machines unchanged. The `FILE_EXTRACT` and `FILE_REPACK`
actions declared in the `INITIALIZING` and `TERMINATING` states are not read by the
engine at runtime. `StateChartExecutor.executeAction()` treats both as log only,
doing nothing. The AASX file and internal entry path are entirely controlled by the
`engine.aasx.path` and `engine.rules.submodel.entry` properties in this instance's
`application-<machine-id>.properties` (see step 3 below). That is the only place
these need to be updated.

### 2.4 Rename the internal AAS folder

If you want the new machine's AASX to have its own internal folder name (recommended
for clarity, for example `Molder-AAS` instead of `MachineTool-AAS`), this requires
care. A plain rename is not enough, because **one other file inside the archive
references that folder by exact path**, `aasx/_rels/aasx-origin.rels`. If you rename
the folder without fixing this file, the AASX is internally inconsistent: any
AAS-compliant tool that resolves the relationship will look for a path that no longer
exists in the archive, even though the Engine's own importer may not immediately
notice.

Concretely:
1. Rename the `aasx_template/aasx/MachineTool-AAS/` folder to for example
   `aasx_template/aasx/Molder-AAS/` and the file inside it to `Molder-AAS.aas.xml`.
2. Open `aasx_template/aasx/_rels/aasx-origin.rels` and replace the old path
   (`/aasx/MachineTool-AAS/MachineTool-AAS.aas.xml`) with the new one
   (`/aasx/Molder-AAS/Molder-AAS.aas.xml`).

### 2.5 Repack into a new `.aasx` file

```bash
python -c "
import zipfile
src = 'MachineTool.aasx'          # used only as a packaging skeleton
dst  = 'Molder.aasx'
edited = 'aasx_template/aasx/Molder-AAS/Molder-AAS.aas.xml'
new_bytes = open(edited, 'rb').read()

zin  = zipfile.ZipFile(src, 'r')
zout = zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED)
for item in zin.infolist():
    name = item.filename.replace('\\\\', '/')
    data = zin.read(item.filename)
    if name.lower().endswith('machinetool-aas.aas.xml'):
        data = new_bytes
        name = name.replace('MachineTool-AAS', 'Molder-AAS')
    elif 'MachineTool-AAS' in name:
        name = name.replace('MachineTool-AAS', 'Molder-AAS')
    zout.writestr(name, data)
zin.close(); zout.close()
print('Built', dst)
"
```

Then patch the `.rels` reference inside the new archive (this is the same fix as
step 2.4, applied directly to the zip rather than the extracted folder):

```bash
python -c "
import zipfile, shutil
src = 'Molder.aasx'
tmp = src + '.tmp'
zin  = zipfile.ZipFile(src, 'r')
zout = zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED)
for item in zin.infolist():
    data = zin.read(item.filename)
    if item.filename.endswith('aasx-origin.rels'):
        data = data.replace(b'MachineTool-AAS/MachineTool-AAS.aas.xml', b'Molder-AAS/Molder-AAS.aas.xml')
    zout.writestr(item, data)
zin.close(); zout.close()
shutil.move(tmp, src)
print('Patched rels in', src)
"
```

### 2.6 Verify the new AASX before wiring it into the Engine

```bash
python -c "
import zipfile
z = zipfile.ZipFile('Molder.aasx')
print(z.namelist())
print(z.read('aasx/_rels/aasx-origin.rels').decode())
"
```

Confirm all five expected entries are present, that the rels file's `Target` points to
the *new* folder name and that the XML entry's content is the edited rules, not a
leftover copy of the template.

### 2.7 Clean up

```bash
rm -rf aasx_template/
```

The temporary extraction folder has no purpose once the `.aasx` is built. It is not
read by the Engine at runtime.

---

## 3. Step by step: configuring the new instance

Create `src/main/resources/application-<machine-id>.properties`, for example
`application-molder1.properties`. Spring Boot merges this on top of the base
`application.properties` when the matching profile is active: any key present in
both files takes its value from the profile specific one.

```properties
# Second machine instance: <describe the machine here>
# Run with: mvn spring-boot:run -Dspring-boot.run.profiles=<machine-id>

engine.aasx.path=src/main/resources/Molder.aasx
engine.rules.submodel.entry=aasx/Molder-AAS/Molder-AAS.aas.xml

engine.storage.path=storage-molder1
spring.datasource.url=jdbc:h2:file:./data/machinetool-db-molder1;DB_CLOSE_ON_EXIT=FALSE;DB_CLOSE_DELAY=-1

gateway.machineId=molder-1
gateway.url=http://localhost:8085/api/submodel/stream
gateway.write.url=http://localhost:8085
service.execution.url=http://localhost:8082/services/trigger

server.port=8081
engine.callback.url=http://localhost:8081/engine/result
```

Only copy the keys that genuinely differ for this instance. Spring leaves any key
you omit at its value from the base `application.properties`. Retry and recovery
settings (`engine.retry.max`, `engine.service.retryDelayMs`,
`engine.error.recoveryTimeoutMs`) typically do not need to be repeated unless this
specific machine should behave differently. Double check the exact property name
before adding one of these: a misspelled key is not rejected by Spring, it is simply
never read, and the engine silently falls back to that property's default instead.

**Port choice matters beyond avoiding collisions with other Engine instances.** Also
check it does not collide with the Gateway (8085) or Service Manager (8082) ports.

---

## 4. Running the new instance

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=molder1
```

Watch the startup log for two confirmations:

```
The following 1 profile is active: "molder1"
Tomcat started on port(s): 8081 (http)
```

If the profile name is misspelled or the file is not named exactly
`application-<profile>.properties`, Spring silently falls back to the base
configuration. The second confirmation (the actual port) is the more reliable check,
since a port collision with an instance already running would also fail loudly.

It initializes itself automatically once Spring Boot finishes booting, no
separate curl call needed. If you ever want to manually reinitialize this
instance later, for example to reset its state without restarting the
process:

```bash
curl http://localhost:8081/engine/startup
```

---

## 5. Verifying it end to end

### 5.1 Confirm it loaded the right rules

```bash
curl http://localhost:8081/engine/state
```

This reports the loaded rule count for this instance. To see the actual
rules that were parsed, check the startup log for lines reading
`Loaded rule: <description>`, one per rule in the new machine's AASX.
Confirm the count and the descriptions match what you wrote in step 2.3,
not the default machine's rules.

### 5.2 Confirm storage isolation

```bash
curl http://localhost:8081/engine/submodel-data
```

A freshly started new instance should show no sensor history yet. If it shows the
other machine's historical values, the `spring.datasource.url` override did not take
effect. Double check the profile file is actually being picked up (see the profile
log line in step 4).

### 5.3 Simulate a sensor push without waiting for the Gateway

```bash
curl -X POST http://localhost:8081/engine/push \
  -H "Content-Type: application/json" \
  -d '{"submodelId":"CPS_SM","elementId":"MeltTemperature","value":"260.0"}'
```

This should drive the instance through SYNCHRONIZING, DECIDING and TRIGGERING, firing
whatever `action` your rule specifies. Confirm with:

```bash
curl http://localhost:8081/engine/history
```

---

## 6. Coordinating with the other components

The Engine instance is only one third of the working pipeline. Before the new
machine is fully live end to end, two things outside this project must also be in
place. For the full contract and a connect and run tutorial for each boundary, see
[CONNECTING_TO_THE_GATEWAY.md](CONNECTING_TO_THE_GATEWAY.md) and
[CONNECTING_TO_SERVICE_EXECUTION.md](CONNECTING_TO_SERVICE_EXECUTION.md).

1. **Gateway (gateway project):** it must actually be streaming data for this
   machine's `machineId` over the shared SSE endpoint (for example keys like
   `molder-1.MeltTemperature`), and it must accept write back `PATCH` requests
   addressed to this `machineId`.
2. **Service Manager (services project):** it must have the service IDs referenced
   in the new machine's `action` properties (for example `melt-cooling-service`)
   registered and runnable, and it must be able to call back to whichever port this
   instance's `engine.callback.url` points at.

Until both are wired up, this instance's Gateway connection will keep logging
`Stream disconnected`, and any service this instance tries to trigger will
fail with a connection error. That is expected: it is a statement about those
two components' configuration, not a problem with the Engine instance itself,
which can be fully verified in isolation using the manual pushes made with
curl shown in section 5.

---

## 7. Common mistakes

| Symptom | Cause | Fix |
|---|---|---|
| `curl .../engine/state` shows the wrong rule count for this instance | Hit the wrong port, or the wrong instance is actually running | Confirm which port this instance printed at startup, and curl that one specifically |
| Second instance will not start, port already in use | `server.port` not overridden, or it collides with the Gateway or Service Manager | Check every running process's port, then pick a free one |
| Second instance shows the first instance's sensor history | `spring.datasource.url` not overridden, or the override is missing the `;DB_CLOSE_ON_EXIT=FALSE` suffix used elsewhere | Copy the H2 URL pattern exactly, with a unique file path |
| Retry delay or recovery timeout does not seem to apply | Property key misspelled, for example `engine.retry.delay.ms` instead of `engine.service.retryDelayMs` | Use the exact property names from `StateChartExecutor`'s `@Value` annotations, listed in section 3 above |
| AASX loads but rules are empty | `engine.rules.submodel.entry` still points at the old internal XML path | Update it to match the renamed folder from step 2.4 |
| `Entry not found in stream` at startup | The `.rels` file inside the AASX still references the old folder name | Rerun the patch script in step 2.5, or reverify with step 2.6 |
| New rule never fires | `targetElement` in the rule does not exactly match the element name the Gateway actually pushes | Confirm the exact `idShort` or key spelling with whoever owns the Gateway for this machine |
| Profile silently ignored, base configuration used | Properties file misnamed (it must be exactly `application-<profile>.properties` in `src/main/resources`) | Check spelling and that the profile flag matches the filename suffix |

---

## 8. Reference: minimal checklist

- [ ] Picked a machine ID and a free port
- [ ] Extracted an existing AASX as a packaging template
- [ ] Edited `Rules_SM` with the new machine's rules
- [ ] Left `StateChart_SM` untouched, or deliberately changed it and updated the
      `FILE_EXTRACT` and `FILE_REPACK` paths to match
- [ ] Renamed the internal AAS folder and patched `aasx-origin.rels` to match
- [ ] Repacked into a new `.aasx` file and verified its contents
- [ ] Created `application-<machine-id>.properties` with a unique port, AASX path and
      H2 database URL, using the exact property names from section 3
- [ ] Started the instance with `-Dspring-boot.run.profiles=<machine-id>` and
      confirmed the port and profile in the startup log
- [ ] Verified rules, storage isolation and a manual push and result cycle work in
      isolation
- [ ] Confirmed with the Gateway and Service Manager owners that this machine ID and
      these service IDs are registered on their side
