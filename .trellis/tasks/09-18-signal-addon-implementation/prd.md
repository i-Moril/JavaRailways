# Stage 2: Signal add-on implementation

## Goal

Implement a separately deployable Signal plugin that uses only the Stage 1 public host contracts to provide signalling, interlocking, train-authority integration, persistence, authoring, and rendering.

The first Stage 2 deliverable is the physical extraction into two separately owned modules: platform-neutral `SimpleATO` and Bukkit adapter/plugin `SimpleATOBukkit`. Signal package/runtime features begin only after that extraction is complete and the old host-owned entry points have been removed.

## Requirements

- Move ATO, operating direction, Destination, Station, Depot, Line, Timetable, rolling-stock assignment, operational route planning, and their persistence into `SimpleATO`; keep generic pathfinding in `Basic`.
- Move signal/ATO rendering, `RailObjectWand`, Bukkit lifecycle, service lookup, and platform interaction into `SimpleATOBukkit` before implementing signal JavaScript/runtime features.
- Build as a distinct Gradle subproject/artifact with its own plugin descriptor and lifecycle.
- Declare JavaRailways as a required host and compile-only dependency; do not shadow JavaRailways classes.
- Fail fast with an actionable message when required Stage 1 services are missing or incompatible.
- Own a world-local store for signal definitions, direction-aware protection-region boundaries, compiled region routes, authority policy, aspects, and renderer state.
- Persist Signal data in a separate versioned sidecar with stable namespaced IDs, rail UUIDs, canonical AB arc lengths, directions, and explicit references.
- Validate duplicate IDs, missing rails, invalid arc lengths, direction errors, overlapping/conflicting definitions, and orphan references before activation.
- Derive every permissive aspect from a confirmed host resource allocation and valid train authority.
- Let players define all interlocking protection regions by placing direction-aware boundaries on rails and selecting the bounded interior. Do not synthesize one protection/stop boundary for every turnout.
- Validate that cutting all registered gates yields the selected bounded interior, that no unregistered topology escape exists, and that entry-to-exit paths and exit clearances are valid for the requesting train.
- Compile each request into one all-or-nothing entry-to-exit resource bundle. Denied requests remain outside the entry and own no subset; adjacent regions without full-train intermediate clearance are merged or jointly requested.
- Surface host-detected at-grade diamond nodes as immutable-connectivity conflict nodes. Players may inspect and diagnose them, but cannot change their line-to-line connection direction as if they were turnouts.
- Feed Signal/interlocking restrictions into the host's single train-protection pipeline; do not introduce a Signal-owned per-train forward scanner parallel to ATO perception.
- Treat missing, stale, corrupt, unavailable, or conflicting state as restrictive.
- Release locks from complete train occupancy/tail-clear evidence through the Stage 1 service.
- Provide in-game authoring and inspection sufficient to create, modify, delete, diagnose, and dry-run the agreed MVP configuration.
- Render signal state through Bukkit/Folia-safe lifecycle and location ownership, with idempotent cleanup.
- Support world load/unload, plugin enable/disable, host shutdown, topology changes, and administrative recovery without leaked claims or silent authority expansion.
- Implement the documented game-grade model with bounded braking-horizon scans and transition-driven region/interlocking updates; do not add certification-only mechanisms without a gameplay requirement.
- Compile and validate every statically reachable automatic timetable route before apply. The audit includes route references, complete protection coverage, signal-package/runtime health required by the route, interlocking validity, controlled-resource coverage, and exit clearance against the applicable train assignment/envelope.
- Apply one timetable revision atomically with a revision-pinned compiled plan and a shared host infrastructure-lease footprint. Structural signalling faults fail apply with actionable diagnostics and acquire no partial leases. Transient train occupancy/live route conflicts remain runtime waits and do not fail apply.
- Keep editable timetable drafts separate from applied plans. Draft save and dry-run validation acquire no leases and cannot start automatic services. Explicit apply transitions one validated revision to `APPLIED`; unapply enters `DRAINING`, blocks new departures/grants, and releases leases only after live work is safely cleared or administratively resolved.
- Refuse edits or deletion of leased rails, turnouts/diamonds, protection regions, signals, stations/depots/waypoints, lines, or other referenced objects until the owning timetable revision is unapplied or safely replaced.

## Acceptance criteria

- [ ] The Signal JAR installs beside JavaRailways and is absent from the JavaRailways host JAR.
- [ ] Signal imports only documented Stage 1 public contracts from JavaRailways.
- [ ] The agreed MVP calculates restrictive/permissive states from coherent epoch-tagged observations.
- [ ] Conflicting routes cannot both be confirmed; required turnouts remain locked until complete tail release.
- [ ] A single player-authored region can protect a simple turnout or a multi-turnout station throat without internal per-turnout stop boundaries.
- [ ] Region validation rejects leaks, ambiguous interiors, invalid directional gates, insufficient exit clearance, and unsupported overlaps before activation.
- [ ] At-grade diamonds are detected/updated outside the runtime tick loop, appear in route resource sets, prevent conflicting passages, and never create a cross-line route.
- [ ] ATO obeys Signal-owned authority/stop/speed constraints and stops before an unavailable route.
- [ ] Signal disable, failure, world unload, and stale-state tests prove that authority never widens silently.
- [ ] Signal configuration round-trips across restart; corrupt and orphaned data is preserved/rejected with actionable diagnostics.
- [ ] Authoring supports dry-run validation and creates a recovery point before destructive topology-related migration.
- [ ] Rendering reflects the authoritative aspect and cleans up every spawned resource.
- [ ] Unit, integration, packaging, and Folia smoke checks pass.
- [ ] Apply/replace/unapply tests prove whole-plan validation, atomic shared lease ownership, actionable signalling diagnostics, edit blocking across every referenced resource kind, and separation from transient runtime route conflicts.
- [ ] Draft save/validation leaves applied revision and lease state unchanged; only explicit apply enables service start, and draining unapply cannot release leases beneath live runs or claims.

## Out of scope

- Embedding Signal storage or presentation into JavaRailways snapshots or WebUI.
- Overlapping or nested protection regions in the first release; adjacent no-clearance regions must be merged or compiled as one interlocking group.
- Multiple national signalling systems in the initial release.
- Permanent runtime compatibility for arbitrary pre-release Signal formats.
- Certification-grade communications, train-integrity proving, redundant control channels, and country-specific operating regulations.
- Deciding whether WebUI becomes a separate module or belongs to `SimpleATO`/`SimpleATOBukkit`, and finalizing the exact signal JavaScript API, during the extraction deliverable.

## Dependency

- Blocked by completion and API review of `09-18-signal-addon-host-apis`.

## Key operating-model decision

- The first release implements player-authored protection regions and interlocking. JavaRailways' existing dynamic following remains active as the continuous-separation constraint. Both feed the single host constraint pipeline; Signal does not implement a separate moving-authority controller. MA is exposed only as the consolidated permission boundary when useful for diagnostics or integration.
- Entry-to-exit claims are atomic and require exit clearance. Requests never retain a partial claim while waiting; deterministic queuing/aging addresses starvation separately from deadlock prevention.

## Deferred package-runtime decisions

- The exact downstream indication/model state exposed to JavaScript is not a prerequisite for the module extraction, protection-region model, resource arbitration, or host integration. Finalize it only when implementing the signal-package runtime slice.
- Guided/shunting workflow details are likewise deferred; the refactor preserves typed authority-mode extension points without committing the first package release to those workflows.
