# Decorations

Independent JavaRailways add-on for decorative train/rail content. This batch implements only display definitions, raw package resources and the optional SimpleDisplays connection. It does not yet create screen visuals.

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

File paths are relative to the captured extension directory, with forward slashes and no absolute/drive/dot/parent segments. Host capture enforces UTF-8 and safe real paths; Decorations validates YAML and local references. XML, including embedded document scripts, is stored as source and is not compiled in this batch. Template dimensions belong in XML; physical dimensions belong to the part.

Schemas: `schemas/display-part-v1.schema.json` adds display-specific validation to the host common part schema; nested tree validation is enforced by the registered provider. `schemas/displays-v1.schema.json` describes the resources file. Config payload and resources each default to schema-version 1; no legacy built-in screen reader is provided.

## Build and dependencies

Install JavaRailways, then optionally SimpleDisplays. Decorations hard-depends JavaRailways and soft-depends SimpleDisplays. Absence/incompatibility of SimpleDisplays only disables its connection. This is a thin JAR: the host and optional provider supply their APIs/runtime libraries.

The API coordinate is `simpledisplays:simpledisplays-api:0.1.0-SNAPSHOT`, compile-only. Publish the reviewed independent SimpleDisplays source explicitly and record its revision:

```sh
./gradlew -p ../SimpleDisplays publishToMavenLocal -Dmaven.repo.local=/tmp/javarailways-simpledisplays-maven
./gradlew -PsimpleDisplaysRepository=/tmp/javarailways-simpledisplays-maven :Decorations:test :Decorations:verifyPluginJar
```

The property resolves only that API module; default repository is ~/.m2/repository. Current optional service API baseline is `21f7686`; independent XML/document JS implementation is `410af2e`. No API classes are copied into either plugin. Future compilation/rendering work must pin artifact provenance for its actual API usage.

Disable closes part registration and optional connection. Service unregister/provider disable retires the optional connection; restarting Decorations is required to reconnect. Paper/Folia rendering and dynamic plugin replacement require separate acceptance; unit tests and descriptor flags alone do not prove them.
