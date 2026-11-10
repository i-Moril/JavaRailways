# Dynamic frames and basic train input — 2026-11-10

User approved commit of static mounting (a4604a43), continuation, and excludes Bukkit PlaceholderAPI from this task. Current next slice is intentionally basic train/car value input + ongoing XML/document-JS redraw. Operational stops/destination/run-type projection, viewer-specific native/packet visuals, interior distance policy, style switching and config consistency remain separate later slices. No edits to independent SimpleDisplays source or main checkout; report this new slice before committing it.

## Gap / owner / paths

Static attachment calls prepare once with empty data/time0. Replace this unpublished API directly with a privately owned render session; no legacy prepare adapter/type generation or schema change. Host owns serial scheduling/visuals, Decorations owns exact XML/session, SimpleDisplays owns evaluation. Root owns WorldManager + CarExtension + basic immutable train input projection/tests/task/spec/docs. Host implement owns public session/input API, attachment/service workers, ExtensionPartModel/TextModel/CompositeModel and focused tests/fakes. Addon implement owns only Decorations production/tests. Independent reviewer read-only. Root runs all Gradle serially after freeze.

## Exact public API

`PartExtensionRenderer : PartExtensionProvider { fun open(context:PartExtensionRenderContext):PartExtensionRenderSession? }` (null means genuinely unavailable/inactive, no per-tick reopen).

`interface PartExtensionRenderSession : AutoCloseable { fun draw(input:PartExtensionRenderInput):PartExtensionFrame; override fun close() }`

`class PartExtensionRenderInput(data:JsonObject=JsonObject(emptyMap()), val elapsedTicks:Long=0L)` has detached immutable data via existing immutablePartExtensionJson and requires nonnegative elapsedTicks. Context/frame/text names unchanged. No provider sees Core/World/Car/Bukkit/Player/backend handles. Open/draw/close on host workers, outside registry/attachment locks; exactly one open attempt per attachment, draws serialized, close exactly once after active callback returns. An opened session that arrives after retirement must be closed without publication.

## Scheduling and ownership

Use existing 2-worker service, one queued/running task per live attachment, one overwritten latest request, one completed pending result. Do not submit per-car-tick history. On completion yield/requeue at most latest work so other attachments progress. Accept monotonically completed results for the valid attachment, even when a later sample arrived during draw; rejecting every nonlatest input would starve slower JS. Retirement invalidates pending/request identity immediately, cancels/removes queued work and requests interrupt of active work, but cleanup completion must observe callback EXIT and actual session.close, not FutureTask.cancel.isDone. Shutdown drains required close work instead of shutdownNow discarding close tasks; no main/region blocking wait.

`CompositeModel.updateExtensionInput(data:JsonObject, tick:Long)` stores/supplies detached latest input to its extensionGroups before existing full/root-only commits. Group normalizes elapsedTicks to its first source tick and requests at most once per10 source ticks (2Hz nominal), initial draw allowed at tick0/default empty input. Same model input held between draws; normal full/root-only commits continue consuming results. No new Bukkit tick/scheduler/audience scan. Existing train models retire on audience loss; no recurring work for inactive/retired attachments. Input-free manually constructed composites preserve one initial draw only.

Dynamic EMPTY clears all generated text. Same-count frames reuse existing leaves/setText/transforms; same unchanged values should avoid unnecessary metadata. Rebuild transactionally when count changes, clean old leaves after successful replacement and retain old successful content on preparation/creation failure. New leaves receive latest group/root transform before audience replay; parent passenger refresh follows child spawn. Keep cleanup stages for obsolete/partial resources until truly complete. Generated leaves never enter original attachedModels/animation slot order. Failure diagnostics bounded to first failure in an episode, reset after successful draw; no per-tick retry loop. Recurring sampling gives later inputs a chance to recover.

## Basic train projection / root wiring

Capture immutable values from the SAME TrainFrameSnapshot used by WorldManager.PendingCarFrame while on simulation owner. Do not modify Core physical snapshots or operational/dispatcher ordering. Capture train creation-template identifier via TrainExtension.modelPackageName (nullable) on simulation owner, plus source tickIndex; no worker/region read of mutable train metadata. Envelope carries projection alongside car frame with existing exact stream/revision checks. applyPublishedFrame stores projection only AFTER gate accepts. Supply it to body and bogie Composite before update. Rebuild a currently built model on accepted train publication stream change so old in-flight session results cannot install for a newly split/joined train identity. Keep source package pinning unchanged.

Structured data (all facts in immutable frame; car index is physical stored order, not fixed-unit or direction inference):

- `train`: {id:string,name:string,type:string|null,speedMetersPerSecond:number,reversed:boolean,derailed:boolean,carCount:integer}
- `car`: {id:string,index:integer (0-based),number:integer (1-based)}
- top-level XML string bindings: `trainId`, `trainName`, `trainType` (empty if unknown), `carId`, `carNumber`.

JS reads ctx.data.train / ctx.data.car. XML bind accepts the explicit top-level string aliases. No invented runType, destination, current/next station, timetable/cycle identity or guessed future stops. Those must later use TimetableUnitDisplaySnapshot actual unit membership/occurrences/unresolved facts; existing visual dispatch precedes dispatcher and cannot silently claim current operational data. Notes recorded separately as needed.

## Decorations session

Exact captured source/category default resolution stays. Optional bridge is API-free, now `openTemplate(...):PartExtensionRenderSession?`. Missing service/default returns null. Typed binding validates draw(DisplayContext) ABI, compiles once per private live session and draws structured data with real elapsedTicks. Convert JsonObject recursively to maps/lists/null/finite primitive values; no data.toString or flattened nested JS shape. Keep XML validation LRU unchanged. Fitting/1024 bound remains existing formula.

Connection tracks privately owned live sessions. close/retirement marks disposed immediately; active draw finishes without publishing and finally closes template once outside locks; idle template may be closed directly outside lifecycle lock (no waiting for an active invocation). Host session.close observes same terminal cleanup/failure; shared DisplayService never closed. Open racing retirement closes new compiled object. Draw errors keep session available for later inputs; template closes only on retirement/model close, not after each frame. Binding disposed sessions return EMPTY/retirement-safe output and release engine references. Close errors preserve primary/suppressed errors. No new addon executor or borrowed validation-cache lease.

## Verification

- Public immutable input/real source shape, same-frame train and car values, aliases, null type, reversal/index semantics, frame gate rejecting old stream before data install; stream-change rebuild.
- Open once, serialized repeated draw, coalesced/latest request with no starvation, idle/queued/active retirement and exactly-once delayed close; true cleanup future, service shutdown with active+idle sessions, late open and draw result rejection, bounded failure episodes/recovery.
- Same-count text update avoids entity rebuild, dynamic grow/shrink/EMPTY, transform updates at current root, late viewer/exact token/order, failure rollback and cleanup all old/new resources, unchanged animation enumeration.
- Real Decorations DisplayContext structure/time, compile once/draw multiple/private close, malformed draw+recovery, connection retirement/in-flight draw/open, hidden-API classloader and missing service/default.
- Host/addon focused and full host tests, thinaddon gate/shadowJar/Devcompile. Retain known ProgramExpressionsTest failure separately.
- Actual isolated Paper real XML+embedded document JS on both backends: basic supplied input and elapsed tick update, text-only identity reuse, count change/EMPTY, source pinning and cleanup; no real client/Folia claim.

## Stationary-clock refinement

Source evidence PendingTrainFramesTest proves Core suppresses stationary mailbox notifications even though published physics revisions advance. Root therefore reuses WorldManager's existing world tick at a10-tick bucket cadence: consume ordinary queued frames unchanged; additionally dispatch current immutable frames only for cars with a built runtime extension model, excluding cars already dispatched. No new scheduler/player scan or Core suppression change. A per-exact-CoreWorld bucket is removed on unload/stop. Composite.hasExtensionRenderers supplies the owner-side flag; missing provider/inactive models do not cause heartbeat region tasks. This is O(current cars) checks at2Hz and bounded existing per-car latest owner dispatch, not per-screen jobs.

Host session.close callbacks still run on host workers. Decorations connection-internal idle-template retirement is a separate library cleanup operation: reviewed410af2e template.close has no active context when the session is idle and therefore does not wait for guest execution; active template retirement is deferred to draw exit. It runs outside all session/binding locks and host session.close may observe a concurrent close completion from its worker.
