# Interior display viewing distance — 2026-11-10

User authorized implementing distance first and assessing culling, with all integrated testing deferred until the work is complete. Distance is player-to-SCREEN, explicitly not car center. Previous operational batch remains dirty and must be preserved; no commit/push/deployment this turn. Style overrides remain the subsequent batch. Use trellis-before-dev; implement sub-agent handles Decorations only, root owns host/public API/docs. No agent builds/tests; root performs compilation only, not test suites/Paper/Folia. Deferred checks must be reported honestly.

## Smallest gap and owners

Generated extension text currently uses the complete car audience. Add an optional generic session audience policy, evaluated using viewer UUID and immutable geometry; host retains platform ownership. Decorations owns interior/exterior meaning, configuration, command and session-only player preference. No Core/SimpleATO or train persisted payload changes; no speculative schema/version bump or old config reader.

## Public API exact additions

In bukkit.api.model.extension:

- `fun interface PartExtensionAudiencePolicy { fun maximumDistanceBlocks(viewerId:UUID):Double? }`: null means ordinary car audience; finite distance >=0 adds a sphere centered at the extension part origin AFTER all parent/car/part animation transforms. Zero explicitly disables generated content. Callback is cheap, thread-safe, side-effect-free, uses no live platform/domain objects, and may run concurrently with draw/close. Companion `UNRESTRICTED` returns null.
- `PartExtensionRenderSession.val audiencePolicy:PartExtensionAudiencePolicy` default getter returns UNRESTRICTED. Published once when private session opens on worker; host does not expose labels while policy is not yet ready. Close releases it.
- `PartExtensionRegistration.refreshAudience(viewerId:UUID)` default no-op for existing metadata/test implementations. Real registration forwards only to its active attachments and only their existing train-audience member. Used by player command after preference changes; no scan of online players, no provider callback under registry lock, no blocking entity tasks.

Host group stores candidate train viewers and visible subset separately. Only generated text is filtered: original children remain ordinary car content. Viewer location is captured on entity owner (eye position), screen world point snapshot on model owner. Eligibility is same world AND distanceSquared<=limitSquared, with limit0 always hidden; default UNRESTRICTED preserves exterior behavior. On owner movement/rotation/animation or player movement/settings, coalesce at most one entity refresh per group/viewer, read actual player on its entity owner, recheck exact current viewer token/group/attachment/generation before show/hide and passenger refresh. No online-player scans or new repeating scheduler.

Owner root-only path recomputes anchor using latest shared-root position + committed rotated parent/part offset; full transform path includes parent rotation and animations. Groups need current transforms before first audience evaluation. No player reads on model/simulation workers. Moving train must update stationary viewers; original render chunk candidate set remains an upper bound.

When no eligible viewer, do not request draw; warm session open is allowed but must not perform default draw. Retain latest detached input and original source clock. Last viewer loss invalidates queued/in-flight output and clears request throttle; on return request latest current input immediately with elapsed time preserved. Normal geometry metadata stays owner driven. Existing generated visuals may remain allocated, hidden from all viewers; no claim of reclaiming native entity tick cost. Compilation can still happen once for warm open. Context/retirement cleanup still includes all generated/obsolete resources.

## Decorations exact implementation boundary

New `DisplayDistanceSettings(defaultBlocks:Double=32.0,maxBlocks:Double=128.0)` holds validated limits and ConcurrentHashMap UUID preferences. Both finite; 0<=default<=max<=4096 and max>0. `distanceFor(UUID)`, `set(UUID,Double)`, `reset(UUID)`, `clear()`; no persistence, clear on quit/disable. Zero disables interior generated text. Constructor invalid config fails enable.

Decorations/config.yml:
```
displays:
  interior-distance:
    default-blocks: 32.0
    max-blocks: 128.0
```
Strict known sections/keys and true numeric finite values, no string-as-number coercion; new file is created from default resource when absent (normal first-enable). Do not write player overrides or add schema versions to this new simple plugin config.

`/decorations display-distance [<blocks>|reset]`: no arg shows effective/default/max and session lifetime; numeric sets within bounds, reset removes override. Permission `decorations.display-distance`, default true; players only. Plugin descriptor declares command and permission like existing addon descriptors. Use existing Bukkit CommandExecutor/TabCompleter pattern in standalone addon. Reuse Paper entity scheduler for command execution if necessary; do not import host util/internal. Command changes call registration.refreshAudience(playerUUID), including reset. Quit removes preference; disable unregisters listeners/command references and clears store through existing plugin lifecycle.

`DisplayPartProvider` accepts injected interior audience policy default UNRESTRICTED for existing consumers, wraps an opened template session to publish the injected policy only for config.interior, delegates draw/close to real session. Exterior session policy UNRESTRICTED. No SimpleDisplays API or Bukkit objects in policy callback. Underlying optional connection still owns/cancels/closes template session normally. DisplayPartConfig/resources/schema shape unchanged.

Expected addon files: DecorationsPlugin.kt, DisplayPartProvider.kt, new settings/config reader/command files and config.yml resource, build.gradle descriptor, focused settings/provider tests authored for final run. Root owns README, task/spec/research.

## Deferred verification and culling assessment

Prepare regression cases but defer execution per user: distinct screen offsets on same car; rotated/nested/animated point; root-only motion; moving train/stationary player; same/world change; player command without movement; viewers independent; zero/default/reset/quit; hidden late/growing results; pause/resume source time; retirement and queued refresh; native/packet parity. Compilation only now.

Assess server vs client savings, conservative safety, camera/FOV availability, car-body opaque geometry and Folia block-ray ownership. Initial distance-only implementation does not add backface/frustum/raycast/mesh/occupancy checks. Assessment goes in research/interior-display-culling-assessment.md and final Chinese brief.
