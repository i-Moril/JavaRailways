# Runtime diagnostic boundary coverage

Scope: current isolated worktree, production boundary/caller inspection. This is not proof that every arbitrary caught exception in every dependency is observable.

- Core: TypedRailEventBus logs listener Throwable and retains listener isolation; PhysicsCoordinator logs failure/halt/collision failures; TickManagerImpl logs generic async tick failures. RailwaySimulationHalt logs the original cause and preserves safety stop. Existing JUL Core module handler captures these; no control changes.
- Basic: RouteResourceAdmissionEngine passes unsafe preparation/runtime failures to stallWorld with original exception. Contributor callbacks previously returned only INVALID_CONTRIBUTION text; now log original Throwable before existing rejection in admission and reversal. Normal rejected results remain unlogged.
- SimpleATO: TimetableDispatcher operation gateway/ticket callbacks previously collapsed exceptions to intervention/failed-run text. Station, destination, halt, cancellation-confirmation callback exceptions now log WARNING with run/train or ticket/request identity. Successful cancellation and ordinary Rejected results remain unlogged. Existing InspectionRuntime retains callback causes through its failure callback, and world coordinator/host composition remains responsible for stall reports. Pure validation-to-result paths are not globally promoted into errors.
- SimpleATOBukkit: embedded adapter uses host lifecycle and SimpleATO logger registration; it has no separate plugin writer. No new runtime changed here.
- Bukkit: resource candidate failures log original Throwable and retain existing resource versions; they no longer add train-resources issues for healthy trains. Existing world configuration failures still hold movement, report current configuration-unavailable state and log Throwable; successful revalidation clears that source. Car model unavailable state no longer includes stack text; exception propagates to its existing owner. World tick failure retains global halt and error logging, never becomes a content issue.
- Bukkit operations: model operation future/dispatch failures now log at gateway before converting to ticket message. Cancellation and already-cancelled tickets stay quiet. Isolated simulation logs simulation runtime identity; normal simulation owner close remains quiet. Behavior reverse callback, undo/redo failure, rail wand failures, direction display/query failures and snapshot read warning retain the original Throwable instead of message only.
- SimpleSignals: rule evaluation uses reportFailure for actual exception transitions, separates ExpectedUnavailable/HostUnavailable and current issueChanged state; normal stop/wait produces no error event. Runtime manager already logs rule/model preparation/revalidation/display failures; installation compilation/source-registration and corrupt definition candidates now preserve original exceptions at the handling boundary. Current installation/topology/rule/model problems remain in WorldIssueService; remove/unload/recovery clear only owned state. Stale/closed install registration is not logged as a new runtime failure.
- TunnelBridgeBuilder: RailSweepService already logs target/plan/apply/undo failures with original Throwable; invalid saved wand decoding now preserves Throwable at its existing WARNING boundary. Writer lifecycle/source registration belongs to backend integration worker.
- ConfigLib: loaders throw configuration errors to composition/candidate-loading boundaries; no autonomous worker/logger or second disk authority.
- Diagnostics, HTTP bridge, WebUI sidecar/auth proxy/reader and module-prefix wiring are covered by backend worker; frontend rendering by frontend worker.

## Current-state semantics

No history was added to WorldIssueService. firstSeen/lastSeen remain timestamps for a currently unresolved object fault. Model/configuration exceptions are recorded separately from the minimal object state. Rejected resource replacement retains healthy old content and only records the failed attempt in disk logging. The content specification's earlier “retain old version and report Issue” sentence was corrected accordingly.

## Regression coverage

New production-path tests exercise SimpleSignals installation/source-registration failure and removal (original cause/suppressed retained, no stack in current issue, removing state does not erase error reports), actual TimetableDispatcher station operation gateway failure (existing failed-run safety semantics and original log cause), and BukkitTrainOperationGateway failed future vs cancellation/rejection.
Existing tests cover signal display unavailable -> repaired -> removed; signal rule error dedup/recovery; WorldIssueService source isolation/clear; ModelManager rejected candidate retains previous version; configuration install safety. Full plugin reload plus world-held configuration recovery still requires a live plugin composition fixture / server acceptance; do not present catalog-only tests as that path.

No server deployment, Folia/real-client acceptance, arbitrary third-party logger capture, stderr capture, JVM crash capture, or permanent log-retention guarantee.

## Validation handoff

Scoped Gradle attempt used JDK 25, max-workers=2. Initial sandbox run could not bind Gradle lock socket; approved unsandboxed rerun executed SimpleATO station tests but overall build failed at Bukkit compilation with insufficient memory while another shared-worktree build was active. Runtime worker stopped further builds; backend worker owns serialized acceptance including Basic/SimpleATO. `git diff --check` passed. See `/tmp/error-log-runtime-check.log`; do not claim whole runtime gate passed from this attempt.

## Detached simulation follow-up

SimpleSignalsSimulationFeature had a literal `System.getLogger("SimpleSignals.simulation")`, outside the module's registered logger package. Changed that actual epoch failure callback to JUL `work.art1st.javarailways.simplesignals.simulation` at SEVERE with original Throwable; backend owns prefix registration. Added real isolated simulation + throwing JS regression that captures the package logger and checks simulation identity, Throwable and fail-closed output. Static scan of all main Java/Kotlin `System.getLogger` calls now finds only fully qualified class-name loggers (WebUiApplication and SimulationCarBehaviorExtension); these fall under the registered package identities. No global root/stderr capture was introduced.

The new installation/removal regression caught an existing orphan-state bug: unsuccessful installation retired its runtime reservation but intentionally retained its current installation issue; `remove(worldId)` then returned early when no runtime existed and never cleared that issue. Removal now clears only SimpleSignals-owned sources under the directory lock when the reservation is absent, so a replacement cannot be inserted between the absence check and clear. The regression also keeps an unrelated producer issue to verify source isolation. Backend owns the post-fix SimpleSignals rerun.

## 最终验收覆盖
后续串行完整门禁已全部通过：Basic390、SimpleATO376、Bukkit546、SimpleSignals650等共2515个后端测试；详见validation.md。失败安装后runtime缺失时remove仍必须清理本来源问题的实际缺陷已修复，回归同时断言其他插件来源不被清除。
