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
  interior: true
  width: 0.8
  height: 0.45
parts: []
```

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

Eviction, service unregister, provider disable and Decorations disable close owned templates and release their references. Concurrent duplicate compilations close the loser; compilation finishing after retirement cannot enter the cache. Compiler/close callbacks run outside its lock. Cleanup attempts every owned template and reports failures. Decorations never closes the shared DisplayService. Runtime mounting opens a privately owned template session rather than borrowing this validation cache. It compiles once and draws repeatedly with detached data and elapsed ticks. Retirement closes the private template after any active invocation exits; the shared DisplayService remains open.

## Dynamic train rendering

The host opens each mounted extension session on its worker pool, then requests a frame at most every10 source ticks (nominally2Hz). One in-flight draw and one overwritten latest request prevent unbounded backlog. Decorations resolves the category default from the owning car’s captured source package, compiles that exact XML once per session, and returns immutable text values. Each draw receives an isolated document/JS invocation. The canvas fits physical part dimensions with preserved aspect ratio and centered margins.

Simple XML bindings use top-level strings: `trainId`, `trainName`, `trainType` (empty when unknown), `carId`, `carNumber` (1-based physical car order). JS additionally receives typed objects:

- `ctx.data.train`: `id`, `name`, `type` (string or null), `speedMetersPerSecond`, `reversed`, `derailed`, `carCount`.
- `ctx.data.car`: `id`, `index` (0-based physical frame order), `number` (1-based).
- `ctx.elapsedTicks`: elapsed source ticks since this model session began. No wall-clock or frame-count guess is needed.

For example:

```xml
<screen width="128" height="32">
  <span id="speed" style="left:64;top:0;font-size:9" bind="trainName">Train</span>
  <script><![CDATA[
    function render(ctx) {
      if (!ctx.data.train) return; // Keep fallback text before the first train input.
      document.getElementById("speed").textContent =
        ctx.data.train.name + " " + (ctx.data.train.speedMetersPerSecond * 3.6).toFixed(0) + " km/h";
    }
  ]]></script>
</screen>
```

Facts are captured from the same immutable physics frame as each car update, then carried through the existing stream/revision gate. Workers never read live trains or players. Parked trains receive a clock refresh through the existing world tick only while an extension model is active. Train split/join publication stream changes retire the old model session before applying new identity data. Car indices do not imply fixed-unit membership or travel order under reversal.

JavaRailways applies part transforms/animations and the existing train audience through native or packet backends. Same-size frames update existing text entities; growth/shrink replaces generated leaves and EMPTY clears them. Draw/create failure preserves previous successful output where available and reports one diagnostic per failure episode. Missing Decorations, SimpleDisplays or category default preserves original groups/children. Installing/re-enabling a provider affects the next model rebuild.

Closing registration invalidates queued/late output immediately and asynchronously observes both generated visual deletion and actual session close. SimpleDisplays retirement suppresses further session output and releases private templates without closing the shared service. No compiled runtime template is borrowed from the validation LRU.

Current mounted output is shared across the existing train audience. Per-viewer mounted output, operational destination/stop data, interior display distance/config/commands and style switching remain subsequent work. The independent library's viewer capability remains available; Bukkit PlaceholderAPI adaptation is excluded from this task. Paper server-side probes do not establish game-client or real Folia acceptance.
