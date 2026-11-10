# Decorations

Independent JavaRailways add-on for decorative train/rail content. Decorations implements display definitions, captured package resources and optional SimpleDisplays template compilation and static first-frame rendering on train parts.

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

Eviction, service unregister, provider disable and Decorations disable close owned templates and release their references. Concurrent duplicate compilations close the loser; compilation finishing after retirement cannot enter the cache. Compiler/close callbacks run outside its lock. Cleanup attempts every owned template and reports failures. Decorations never closes the shared DisplayService. Runtime mounting uses a fresh privately owned template rather than borrowing this validation cache. It draws once with empty data and elapsed time zero, copies the resulting text primitives, and closes the private template on every path. The shared DisplayService remains open.

## Static train rendering

The host prepares each mounted extension once on its own worker pool. Decorations resolves the category default from the owning car’s captured source package, compiles that exact XML, and returns immutable text values. The logical canvas fits the physical part dimensions with preserved aspect ratio and centered margins. XML scripts execute once during drawing; train, PlaceholderAPI and viewer data are not supplied yet.

JavaRailways creates and mounts the text through its native or packet backend, applies the existing part transforms/animations, and uses the existing train audience. Missing Decorations, SimpleDisplays or a category default preserves the extension group and its children without generated text. Preparation errors are reported without breaking the car tree. Installing or re-enabling a provider affects the next model rebuild.

Closing the part registration asynchronously removes generated text while preserving original nodes. Detached static text can outlive a SimpleDisplays connection because it retains no template or engine handle. Dynamic refresh, per-viewer content and interior display distance/config/commands remain future work.
