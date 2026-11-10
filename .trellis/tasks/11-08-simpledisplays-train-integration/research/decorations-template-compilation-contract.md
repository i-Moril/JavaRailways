# Decorations template compilation slice — 2026-11-10

User approved committing the foundation and continuing. Foundation committed as 95935639. This next independently verifiable slice compiles captured XML during Decorations provider validation; actual train runtime mounting needs a separate renderer/audience contract and remains the next stage.

## Behavior gap and owner

DisplayPartProvider currently only parses YAML/references and raw XML. An installed SimpleDisplays connection should validate every declared template through the actual library compile API, so invalid XML/layout/embedded script syntax rejects reload preparation with package/template/file context. Without SimpleDisplays, the same metadata/raw-resource loading continues normally. No disk read or source replacement after snapshot capture.

Production flow: host detached captured resources -> DisplayResourceParser -> Decorations DisplayPartProvider -> optional API-free connection method -> typed SimpleDisplays compile/cache. Discovery must precede part registration so late validation of existing packages can compile templates when available. Provider retirement/unregister/Decorations disable must close cached templates and drop service references. Existing published model/resource snapshots remain untouched on validation failure.

## Scope

- Update DisplayPartProvider to accept an injected resource validation callback with a harmless default for metadata-only tests; plugin composition supplies the real optional connection callback.
- OptionalSimpleDisplays/SimpleDisplaysConnection descriptors stay API-free, including new method signatures; only the reflectively loaded binding/cache may link DisplayService/DisplayTemplate.
- Use the reviewed independent SimpleDisplays 410af2e API and implementation for local Maven publication/runtime evidence. Compile through public API only, no private implementation dependency in production or copied source.
- Each connection owns at most 64 compiled templates. Key by exact XML source (no icons are declared in current resource schema), reuse across package/template IDs, evict oldest recently-unused entry at the cap. No package-name active-cache map or model-resource publication here. Cache is only memoized compilation validation, not a mounted-template lifetime store; later rendering must establish resource leases before sharing these objects.
- Compiler and template.close are external callbacks: no cache lock while calling them. Concurrent duplicate compilation closes the loser; compile finishing after retirement cannot install. Close/eviction attempts every owned template, reports cleanup failures without skipping the rest, and never closes the independent DisplayService itself.
- Failure paths retain original causes and context. Validation failure is not converted into 'provider missing'. Missing/incompatible optional API retains the established degradation policy; ABI checks must include template close support used here.
- Compile existing resources during registration; future reload validates updated resources; changing source compiles a new candidate, unchanged source reuses the cache. Failed candidates do not replace old model/resource snapshots.
- No draw/render invocation, viewer/PAPI/train data capture, dimensions fitting, runtime mount API, entities, new config/schema fields, point overrides, player distance commands, background schedulers or runtime hot reconnection in this slice.

## Files and checks

Decorations implementation owns provider/composition/optional bridge/cache and unit tests. Root owns docs/task records, local API artifact provenance, serial Gradle and actual Paper probes; root trims the prior host API file's trailing blank line. Reviewer is read-only until explicit fixes coordinated.

Tests: exact source reuse and changed content, bounded eviction, per-template error context, retry after failure, partial-success ownership, duplicate concurrent compile, close during compile, owner retirement/service unregister/host close, absent API classloader including new API-free callback. Actual Paper uses reviewed SimpleDisplays to prove valid embedded-script compilation and actionable invalid XML/JS failures via real host reload without replacing published baseline. No claim of rendered screens or runtime JS evaluation.
