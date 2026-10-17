# Boundary display names implementation and validation

Date: 2026-10-17. Implementation only; no commit, deployment, or remote operation.

## Implemented contract

- Immutable `persistence.BoundaryName` records at raw authored cut positions, with default-empty `boundaryNames` in current sidecar schema 1. Strict normalization rejects blank stored names, duplicate raw anchors, control/newline input, and names over 64 Unicode code points. User input trims surrounding whitespace; blank input clears a shared group.
- Physical grouping reuses `TopologyState.overlayBoundaryIdentity` for real ordinary connections/loop anchors; same-rail tolerance grouping uses deterministic existing representatives without transitive chain merging. Conflicting merged names remain distinct metadata and render a localized conflict state.
- World copy, codec, document drafts, patch replacement and summary preserve names. Explicit authoring candidate normalization propagates unique group names to new live aliases and prunes only no-longer-owned anchors. Topology revalidation copies names without rewriting them.
- Names-only edits use the existing manager patch, exact revision/topology/runtime guards, edit permit, staged save and publication. Changed old/new metadata rails participate in edit impact; region/route/device identities, geometry, revisions, and indicator mappings remain unchanged.
- Section settings arm one naming selection. Boundary click is consumed rather than invoking ordinary create/delete; missed picks remain armed. Native text input provides save/clear/cancel, single-use guarded callbacks and invalid-input retry. Tool/world/permission/session checks precede submission; backend checks capture freshness before patching.
- Overlay, indicator route mapping, entrance choices/rebind targets, and route preview selection/deletion use shared names with actual curve-derived XYZ coordinates, preserving exact action identities. Duplicate captions retain separate numbered route rows. No new path tracing or physics scan.
- Captured overlay preparation shares one curve cache with labels and geometry. Signal captures retain O(1) capture behavior; grouped names and boundary indexes build lazily on workers.

## Checks

Commands use `/workspace/.gradle`, offline/no build cache/no parallel/one worker, 3 GiB heap and 1536 MiB metaspace, Kotlin in-process compiler.

- Initial compile caught three snapshot/cut argument mismatches; added the explicit snapshot label overload.
- Initial focused test compilation caught the existing ID-generator trailing lambda binding to the added optional targets parameter; restored the generator to the final constructor parameter.
- Focused run `/tmp/boundary-focused2.log`: 187 tests, one failure from newly authored i18n placeholders. Fixed placeholders to the repository's `String.format` convention; all manager/persistence/lifecycle/UI tests in that run otherwise passed.
- First complete run `/tmp/boundary-full.log`: 59 suites, 618 tests, one failure in the existing one-curve-read overlay assertion. Fixed shared per-prepare curve caching without relaxing the test.
- `/tmp/boundary-final.log` stopped at test compilation because the newly added crossing test supplied an unnecessary ID to `AtGradeRailCrossing`; corrected the test constructor to the existing two-point API.
- Final complete run `/tmp/boundary-final2.log`: **BUILD SUCCESSFUL** in 1m36s; :SimpleSignals:test, :SimpleSignals:jar, :SimpleSignals:verifyPluginJar and root verifySignalModuleBoundaries all passed.
- Final measured XML: 59 suites, 619 tests, 0 failures, 0 errors, 0 skipped.
- Feature source manifest `/tmp/boundary-final-source-manifest.json` verified unchanged during final build. Known nonfatal environment/compiler warnings remain (invalid alternate JDK installations and Graal js-community POM treated as ZIP).
- `git diff --check` passed after product/test edits.

Product/tests changed relative to the preimplementation snapshot are listed in `/tmp/boundary-feature-changed-paths.json`. The snapshot at `/tmp/boundary-names-preimplementation/` preserves prior dirty files, including unrelated fixes/resources.

## Validation limits

All checks are local and offline. Real Paper/Folia text input, client TextDisplay rendering, and in-game interaction remain unverified. Existing operational edit permits may conservatively block naming protected rails; no bypass was introduced. Endpoint captions distinguish destinations but do not fully identify multiple geometric paths with the same endpoints.
