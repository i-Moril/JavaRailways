# Duty timeline validation — 2026-10-27

- Frontend:217 tests/37files; TypeScript+Vite productionbuild pass.
- SimpleATO633/Bukkit640/WebUI36 tests pass (1309 total). Real isolated simulation tests cover explicit idle gap, final movement, async ticket success/rejection and unfinished task; dispatcher tests cover halt/store/formation handoff.
- Offline migration:14 tests with real candidateJava validators, dryrun no writes, backup/apply/idempotency, source changes and precompiled helper hashpin. JRE-only deployment host uses locally compiled release17 helper; both candidateJAR and helperclass pinned in plan.
- Browser actual packagedWebUI:program start700→801 by pointer drag; resize100→201, one Undo restored100/Redo201; simulatedmode unknown; pointmode zero; rightclickremove RUNref leaves program and run definitions; Undo restores; program source opens library; save/readback and serverrestart+reauth restore actual task801. Light/dark screenshots inspected, no browsererrors. Added missing panel margins and clearing initialPosition when selecting acquisition selector.
- RUN edits and PROGRAM edits reorder timeline in same document transaction, tiesstable. Manualduration and mode do not change generated runtime input; authoring errors hide compiled/simulatedresults.
- Localbrowser bridge deliberately does notsimulategame operations;501 compile/sim path is not an acceptance substitute for actual backendtests. Player/game acceptance pending;task remainsin_progress/unarchived.
