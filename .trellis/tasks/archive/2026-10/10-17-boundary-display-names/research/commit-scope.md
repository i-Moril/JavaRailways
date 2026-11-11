# Scoped commit plan

User explicitly requested commit after the local feature handoff. Commit only this task; do not push or deploy.

## Work commit

`feat(signals): add shared boundary display names`

Includes feature-only versions of these product/spec paths, plus this task planning, research and validation records:

- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/SimpleSignalsPlugin.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/BoundaryNames.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/CurrentRoutePreview.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/RouteIndicatorWandEditor.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/RoutePreviewWandHandler.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/SectionOverlayBackend.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/SectionOverlayDrawing.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/SectionWandBackend.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/SectionWandHandler.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/SectionWandSessions.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/SignalWandBackend.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/SignalWandHandler.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/WandDialogs.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/persistence/BoundaryName.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/persistence/SimpleSignalsDefinitionDrafts.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/persistence/SimpleSignalsWorldDefinitions.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/runtime/SimpleSignalsDefinitionEditImpact.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/runtime/SimpleSignalsWorldRuntime.kt`
- `SimpleSignals/src/main/resources/i18n/wands_en_US.properties`
- `SimpleSignals/src/main/resources/i18n/wands_zh_CN.properties`
- `SimpleSignals/src/test/kotlin/work/art1st/javarailways/simplesignals/authoring/BoundaryNamesTest.kt`
- `SimpleSignals/src/test/kotlin/work/art1st/javarailways/simplesignals/authoring/RuntimeRoutePreviewBackendTest.kt`
- `SimpleSignals/src/test/kotlin/work/art1st/javarailways/simplesignals/authoring/SectionOverlayDrawingTest.kt`
- `SimpleSignals/src/test/kotlin/work/art1st/javarailways/simplesignals/authoring/SectionWandHandlerTest.kt`
- `SimpleSignals/src/test/kotlin/work/art1st/javarailways/simplesignals/authoring/SignalWandHandlerTest.kt`
- `SimpleSignals/src/test/kotlin/work/art1st/javarailways/simplesignals/persistence/SimpleSignalsDefinitionDraftsTest.kt`
- `SimpleSignals/src/test/kotlin/work/art1st/javarailways/simplesignals/persistence/SimpleSignalsWorldDefinitionStoreTest.kt`
- `SimpleSignals/src/test/kotlin/work/art1st/javarailways/simplesignals/runtime/SimpleSignalsWorldRuntimeManagerTest.kt`
- `.trellis/spec/simple-signals/backend/boundary-names.md`
- `.trellis/spec/simple-signals/backend/index.md`
- `.trellis/spec/simple-signals/backend/section-authoring-wands.md`
- `.trellis/spec/simple-signals/backend/definition-edit-impact.md`

## Existing dirty work excluded

The preimplementation snapshot contains prior empty-dialog handling, diagnostics/reporting and error logging, signal runtime fixes, physics/event changes, model assets, unrelated task notes and docs-site work. Whole-file staging is not safe for shared paths.

Three-way merge of current file, preimplementation baseline and HEAD produced feature-only candidates. WandDialogs conflict was resolved to add only boundaryName; properties conflicts retain only new boundary.* entries (excluding already existing boundary.removed). Static check reviewed all 32 candidate files, found no dependency on excluded dirty source and no unrelated product hunk.

Working files remain unchanged; only exact candidate blobs are staged. No reset, stash, checkout or whole-file rewrite is used on shared source files. Source hashes must match the 622-test combined-workspace pass; isolated HEAD + candidate validates the actual commit separately. The unchanged WebUI frontend static build is reused; its install/build tasks are excluded from this Java-only check.

## Bookkeeping after work commit

Archive only boundary-display-names, rebase its context paths, then record journal referencing the work commit. Other active tasks remain active.
