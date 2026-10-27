# Obsolete compatibility cleanup deployment — 2026-10-27

- Source:94a3a54d19aa437748c954f504662775dd6da681.
- Main SHA256:38d95026e93695e04d7be3152b89dbbe0a325d7702347d43bae4f2b4b1295533.
- WebUI SHA256:91cbd1fc93b0700b70905bf41822ba21a67b922001e9764adbf74e5e2602aa06.
- Normal stop/world/global/player save complete07:21:58 UTC, status0/pid0 confirmed.
- Recovery:/opt/mcsmanager/backups/javarailways/merged-review-20261023-1r0ct1_i. Archive SHA256:886b97aa6da222e87d97451ae1c8e45ea00b78a5f4afa94d0f6fdd4d22ae7da6,136 plugin-data files.
- Candidate installed07:22:49 UTC. Only main plugin replaced, other JARs unchanged.
- Stopped-library dry-run planned1rewrite/0delete; hash-pinned apply completed with source copies in recovery/library-migration. Current Java repository validated1world/1record, including definition semantics and canonical content hash.
- Before startup, complete plugin-data inventory equals backup inventory plus exactly the single planned library rewrite; migration-audit.json saved in recovery. No old arrivals/rows/ARRIVAL checkpoint data found, so no timetable runtime migration required. Unrelated model platform rows preserved.
- Server ready07:23:54 UTC; bridge health ok, sidecar matches nested JAR,17resources match; public HTML/JS/CSS matched (index-DIKZsiy_.js,index-GM07XQUN.css). Existing plaintext HTTP/startup physics warnings remain.
- Tests:2265backend,222frontend,8migration all pass; no skipped tests in backend gate. In-game user acceptance remains pending, task stays in_progress/unarchived.
