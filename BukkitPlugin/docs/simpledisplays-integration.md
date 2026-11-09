# Optional SimpleDisplays API dependency

Phase 1 consumes `simpledisplays:simpledisplays-api:0.1.0-SNAPSHOT` from the independent
SimpleDisplays repository, baseline commit `21f7686`. There is no public release repository
yet. Build/release preparation must publish that reviewed source revision explicitly:

```sh
# From the JavaRailways checkout; adjust the independent source path as needed.
./gradlew -p ../SimpleDisplays publishToMavenLocal -Dmaven.repo.local=/tmp/javarailways-simpledisplays-maven
./gradlew -PsimpleDisplaysRepository=/tmp/javarailways-simpledisplays-maven :BukkitPlugin:test :BukkitPlugin:shadowJar
```

The property selects a Maven repository directory or URL, restricted to the API module.
Without it the same coordinates resolve from `~/.m2/repository`. Keep source revision and
artifact provenance with release evidence: the development SNAPSHOT coordinate alone is
not immutable. Do not substitute copied API classes or the full SimpleDisplays plugin JAR.

The API is compile-only (also present only on the test classpath). The deployable host JAR
must contain no `simpledisplays/api/` classes. Install the independent SimpleDisplays plugin
on the server to provide the API identity and Bukkit `DisplayService` registration.
Generated `softdepend` orders its enable before JavaRailways; the host does not load optional
API types when the named plugin is missing or disabled. An incompatible API, missing service,
or wrong service owner disables only this integration, with a warning. Provider disable,
service unregister, and host disable release the retained service and lifecycle listener.
Re-enabling/replacing the provider requires restarting JavaRailways; no hot reconnection is
implemented. This phase creates no screens and changes no package or persistence schema.

## Package display resources

Train packages may include an optional `displays.yml` at their root:

```yaml
config-version: 1
templates:
  destination:
    file: displays/destination.xml
  cabin_stops:
    file: displays/cabin_stops.xml
defaults:
  head_led: destination
  side_led: destination
  cabin_side_lcd: cabin_stops
```

Template names are local to the source package; several categories may reference the same template. `templates` is required, and may be empty. `defaults` may be omitted. Names/categories must be nonempty and contain no whitespace. There is no template type or duplicate width/height declaration: XML defines logical dimensions and may contain inline scripts using the independent SimpleDisplays document API. Physical installation dimensions remain in the screen part. The editor schema is `schemas/train-model/displays-v1.schema.json`.

Files are UTF-8 XML, addressed relative to the package root with forward slashes. Absolute paths, Windows drive prefixes, backslashes, empty/dot/parent path segments and files outside the real package directory are rejected. Symlinks that remain inside the package are allowed; escaping or broken links, missing files and directories are rejected. Errors identify the package configuration and the relevant template/file or category. Unknown fields, bad local references and duplicate YAML keys are errors. No `displays.yml` means empty display resources.

Package preparation reads raw XML, including inline script text, and stores it with category defaults in the existing source-package snapshot. This loading step does not require SimpleDisplays and does not compile or execute XML/JS. Actual template compilation/layout validation and mounted rendering belong to later integration stages; successful package loading does not mean a screen is rendered.

Snapshot identity includes XML source, file declarations and default mappings, independently of YAML map insertion order. Existing fixed snapshots retain their captured content after files change. Each source car package keeps its own display resources in mixed-package trains. Empty display resources are omitted from the existing snapshot format so screen-free package hashes remain unchanged; manifest/snapshot versions are unchanged and no legacy runtime reader is added.

The independent SimpleDisplays XML/document implementation is at `410af2e`; this package-loading slice continues using the established API-free boundary and does not update the compile-only dependency or add optional-provider calls. When later stages consume the viewer context, publish and record the exact reviewed API artifact revision instead of relying on SNAPSHOT coordinates alone.
