# Display passenger warning and refresh cost

The user reports client `Received passengers for unknown entity` spam and severe lag after the `be8b75ec` deployment. This is a regression investigation within the existing display task; no new feature or data conversion is involved.

## Evidence and smallest gap

- The Minecraft26.1.2 client handler (locally cached client bytecode) logs this exact warning when the vehicle entity is absent. For an existing vehicle it ejects all passengers and attaches the supplied IDs again, even if the list is unchanged. Repeated identical mount packets therefore perform real client work.
- `CarExtension.refreshFor` calls `CompositeModel.showTo` repeatedly from the existing audience refresh. The composite's extension path explicitly refreshes root passengers after `root.showTo`, even if that show declined because the anchor chunk has not been sent. Extension audience transitions can also refresh the root before it has spawned.
- `EntityLibDisplayVisual.refreshPassengersFor` currently sends to the supplied player without checking whether the root is shown/current and without deduplicating its list. This is a deterministic unspawned-root mount sequence in source; field packet IDs have not been captured.
- EntityLib3.0.3 `WrapperEntity.addPassenger/removePassenger` immediately broadcasts the full root list for each leaf mutation. Dynamic screen cardinality changes recreate leaves and amplify the mount traffic. Its direct Spawn/Destroy sends also bypass the coordinator that buffers manual mounts, allowing a queued mount to follow an immediate Destroy.
- At16:46:51UTC the test server's existing performance command reported5/5 active trains, 15.255ms sampled tick impact, 858394 bundled packets total and0 managed seat entities/virtual seats. This is one server sample, not a client FPS measurement or packet-rate trace. No seat change is justified by that sample.

## Change boundary

The behavior lives in the packet display backend. Expected source change: `EntityLibDisplayRenderBackend.kt`, using a display-only wrapper adapter and the existing `TrainPacketBundleCoordinator`. Expected tests: real backend/wrapper plus `TrainVisibilitySimulationTest` client-entity table, and affected text backend expectations. Normal display rendering, audience/chunk-generation semantics, item/block/text metadata and EntityLib riding bookkeeping must remain intact.

The adapter suppresses EntityLib's eager unfiltered passenger packet, including empty forced bundles, and sends display Spawn/Metadata/Destroy through the same existing coordinator as explicit mounts. Root refresh requires actual current viewer state, includes only that viewer's shown/current children, and deduplicates identical ordered lists. Re-show after real hide/respawn must resend needed relationships. The root's own show path must publish after its spawn so ordinary composites without extensions retain mounting behavior. First empty state needs no packet; removing previously mounted children must clear the list.

No new scheduler, global mount registry, general packet filter, culling, seat rewrite, template/layout change, persistent field or schema version is added. The completed repair follows the earlier user-authorized submission/deployment of this same display task; live observation remains read-only until verification finishes and a recovery archive is established.

## Validation

First reproduce against the deployed source with the real wrappers/network exit and minimal client entity table. Cover repeated unsent-root show, initial child/root/mount order, unchanged steady refresh, visible-child filtering, dynamic growth/shrink/EMPTY, chunk unload/resend, player isolation, hide/destroy within an open frame, and ordinary composites. Record mount packet counts to distinguish removal of repeated detach/reattach from suppressed logging. Tests substitute network delivery/client state; they do not prove the field trigger or client rendering acceptance.

Run focused renderer/extension/visibility suites and a current shadowJar build, then independent source review. Keep the previously recorded unrelated full-host failures separate; do not change them. Sync the rendering spec with the verified contract. No unrelated deployment/runtime issues are part of this repair.

## Completed source verification

The original production code failed all3 new regressions:100 refresh attempts generated100 unknown-vehicle mounts;90 glyphs generated91 parent mount packets; a same-frame hide left1 late unknown-vehicle mount. Log: `/tmp/jr-display-passengers-baseline-20261111.log` (15 tests,3 failures).

The completed repair changes only the packet display backend plus focused tests. Final selected renderer run passed56 tests,0 failures/errors/skips, including17 real-wrapper/client-table simulation cases and the existing metadata/outline/construction/extension/ordinary-model checks. Final shadowJar passed in the same run: `/tmp/jr-display-passengers-final-20261111.log`. The added dynamic test enables EntityLib forced bundles and confirms hidden observers receive no empty bundles. A metadata-translation omission identified in independent review was repaired; two viewer locales retain independent translated metadata without mutating the source component. Independent re-review and diff check passed. Previously recorded unrelated full-host failures were not modified or rerun.

The packet-order and count mechanism is verified locally; no actual client entity IDs, FPS trace or live packet capture were obtained. Deployment/health and game-client acceptance must be reported separately.
