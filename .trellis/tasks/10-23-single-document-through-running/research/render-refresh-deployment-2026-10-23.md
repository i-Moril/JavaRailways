# Render refresh deployment — 2026-10-23

- User authorized commit and deployment. Source commit: `52f3ab484c7be316511bc91fe852a52994b0e72b`.
- Verification before deployment: BukkitPlugin 590 tests passed; shadowJar build passed; nested WebUI resources checked.
- Target: JavaRailwaysTest, instance `836082a48c4f48fcb1fea4e83c57dc96`, user@192.168.1.230. One player (__ART1st__) was online before the announced restart.
- Normal stop completed at 13:38:05 UTC: all worlds and player data saved; MCSManager status 0 and PID 0 verified.
- Recovery: `/opt/mcsmanager/backups/javarailways/station-programs-20261022-dvjmkzig`. Existing backup tool retains its historical directory prefix; this backup was created October 23.
- Archive SHA256: `9231344df1c877c51a977c5ce7b363ec8c76137eee0e1fdf4f64e3fa1f5324cc`; inventory contains 132 files.
- Only JavaRailways.jar replaced. Installed SHA256: `93dd1cd6d3655bdcb9eb8e7383d8cc76473d7f315c70977bff0007c882888d6c`. Install audit confirms plugin data and other plugin jars unchanged.
- Server startup completed 13:41:48 UTC (26.898s). Bridge health returned `{"ok":true}`; WebUI HTTP 200. Live index, JS and CSS byte-match the nested WebUI artifact.
- At 13:42:21 UTC: one stationary train (`active=0/1`), five spans, visual region dispatch `tasks=5`, `pendingCars=0`; platform region completion `10/10`. Before fix visual dispatch tasks were zero. Initial stationary refresh now reaches region dispatch.
- ATO world RUNNING; SimpleSignals installed, definitionRevision 46, topologyRevision 1, regions/tables/blocks 6 each, fixed/protection signals 2 each.
- No client visibility acceptance yet: audience and packet model counters remain zero without a viewer. Server-side dispatch is confirmed, not in-game appearance.
- Startup retained the known Terra `theUnsafe` error and one physics startup overload/clamp warning. A diagnostic command sent too early during startup produced a Folia console-source getLevel NPE at 13:41:49; the same command succeeded after startup at 13:42:21. This was a premature verification command, not evidence of a render failure.
