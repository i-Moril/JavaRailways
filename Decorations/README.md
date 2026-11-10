# Decorations

Independent JavaRailways add-on for decorative train/rail content. Decorations implements display definitions, captured package resources and optional SimpleDisplays template compilation and dynamic XML/document-JS rendering on train parts.

JavaRailways owns common parts-tree structure, transforms/animations, generic config and source-package snapshots. Decorations registers `decorations:display` through the public host service. Without Decorations, trains load with intact extension nodes and children; captured source resources remain available.

A train manifest declares the resource directory:

```yaml
extensions:
  decorations:display:
    schema-version: 1
    directory: decorations
```

A standalone or inline part uses the existing parts tree:

```yaml
config-version: 1
name: cabin_lcd
type: decorations:display
schema-version: 1
config:
  category: cabin_side_lcd
  # Optional template ID from this car package or an enabled external style bundle.
  # template: destination
  interior: true
  # Optional fixed car/model HEAD side; omit when this screen has no side association.
  # door-side: left
  width: 0.8
  height: 0.45
parts: []
```

A part’s optional `config.template` takes precedence over its category default. Explicit template references must exist in the owning car’s captured resources or the enabled external style catalog, including for imported part geometry; an unknown explicit reference is rejected. Selection order is runtime fixed-unit/category override, part template, then package category default. Without any selection the group has no generated screen content.

Optional `decorations/displays.yml`:

```yaml
config-version: 1
templates:
  destination:
    file: displays/destination.xml
defaults:
  head_led: destination
```

File paths are relative to the captured extension directory, with forward slashes and no absolute/drive/dot/parent segments. Host capture enforces UTF-8 and safe real paths; Decorations validates YAML and local references. XML, including embedded document scripts, is stored as source. When SimpleDisplays is available, all declared templates are compiled during provider validation; malformed XML/layout or script syntax fails reload preparation with package/template/file context. Compilation parses JS without running it. Without SimpleDisplays, metadata and captured source continue to load normally. Template dimensions belong in XML; physical dimensions belong to the part.

Schemas: `schemas/display-part-v1.schema.json` adds display-specific validation to the host common part schema; nested tree validation is enforced by the registered provider. `schemas/displays-v1.schema.json` describes the resources file. Config payload and resources each default to schema-version 1; no legacy built-in screen reader is provided.

## Independent display styles

Install an independent style directory below `plugins/Decorations/styles/`, for example:

```text
styles/cabin-route/displays.yml
styles/cabin-route/displays/route.xml
```

The file uses the same `displays.yml` schema, with a bundle-qualified template ID:

```yaml
config-version: 1
templates:
  cabin-route.route:
    file: displays/route.xml
```

Bundle directory names use lowercase letters, numbers, underscores and hyphens; every template ID must start with the directory name and a dot. External bundles cannot supply category defaults. A car chooses this template with `config.template: cabin-route.route` or the existing fixed-unit/category style override. Local category defaults continue to select local templates. A duplicate ID between a car catalog and an external bundle is an error, with no implicit precedence.

Decorations captures all external XML sources once during enable and validates their syntax when SimpleDisplays is available. Each car's exact captured catalog is merged with that immutable enable-time catalog for validation, mounting and runtime style selection. Editing files requires restarting Decorations; drawing never reads the filesystem. A missing styles directory is allowed; an installed malformed bundle or missing explicitly referenced template produces a diagnostic rather than falling back. Ordinary files must be UTF-8, without symbolic links; source limits are 256 files, 4 MiB per file and 16 MiB total, with at most 16 directory levels and 1024 entries per bundle. Extra documentation files are ignored. This style installation does not change train package or payload schema versions.

## Build and dependencies

Install JavaRailways, then optionally SimpleDisplays. Decorations hard-depends JavaRailways and soft-depends SimpleDisplays. Absence/incompatibility of SimpleDisplays only disables its connection. This is a thin JAR: the host and optional provider supply their APIs/runtime libraries.

The API coordinate is `simpledisplays:simpledisplays-api:0.1.0-SNAPSHOT`, compile-only. Publish the reviewed independent SimpleDisplays source explicitly and record its revision:

```sh
./gradlew -p ../SimpleDisplays publishToMavenLocal -Dmaven.repo.local=/tmp/javarailways-simpledisplays-maven
./gradlew -PsimpleDisplaysRepository=/tmp/javarailways-simpledisplays-maven :Decorations:test :Decorations:verifyPluginJar
```

The property resolves only that API module; default repository is ~/.m2/repository. Current compile/close API and independent XML/document JS implementation baseline is `410af2e`. No API classes are copied into either plugin. Build/release preparation must pin artifact provenance for its actual API usage.

Disable closes part registration and optional connection. Service unregister/provider disable retires the optional connection; restarting Decorations is required to reconnect. Paper/Folia rendering and dynamic plugin replacement require separate acceptance; unit tests and descriptor flags alone do not prove them.

## Compilation lifecycle

Discovery runs before part registration, so already loaded packages are compiled during the registration check. Invalid existing packages are reported while their captured data remains intact. On future reloads, invalid templates reject the candidate and preserve the published baseline.

Each connection caches at most 64 compiled templates in least-recently-used order, keyed by exact XML source; current package resources declare no custom icon map. Repeated source across IDs/packages reuses compilation, while a changed source compiles separately. This is a bounded validation cache, not a store of mounted live templates. It has no active package-name alias or effect on snapshot selection; successful partial compilations from rejected candidates may remain cached until eviction.

Eviction, service unregister, provider disable and Decorations disable close owned templates and release their references. Concurrent duplicate compilations close the loser; compilation finishing after retirement cannot enter the cache. Compiler/close callbacks run outside its lock. Cleanup attempts every owned template and reports failures. Decorations never closes the shared DisplayService. Runtime mounting opens a privately owned template session rather than borrowing this validation cache. Each selected private template compiles once and draws repeatedly with detached data and elapsed ticks; changing the effective template closes it and lazily opens the replacement. Retirement closes the private template after any active invocation exits; the shared DisplayService remains open.

## Dynamic train rendering

The host opens each mounted extension session on its worker pool, then requests frames every10 source ticks (nominally2Hz), with an immediate refresh when operational context changes. One in-flight draw and one overwritten latest request prevent unbounded backlog. Decorations resolves the effective template from the owning car’s captured source package, lazily compiles that exact XML, and returns immutable text values. Each draw receives an isolated document/JS invocation. The canvas fits physical part dimensions with preserved aspect ratio and centered margins.

Simple XML bindings use top-level strings: `trainId`, `trainName`, `trainType` (empty when unknown), `carId`, `carNumber` (1-based physical car order), `destination`, `runType`, `currentStationName` and `nextStationName`. Operational aliases are empty strings when unknown; destination/runType come only from authored string values. JS additionally receives typed objects:

- `ctx.data.train`: `id`, `name`, `type` (string or null), `speedMetersPerSecond`, `reversed`, `derailed`, `carCount`.
- `ctx.data.car`: `id`, `index` (0-based physical frame order), `number` (1-based), `operatingEnd` (`head`/`tail` relative to this installed car, or null when unavailable). Operating end uses the same owner-captured control-end and installed-direction mapping as actual door execution; it is not inferred from car index or train name.
- `ctx.data.unit`: null or `{id, carIds}`, based on a complete registered fixed unit, independent of physical car index/reversal.
- `ctx.data.operational`: null without an active context; otherwise `{timetableId, operationalTripId, cycleIndex, values, stops, stationIndex, phase, plannedStopCount, completedStopCount, unresolvedRuns, currentStation, nextStation, currentRun, doorSide}`. `cycleIndex` and planned ticks are exact decimal strings.
- `stops` contains the complete known ordered call sequence for this exact fixed unit and operational trip (history plus future), and each entry contains `serviceId`, `runId`, `callId`, `stationId`, nullable `stationName`, `destinationId`, `status`, nullable `arrivalTick`/`departureTick`, and `completed`. PASS entries and repeated station visits remain distinct. Each entry also includes station `metadata`. `completed` and `completedStopCount` mean reached, including a STOP arrival; they do not mean departed or passed. `unresolvedRuns` explicitly lists future segments whose unit membership is unproven; their calls are not fabricated. The template input has no duplicate `remainingStops` list.
- `currentStation` is null without current stopped-placement proof; otherwise `{stationId, stationName, berthId}`. It does not claim a timetable call occurrence. `nextStation` is the first STOP/TERMINAL starting at the cursor when in transit, or after the cursor when at a station. It is null when progress is unknown/finished or future membership is unresolved. Selection uses occurrence index, so two visits to the same station are not confused. Unknown station names stay null.
- `stationIndex` is a zero-based index into `stops` or null. `phase` is `at_station` (cursor is the arrived stopping call), `in_transit` (cursor is the next unreached physical call, including PASS), `finished`, or `unknown` (both have null cursor). A stationary signal wait remains in transit; preparing the next movement does not by itself mark departure. No current index is guessed from station name, schedule, speed alone or completed counts.
- `ctx.elapsedTicks`: elapsed source ticks since this model session began. No wall-clock or frame-count guess is needed.

For a route window, use the authoritative cursor rather than the `completed` diagnostic flag. For example, within XML's `render(ctx)`:

```js
const op = ctx.data.operational;
const known = op && (op.phase === "at_station" || op.phase === "in_transit");
const start = known ? Math.max(0, op.stationIndex - 2) : 0;
const visible = known ? op.stops.slice(start, start + 8) : [];
const cells = visible.map((stop, slot) => ({
  stop,
  index: start + slot,
  passed: start + slot < op.stationIndex,
  stopping: stop.status !== "PASS"
}));
// Render cells with document.createElement(...); no world queries are needed.
// At arrival, the indexed station is still current, not passed.
```

The list may include only the proven parts of a future trip: check `unresolvedRuns` before describing the final known call as the destination. Destinations continue to come from explicit authored values. Unknown/finished cursors are null, not an end index or a guessed first station. This is a template-input change only; the timetable HTTP diagnostic API retains its separate planned/completed/remaining lists.


Use `bind` for a simple alias, for example `<span bind="destination">Unknown</span>`. A nonempty binding takes precedence over that span’s fallback/textContent; leave it off a node whose text is computed by JS. For example:

```xml
<screen width="128" height="32">
  <span id="speed" style="left:64;top:0;font-size:9">Train</span>
  <script><![CDATA[
    function render(ctx) {
      if (!ctx.data.train) return; // Keep fallback text before the first train input.
      document.getElementById("speed").textContent =
        ctx.data.train.name + " " + (ctx.data.train.speedMetersPerSecond * 3.6).toFixed(0) + " km/h";
    }
  ]]></script>
</screen>
```

Basic facts are sampled from the immutable train frame; operational facts are freshly captured after dispatcher/postTick in the same world owner boundary. Samples refresh every10ticks or on domain display revision changes. Each geometry envelope retains the latest same-stream immutable sample so region-task coalescing cannot discard a pending SET/reset; it then passes through the existing stream/revision gate. Workers never read live trains or players. Parked trains receive a clock refresh through the existing world tick only while an extension model is active. Train split/join publication stream changes retire the old model session before applying new identity data. Car indices do not imply fixed-unit membership or travel order under reversal.

JavaRailways applies part transforms/animations and the existing train audience through native or packet backends. Same-size frames update existing text entities; growth/shrink replaces generated leaves and EMPTY clears them. Routine draw/create failure preserves successful output within the same operational context and reports one diagnostic per failure episode. A changed unit/operational context or effective style first clears generated text and rejects old queued/completed results; a failed replacement remains blank. The private template session and elapsed clock remain in use. Missing Decorations or SimpleDisplays preserves original groups/children. A screen with captured templates but no default remains available for a runtime override. Installing/re-enabling a provider affects the next model rebuild.

Closing registration invalidates queued/late output immediately and asynchronously observes both generated visual deletion and actual session close. SimpleDisplays retirement suppresses further session output and releases private templates without closing the shared service. No compiled runtime template is borrowed from the validation LRU.

Current mounted output is shared across the existing train audience. Per-viewer mounted content is deferred alongside PlaceholderAPI and is outside current acceptance; interior distance and fixed-unit style overrides are implemented with integrated acceptance deferred. The independent library's viewer capability remains available; Bukkit PlaceholderAPI adaptation is excluded from this task. Paper server-side probes do not establish game-client or real Folia acceptance.


## Interior display distance

`plugins/Decorations/config.yml` supplies the initial policy:

```yaml
displays:
  interior-distance:
    default-blocks: 32.0
    max-blocks: 128.0
```

Values must be finite numbers, `0 <= default-blocks <= max-blocks <= 4096`, with a positive maximum. Unknown keys/duplicate keys and string numbers are rejected. The plugin creates this config from its packaged default on first enable.

Players use `/decorations display-distance` to query, `/decorations display-distance <blocks>` to set, and `/decorations display-distance reset` to restore the server default. Permission `decorations.display-distance` defaults to true. Zero hides interior screen content; settings last for the current login and clear on quit or plugin disable. A change requests an audience refresh without requiring movement.

Distance is from the player's eye to each screen part's fully transformed world origin, including ancestor/car rotation, part offset and animation; it is never measured to the car center. The ordinary car audience is still the candidate set. `interior: false` follows that audience without an extra screen distance. The host filters generated text only, keeping the original part subtree's normal visibility.

When no viewer is eligible the group stops requesting JS frames and rejects obsolete in-flight output. It retains latest input and source-clock origin for resuming. Warm compilation and already allocated hidden native entities may remain; this is not a claim of eliminating every server-side entity cost. No cabin occupancy, camera-cone, backface or occlusion policy is enabled.

Implementation and test cases are present; this batch has compilation checks only. Integrated tests, real Paper/Folia and client acceptance are deferred until the remaining work is complete, per the requested workflow.


Runtime styles are keyed by world UUID, actual fixed-unit ID (`ctx.data.unit.id`) and category. They follow the unit through reversing, splitting, coupling and model rebuilds; a player leaving does not clear them. They are runtime-only: accepted world unload, Decorations disable and server restart clear them. No train-name or car-index lookup substitutes for unit identity.

Players with `decorations.display-style` (default OP) use their current world:

```text
/decorations display-style <unitId> <category>
/decorations display-style <unitId> <category> <templateId>
/decorations display-style <unitId> <category> reset
```

The query reports the override, bound screen count and common available templates. Setting validates the ID against every currently bound screen’s exact captured catalog before changing anything. Overrides survive temporarily having no bound screens, but a new set requires a bound catalog. A later resource change that removes the selected template fails locally; reset restores defaults. Only screens whose effective selection changes clear and redraw, preserving their source clock and the car model.

Other extensions can load `work.art1st.javarailways.decorations.api.display.DisplayStyleService` from Bukkit ServicesManager for immutable queries and set/reset. This service exposes no SimpleDisplays or host-private types. Hidden screens continue cheap detached unit-binding updates without evaluating XML/JS; private templates open only when drawing resumes.


Train model inline parts inherit the containing car/part document's `config-version`; do not repeat it inside `body-parts`, `wheel-parts` or nested `parts`. Standalone part files still require `config-version: 1`. Part animation definitions now use a map keyed by animation name (no inner `name`); installation and action execution entries remain ordered lists. Existing development packages can use [the one-time converter](../scripts/model-schema-consistency/README.md).

Station metadata is supplied as structured data on `ctx.data.operational.currentStation.metadata`, `nextStation.metadata`, and every `stops` entry's `metadata`. Each existing station defaults to `{}`; unavailable current/next station objects remain `null`. For example:

```js
function render(ctx) {
  const station = ctx.data.operational && ctx.data.operational.nextStation;
  const transfers = station && station.metadata.transfers;
  document.getElementById("transfers").textContent = Array.isArray(transfers)
    ? transfers.map(line => line.name).filter(name => typeof name === "string").join(" / ")
    : "";
}
```

The authenticated WebUI Stations page edits that JSON directly, once per world/station rather than per berth. Fields such as `transfers` are author conventions; custom fields remain intact. Metadata is never executed. Saves update the live station; existing periodic and normal shutdown world saves persist it in the SimpleATO infrastructure snapshot. Screens capture changes on the next regular input sample (at most 10 simulation ticks under normal ticking), without rebuilding the model. Whole-template input limits still apply when many stop metadata objects are included. JSON data retains its numeric text in persistence; use strings for identifiers or integers that require precision beyond JavaScript's safe integer range.

Lines are indexed by their actual infrastructure line ID in `ctx.data.lines`, including lines referenced only by transfer information. The index belongs to the current world and contains `{id, displayName, metadata, color?}` records. It is captured on the simulation owner with the regular input sample, and missing IDs have no record:

```js
const transferLine = ctx.data.lines["metro_2"];
const transferName = transferLine
  ? (transferLine.metadata.nameEn || transferLine.displayName)
  : "";
const currentRun = ctx.data.operational && ctx.data.operational.currentRun;
const runNotice = currentRun ? currentRun.metadata.notice : null;
```

`currentRun` is `null` outside an unambiguous actual run scope; otherwise it contains `{serviceId, runId, lineId, metadata}`. Its timetable and cycle are those of the enclosing operational context. It is captured from the active physical service/entered segment, not guessed from the first remaining stop or `diagramLineId`. Line metadata and run metadata are independent of operational display `values`; templates decide which values to use. Updates use existing sampled refresh and immutable data; no live-world query is made by JS. The complete catalog and stop data remain subject to the library's existing aggregate input limits.


### This-side and opposite-side opening

An installation may set `config.door-side: left` or `right`, in the fixed installed car/model HEAD basis. Keep this value unchanged on turnback. It associates the screen with a side; it does not change which doors open or infer side from screen rotation/position. The field is optional and uses existing display schema-version1.

`ctx.data.operational.doorSide` is the effective **operating-direction** LEFT/RIGHT/BOTH/NONE at a proven current stop, or null when unknown. It incorporates station-program/accepted explicit opening overrides and a committed reversal. It is retained with actual arrival evidence when a future mission is prepared, cleared by physical departure or lost/ambiguous execution evidence, and never predicts the next stop. It describes effective opening side, not measured door animation/aperture.

Decorations combines it with `car.operatingEnd` and the installation before calling SimpleDisplays:

```js
// Derived per installation; metadata remains detached and read-only.
ctx.data.display.doorSide;     // "left" | "right" | null (fixed installed side)
ctx.data.display.openingSide;  // "this" | "opposite" | "both" | "none" | null
```

LEFT/RIGHT flips when the car's operating end is tail, then compares to the fixed installation side. For example, raw LEFT + head + left installation is `this`; after reversal raw RIGHT + tail + the same left installation is still `this`, preserving the physical platform side. A screen on the opposite wall reports `opposite`. Missing installation/end facts suppress directional hints (null). Known BOTH/NONE produce `both`/`none` even without a side association; neither is conflated with unknown.

Templates translate these stable values into their15-second shared language pages, for example:

```js
const english = Math.floor(ctx.elapsedTicks / 300) % 2 === 1;
const labels = english
  ? {this:"Doors open on this side", opposite:"Doors open on the opposite side", both:"Doors open on both sides", none:""}
  : {this:"本侧开门", opposite:"对侧开门", both:"两侧开门", none:""};
const side = ctx.data.display.openingSide;
document.getElementById("door").textContent = side === null ? "" : (labels[side] || "");
```

The host captures these values on its simulation owner and invalidates old-direction output when the operating end/side changes. Workers perform no live train/player lookups. Actual template/glyph rendering and game-client door-indicator acceptance remain separate from this data integration.
