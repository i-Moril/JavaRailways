# Train-mounted displays through Decorations

> Current authorized slice: [native/EntityLib text backends](research/text-backends-contract.md). Template compilation committed84b804fc. This adds real internal text visuals only; implementation and [validation](research/text-backends-validation.md) complete, currently uncommitted. Train-part runtime mounting follows separately.

> Current slice: [Decorations template compilation](research/decorations-template-compilation-contract.md). Foundation committed as `95935639`; user approved continuation. This slice is implemented, uncommitted, with [validation](research/decorations-template-compilation-validation.md): actual XML/embedded-JS syntax compilation and bounded cache; runtime mounting follows separately.

## Confirmed module boundary (2026-11-10)

JavaRailways supplies generic namespaced part definitions, immutable extension package resources and provider registration. The independent `Decorations` plugin owns `decorations:display`, display resource semantics and the optional SimpleDisplays connection. SimpleDisplays remains a separate reusable XML/document-JS library/plugin.

Without Decorations, a train must load normally. Unknown namespaced nodes retain their configuration, transforms, animations and complete child subtree. A diagnostic identifies the unavailable extension. Unknown unnamespaced part types remain errors. No reflective loading from saved type IDs is permitted.

The approved current foundation slice is [the Decorations contract](research/decorations-foundation-contract.md). It migrates existing definitions/resource loading/service discovery without implementing visuals. Main-package and exact per-car source snapshots capture raw extension data without a provider. Late provider registration validates already loaded packages; reload publication must reject stale provider generations. Provider disable retires its registration without removing model trees.

## Current foundation acceptance

- [x] Host has no SimpleDisplays API dependency, adapter or descriptor dependency.
- [x] Decorations registers display semantics through public host API, with lifecycle cleanup and a separate thin plugin JAR.
- [x] Missing add-on preserves extension nodes, transforms, animations, imports and children.
- [x] Declared extension directories capture immutable UTF-8 resources with safe paths and deterministic snapshots, including per-car source packages.
- [x] Invalid installed-provider config fails preparation; late registration reports per-package failures; stale candidates cannot publish across registration changes.
- [x] Existing built-in/no-extension snapshot hashes remain fixed; current schema versions remain unchanged.
- [x] Schemas, examples, targeted tests, packaging and available runtime smoke evidence agree.

Validation: [foundation record](research/decorations-foundation-validation.md). Complete host suite retains one previously recorded expression failure; do not claim whole-suite green. No commit yet.

## Remaining display integration

- Compile captured XML/document-JS templates through the independent SimpleDisplays API; preserve viewer-specific content and isolated script state.
- Define generic train-part runtime mounting and cleanup APIs, then implement Decorations rendering through native Bukkit and EntityLib backends, with Folia ownership.
- Reuse parts-tree names, transforms, inline definitions and imports. Display config requires category, interior, physical width and height. No parallel screen list or separate point identity.
- Preserve confirmed visibility policy: exterior screens follow car-body visibility; interior screens initially use configurable per-player distance, including a player command. Occupancy, facing, frustum and occlusion are outside the first policy.
- Resolve package/category/point defaults and train category overrides. Initial precedence remains train override, package point default, package category default; current resource format only implements category defaults.
- Supply train/run type, destination, current/next station and actual ordered future stops directly through the library API. Actual Bukkit PlaceholderAPI adaptation is optional subsequent work, not the transport between JavaRailways and SimpleDisplays.
- Fit logical template coordinates (128 units per block/metre) to the physical installation dimensions.
- Verify movement, curves, slopes, reversing, audience changes, rebuild and unload through both rendering backends on real Paper/Folia. Static tests and plugin health do not prove client rendering.
- Runtime style-switching command/per-car JS addressing and persistence remain TODO pending target and composition semantics.
- Run deferred `11-09-model-config-schema-consistency` after other display work and before final acceptance.

## Workspace and provenance

Use only the dedicated `ddf5/JavaRailways` worktree on `codex/simpledisplays-train-integration`; the main checkout contains unrelated work. Independent SimpleDisplays XML/document JS is at `410af2e`, reimplemented from `21f7686` without a revert commit. Earlier built-in screen and root displays.yml formats were unshipped development shapes and are replaced directly, with no legacy runtime reader or speculative version increase.

Historical discussion below records prior decisions; this confirmed module boundary and foundation contract supersede obsolete ownership/format claims.

## Confirmed visibility decision (2026-11-09)

- The user selected the distance-based initial policy above. The user also approved inline/imported screen parts with required `category`, `interior`, `width` and `height` under the extension-owned `config` (original built-in `screen` shape superseded by Decorations). Config keys, numeric defaults, command syntax, distance reference point and player-setting persistence remain implementation-design details to specify before coding; no values or persistence behavior have been agreed here. The player command is for viewing distance, distinct from deferred category-style switching.
- Continue work in `/home/user/.codex/worktrees/ddf5/JavaRailways` on `codex/simpledisplays-train-integration`, starting from `ce2bcd44`. The main checkout has other work and must not be used for implementation or builds of this task.

## Template discussion (2026-11-09)

- The user proposes reusing SimpleDisplays XML directly and adding placeholder API adaptation rather than designing another display-template language.
- Existing SimpleDisplays supports `<span bind="key">fallback</span>` and `DisplayTemplate.draw(Map<String, String>, elapsedTicks)`; the host can supply scalar values without rewriting XML or duplicating the compiler.
- Confirmed: “placeholder API” means the external Bukkit PlaceholderAPI plugin. The user also asks about JS-generated dynamic elements inside SimpleDisplays. JS input/output shape, template storage and refresh policy remain under discussion; the user subsequently authorized the scoped SimpleDisplays JS implementation below.

- Confirmed: as a reusable library, SimpleDisplays must allow the same display instance/template to produce different content for different viewers. Viewer-specific content is a supported capability, not restricted to a JavaRailways-only special case. Identical train information may still be reused, but callers must not be forced to share one frame among all viewers.

## Authorized SimpleDisplays JS slice (2026-11-09)

- Implement only the independent library's JS compile/render API: generic structured display data, explicit per-viewer input, elapsed ticks and renderer-neutral text/icon output. Preserve the existing XML API.
- An injected placeholder resolver may be called from JS; the actual Bukkit PlaceholderAPI adapter is later work. JavaRailways sends data through the direct library API, not through PAPI.
- Execute one bounded render invocation without cross-viewer mutable script-state leakage. Validate full output before returning a frame and clean up script resources on template/service/plugin close.
- Do not implement JavaRailways script resource loading, train data capture, native/entitylib mounted text output, visibility settings or commands in this slice.
- Independent source worktree: `/home/user/.codex/worktrees/ddf5/SimpleDisplays`, branch `codex/js-rendering`, base `21f7686`. Keep the original SimpleDisplays and JavaRailways main checkouts unchanged.

- Completed independent library slice as SimpleDisplays `db9c65e`; API documentation lives in that repository README. See [validation](research/simpledisplays-js-validation.md). Actual PAPI adaptation and JavaRailways use of JS remain pending.

## XML/document JS revision (2026-11-09)

The user superseded the standalone JS design and asked to redo from `21f7686` without a revert commit. The authoritative current slice is [XML/document JS contract](research/xml-document-js-contract.md). Earlier standalone-JS implementation/evidence is historical and not the current API. Package template wiring remains deferred.

Replacement library slice completed as `410af2e` with XML embedded scripts/document API. See [replacement validation](research/xml-document-js-validation.md); package template loading and train rendering remain pending.
