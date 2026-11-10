# Native / EntityLib text visual slice — 2026-11-10

User approved the proposed first rendering slice: generic text capability in existing display backends, before extension runtime mounting. Baselines: foundation95935639, compilation84b804fc. Dedicated ddf5 worktree only; no push/deploy, report before committing.

## Gap, owner and concrete interface

`DisplayVisualContent` currently has Item/Block; both concrete backends support existing transform/visibility/mount/brightness/destroy lifecycles. EditorTextService is native-only and retains its own player-editor contract, so do not replace or route it through this change.

Add `DisplayVisualContent.Text(text: Component)` and `DisplayVisual.setText(text: Component)` in render/backend/DisplayVisual.kt. Component is immutable and retains styles/fonts; empty Component is a valid clearing operation. Implement setText explicitly in native and packet visuals and update test-only DisplayVisual implementors as needed. A live non-text visual rejects setText clearly; after destroy all text updates are harmless like other retired updates. No misleading default no-op.

Native factory creates TextDisplay and applies existing configureCommon. Text-specific baseline: fixed billboard, CENTER alignment, lineWidth Int.MAX_VALUE (no incidental vanilla wrapping), full opacity, shadow false, seeThrough false, default background false, background ARGB0. Packet factory uses EntityTypes.TEXT_DISPLAY / real TextDisplayMeta and identical flags, both alignment bits false for center. Current cached EntityLib API3.0.3-SNAPSHOT provides get/setText(Component), lineWidth, backgroundColor, textOpacity, shadow, seeThrough, useDefaultBackground, alignLeft/alignRight. Do not encode protocol offsets manually.

setText updates actual native entity text or actual TextDisplayMeta and queues metadata through existing trainPacketBundles, preserving the established metadata batching/ordering. Do not invent a separate sender, scheduler, audience tracker, per-viewer scan or text-only movement algorithm. Existing transformations, brightness, parent passengers, hide/show and destruction apply unchanged. Caller thread ownership remains existing backend contract; native create/mutate on correct target owner, destroy uses existing completion dispatch. No new global/async Bukkit access.

## Scope and intended files

Production: three existing backend files (descriptor/interface, native, EntityLib). Keep changes local and avoid broader renderer refactors/test-only public APIs. Tests: meaningful native factory/update settings, real EntityLib metadata flags/content and queued metadata where feasible, transform/visibility/removal/retired guards, unrelated Item/Block and cleanup regressions. Update existing fake visuals to reject unsupported text rather than silence it.

Root owns task/spec/docs and serial builds + isolated actual Paper probe using the generated shaded host artifact. Implementation agent owns backend source/tests; review agent read-only. Cached upstream bytecode is primary API evidence; do not add dependencies or update EntityLib/PacketEvents versions.

Excluded: public extension mount/factory APIs, TextModel/model-tree integration, XML conversion/layout/scale fitting, Decorations/SimpleDisplays changes, train data, per-viewer dynamic frames, PAPI, visibility configs/commands, editor service migration, persistence/schema changes or no-op render stubs.

## Validation

- Focused backend/model visibility/construction/ordinary service tests; compile affected fake visuals and downstream module.
- Host shadowJar and inspect relocated EntityLib text metadata linkage; no new dependency/API class copies.
- Actual isolated Paper probe creates/updates/transforms/teleports/destroys both real native TextDisplay and packet text wrapper, inspects their actual state and flags, verifies cleanup completion/idempotency. No player/client rendering acceptance without a real client.
- Full host suite where appropriate; previously recorded sole ProgramExpressionsTest failure remains separate. Folia runtime verification is not implied by owner-preserving source changes/Paper health.
