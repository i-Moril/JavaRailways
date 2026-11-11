# Operational display inputs — 2026-11-10

User approved commit of dynamic slice09da5fbc and continuation. Current batch connects actual fixed-unit operational values and stop facts to existing XML/document-JS sessions. No PAPI, per-viewer visuals, interior-distance config/commands, overrides, schema normalization or independent SimpleDisplays edits. Dedicated ddf5 worktree only. Report before committing this new batch; no push/deploy.

## Gap / ownership

Basic data currently comes from immutable physics frames before dispatcher tick. Actual displayRuntime owns trip/cycle/unit/author values/occurrences but querying requires guessed timetable/cycle. Add fresh owner-only batch capture, then project domain values into generic host Json and carry them alongside car frames after dispatcher+postTick. Workers remain platform/domain-object free. Reset/retire/context changes invalidate old queued/completed output and clear prior generated text, rather than preserving wrong old labels after a new draw failure.

Ownership: implement agent owns SimpleATO production/tests plus host attachment/group/Composite context invalidation+tests. Addon agent owns Bukkit TrainPartRenderSnapshot.kt and new operational projection/tests only; no Decorations/SDL changes needed. Root owns WorldManager.kt, CarExtension.kt, publication integration tests, task/spec/docs and all serial builds/runtime probes. Reviewer read-only.

## SimpleATO owner capture API (exact names)

Add immutable `TimetableUnitDisplayContext` in timetable package with `trainId:String`, `unitId:String`, defensive `carIds:List<String>`, `timetableId:String?`, `display:TimetableUnitDisplaySnapshot?`, `currentBerth:StationBerthRef?`.

`TimetableDispatcher.captureUnitDisplays(world:World):List<TimetableUnitDisplayContext>` captures all complete registered units currently present, freshly from displayRuntime, not cached run.unitDisplays. It resolves actual unit membership and exact context key owned by runtime; caller never picks cycle/latest duty. For ordinary unregistered/incomplete/noncontiguous/wrong-order units return no claimed binding, not broad catch of domain exceptions; reverse unit order is valid where depot contract allows. Map by actual fixed carIds, not physical index or train-name similarity. Include held units with no running duty. Missing table/context => display/timetableId null; inactive context retained in this domain diagnostic API. Context ambiguity must not select arbitrary winner.

Current berth only from nonterminal run existing physically checked confirmedDoorStop evidence (zero speed, exact current frame/head/tail/reversed/carIds, valid berth). No inferred call occurrence from currentWayPointId/last completed. Unknown is null. Keep existing explicit-table unitDisplaysForTrain API for its diagnostic consumers.

Expose `TimetableDispatcher.unitDisplayRevision(world:World):Long` from runtime. Bump a runtime-only monotonic revision for actual begin/enter/set/reset/reached/retire/finish/future-proof/retain/clear changes so stationary authored actions force a fresh owner sample. Equal JSON SET may be unchanged; no schema version/new persistent state. Capture fresh after dispatcher+postTick; no cached snapshot from tick beginning. Revision is wakeup evidence, per-car semantic equality prevents unrelated-unit resets from clearing every display.

## Bukkit immutable projection (exact helper boundary)

New `OperationalPartRenderProjection` owns Json `unit`, `operational`, top-level string aliases and `contextKey:JsonObject`. `projectOperationalPartRender(context:TimetableUnitDisplayContext?, stationName:(String)->String?):OperationalPartRenderProjection` resolves only names through caller owner snapshot, no live objects retained. `OperationalPartRenderProjection.EMPTY` for no valid unit/context. Root maps domain batch contexts to car IDs.

Extend `captureTrainPartRenderSnapshot(frame,carIndex,trainType,tick,operational:OperationalPartRenderProjection=EMPTY)` and `TrainPartRenderSnapshot` adds semantic `contextKey:JsonObject` (include unit+operational, excludes physical speed/time). Keep detached immutable input ownership.

Data shape:
- existing train/car and string aliases stay.
- `unit`: null or {id,carIds:[string]}; physical unit membership independent of reversal/index.
- `operational`: null unless display.active and exact timetable present; otherwise {timetableId,operationalTripId,cycleIndex:string,values:object,remainingStops:array,plannedStopCount:number,completedStopCount:number,unresolvedRuns:[string],currentStation:object|null,nextStation:object|null}.
- each remainingStops item: {serviceId,runId,callId,stationId,stationName:string|null,destinationId,status,arrivalTick:string|null,departureTick:string|null,completed:boolean}. Preserve source order, repeated occurrences and PASS; no global station dedup. Planned ticks/cycle are strings to avoid JS safe-integer loss. Do not copy all3 duplicate stop lists into the library input.
- currentStation: {stationId,stationName:string|null,berthId}, only proven current berth (no fabricated callId).
- nextStation: first remaining STOP/TERMINAL occurrence only when unresolvedRuns is empty and station differs from proven current station (otherwise unknown); skip ORIGIN/PASS, never use unresolved later segments as certain next. Include the same stop fields, not a guessed route.
- `destination`, `runType`, `currentStationName`, `nextStationName` top-level XML strings; runType and authored destination aliases use only exact strings, otherwise empty. Since the 2026-11-11 user decision, an absent destination key defaults from the exact actual currentRun terminalStationId (never the final known combined-trip stop); see current-run-destination-default.md for localization and alias behavior. Inactive/no context publishes operational=null and empty aliases; domain diagnostics remain in SimpleATO, not copied as stale active display values.

Library existing4096-node/character budget remains. Overbudget fails contextually and clears obsolete context rather than truncating stop facts or raising limits speculatively.

## Publication and invalidation

Move ONLY platform visual dispatch out of beforeDispatcher to immediately after OperationalWorldRuntime.afterPublication returns, still inside same processBetweenFrames world owner boundary. Domain observation/derail/dispatcher/postTick ordering is unchanged. Read one train.publishedFrame reference for car geometry+basic values and fresh post-tick operational capture.

Sample metadata on existing10tick world bucket, plus whenever unitDisplayRevision changes (forces active cars even with stationary empty mailbox). Other geometry-only envelopes carry the latest same-stream immutable sample reference so coalescing cannot erase pending sampled changes; metadata is nullable only before the first same-stream sample. On train stream change clear previous metadata immediately even if new sample absent, then rebuild dynamic model via existing stream logic. Initial unknown metadata must not invent old train/unit state. Keep exact stream/revision gate before metadata install. No worker/current-disk/store lookups or new Bukkit scheduler.

When accepted snapshot.contextKey changes, CarExtension invalidates existing Composite extension context BEFORE offering new data; clear prior generated leaves, invalidate attachment pending/request generations and bypass10tick throttle once. Ordinary speed/time updates do not change semantic key and remain starvation-safe. Compilation/session need not be reopened for a context update (invocation state already isolated). Direct context reset uses same clock origin; train stream rebuild resets full model/session identity.

Implement `PartExtensionAttachment.invalidateContext()` with private generation on requests/results; old in-flight draw may finish but cannot publish, latest new request remains bounded/serial. `CompositeModel.invalidateExtensionContext()` forwards to group: clear old generated visuals through observed cleanup, clear pending/replay identity, preserve original children/root/audience/session, reset lastRequestedTick (not firstSourceTick). All obsolete/partial cleanups remain part of eventual close. Failed new context draw stays blank, failed same-context routine refresh may keep last success.

## Verification

- Domain fresh owner capture: actual fixed units D/E, reversed/split/held, different cycles, no registry/no context/inactive/reset, fresh SET late in tick, incomplete membership no false binding; current berth proof disappears with movement; revision wakes on actual display changes.
- Projection author values and types, null/inactive aliases clear, repeated/PASS/unknown station names, unresolved prevents next, true current separate from planned, ticks exact strings and immutable data. No guessing of run type or destination from combined-trip stop lists; exact current-run terminal fallback follows the 2026-11-11 decision.
- In-flight old-context output rejected, queued replay suppressed, samecontext slowdraw still progresses, clear-old-before-new-failure, same session compile reused, context reset bypasses cadence, old/new cleanup tracked.
- World source envelope after dispatcher/postTick, stationary revision forced sample, geometry-only envelopes do not restore old data, stream transition clears before new metadata, active-only candidate filtering.
- Focused SimpleATO+host tests, full affected modules, host known ProgramExpressionsTest failure separate; shadowJar/Devcompile/Decorations thin gates.
- Isolated Paper actual XML/JS both backends binding authored values and ordered stops through host projection, updates/reset/invalidation/cleanup; no game-client or real Folia acceptance claim.
