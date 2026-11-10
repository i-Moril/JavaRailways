# Door-side display integration evidence — 2026-11-10

Worktree ddf5/JavaRailways, branch codex/simpledisplays-train-integration; last commit09da5fbc. Previous task changes preserved. This batch remains uncommitted and undeployed. No tests/server/game client run.

## Implemented boundary

SimpleATO adds immutable nullable context.doorSide in the actual operating basis. A nonnull value requires an exact entered currentRun and AT_STATION progress. Effective station-program side and accepted explicit OpenDoors overrides update the independently retained arrival evidence. Replacing/submitting a future mission does not clear this evidence; real movement, lost placement/formation, manual/ambiguous/no entered scope and derail invalidate it. Committed reversal swaps LEFT/RIGHT along with existing actual operation state; BOTH/NONE stay unchanged. This is opening-side intent, not measurement of animated door aperture or future station prediction.

Host captures car.operatingEnd on the simulation owner using the exact TrainControlManager.operatingControlEndFor and Train.behaviorRelativeTail helper used by actual TrainExtension door dispatch. The detached head/tail value is passed through existing sample cache, never recomputed on worker/region callbacks. operational.doorSide and carOperatingEnd participate in semantic context invalidation, so older direction output cannot install after an accepted changed context.

Decorations optional config door-side uses lowercase left/right relative to installed fixed car/model HEAD. Absent is unspecified. Existing payload schema-version1 retained (additive default-absent field), no reader/migration. Typed SDL draw receives ctx.data.display with static doorSide and derived openingSide this/opposite/both/none/null. LEFT/RIGHT first converts by per-car end then compares installation; BOTH/NONE don't require installation/end. Unknown directional facts suppress hints. No screen transform inference or new scheduler/live getter.

No actual template/glyph render change or model/server package replacement in this data slice. Metadata conventions,15-second language pages, and deferred vehicle-info switching discussion unchanged.

## Verification actually performed

- Independent read-only frozen-source Trellis review passed, no blocking findings.
- git diff --check and schema JSON syntax parsing passed.
- Serial production/test-source compilation BUILD SUCCESSFUL in8s,39 actionable tasks (not tests):

```
./gradlew :SimpleATO:compileKotlin :SimpleATO:compileTestKotlin :BukkitPlugin:compileKotlin :BukkitPlugin:compileTestKotlin :Decorations:compileKotlin :Decorations:compileTestKotlin --offline --max-workers=1 -Pkotlin.daemon.jvmargs=-Xmx4g -PsimpleDisplaysRepository=/tmp/javarailways-simpledisplays-xml-dom-maven
```

Log /tmp/jr-door-side-display-compile.log. Uses existing isolated XML/document API artifact repository, no SimpleDisplays source edits/build.

Authored but NOT EXECUTED: independent arrival-side/reversal/NONE/BOTH/clear/DTO invariants; actual Dispatcher origin+prepared-nextmission+departure fixtures for NONE, accepted explicit RIGHT override and station-program RIGHT override; host nullable-side projection/context invalidation, captured per-car end and metadata coalescing; Decorations strict config plus full side/end/installation matrix and typed DisplayContext fake-provider four-frame boundary.

Unified automated tests and actual Paper/Folia/client reversal+door indicators still required at user-requested final acceptance. Compilation and source review are not runtime/game acceptance.
