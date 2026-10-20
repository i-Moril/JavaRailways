# Review follow-up validation (2026-10-20)

Three userP2 findings are being fixed on the same alignment worktree. Prior ae862ac8 gates are the before baseline; this report tracks the new candidate separately.

- Before snapshot: ../simulation-review-fixes-baseline-20261020 (48 source paths, zero drift).
- Current candidate: review-candidate.json (52 source paths); both files were held in snapshot/red-current during the RED diagnostic, then restored and byte-verified; the full final gate used the restored fix.
- Focused current build: BUILD SUCCESSFUL in3m11s, Bukkit39 + Signalsmanager159 =198 actual tests,0 failure/error/skip; Java API consumer compiled. XML preserved in snapshot/evidence/focused and log in focused.log.
- Docs npm run check passed after observer/lifecycle changes.
- New regression coverage: exact source retirement/current object identity, active restriction/inspection safety and stall failure retry, plugin disable/globalhalt cleanup, manager partial failure and sameUUID reload/clear-install race; observer default semantics, destroyed-car retirement and survivor close, same real formal signal/s100/timetable/coupling joint cases.
- RED proof passed: baseline manager and gateway ONLY produced2 expected failures (missing retirement barrier; stale retired-car failed Destruction stage reobserved). Both current files were then byte-restored, all52 candidate hashes matchedc781ed4f. XML/logs retained in snapshot/evidence/red and /tmp/simulation-review-red.log.
- Final current gate passed: Bukkit542/122 suites +Signals647/59 suites =1189 tests/181 suites,0 failures/errors/skips,13 new regression cases. BUILD SUCCESSFUL3m48s; Java consumer, Signal module/JAR boundaries and Bukkit shadowJar passed. Docs check passed. Source52 hashes remainc781ed4f; dev still87fe3e45.
- Independent Trellis review found no unresolved product finding; report review-fixes-check.md. Machine evidence and XML/log hashes: review-final-evidence.json. Other unaffected modules/frontend remain prior historical evidence, not freshly repeated or added to the1189 count. No commit, merge, push, deploy.

## Result by finding

1. Failed LIVE installation retires only matching source objects while holding its installation management gate, before engine close. New host retirement preserves same-ID/new-World replacements and other owners; active-state safety gates are maintained. Unconfirmed retirement leaves a closed reservation and live engine for explicit retry; clear/install overlap cannot drop it.
2. SIM car extension has no effects history or effectSnapshot. A nullable internal observer is absent by default; only explicitly injected test collectors allocate/retain observation events. Default animation validation, delays and completion stay real.
3. Gateway no longer has ownedCars. Core actual destruction owns retired extensions; gateway close snapshots only current cars and attempts all closes. A real failed retired Destruction stage is not observed again, and surviving cars still close.

No persistent format/version changes, compatibility adapters, weak-reference/GC checks or new global locks were introduced.
