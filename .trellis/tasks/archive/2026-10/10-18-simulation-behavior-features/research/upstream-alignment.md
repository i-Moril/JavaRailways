当前最终验收已更新为53源c1d805b9、8模块2507项零失败；见 [父最终记录](parent-acceptance.md) 与 [独立复核](parent-acceptance-check.md)。下文旧候选/实施中列表仅保留历史，不代表当前开放项。

Subsequent lifecycle review fixes supersede this ae862 source snapshot; see [review-fixes-validation.md](review-fixes-validation.md) for c781ed4f and fresh1189 affected-module tests. The alignment record below remains historical.

# Upstream alignment (2026-10-20)

User requested compatibility with latest dev/signal-system while retaining prior no-immediate-merge instruction.

- Original candidate: codex/simulation-signal-features, base9ed278181a577ebc1450bd307fe466d47ed91f96; preserved.
- Byte-verified backup: ../simulation-alignment-snapshot-20261020/manifest.json and candidate.patch;96 candidate paths plus separate docs.patch. Node dependency links excluded.
- New candidate: codex/simulation-world-alignment, base87fe3e4567c4b9fddbcee3c0b5975e460a7e05c7.
- Upstream changed464 paths;15 source/test overlap paths. Actual three-way application had3 code conflicts (Bukkit factory, SimpleATO simulation and its tests) and1 specification conflict (car-behavior).
- This alignment leaves the reviewable candidate uncommitted and does not merge it into dev. Main dirty task artifacts are preserved.

## Change boundary

Adapt added simulation consumers to the current Core-owned infrastructure registry/resource DTOs, protected topology, owned construction, completion-stage destruction, guest-error callback and ATO capture. Preserve upstream per-selection authoring retry while retaining per-World signal evaluation/manager lock ownership. No resurrection of removed API, duplicate arbiter, persistence compatibility/version change, or change to global fatal host policy.

The upstream fatal host policy is materially relevant: RailwaySimulationHalt terminates all coordinators in this process. Normal business and guest-script failures must remain local; unconditional failure isolation cannot be claimed under this policy. This is recorded as an explicit parent-goal qualification, not silently marked passed.

## Evidence (historical first gate)

All prior2411-test signal gates and60-case behavior gates are historical old-baseline evidence. New source must compile, run current module tests and receive independent review. Pending results are recorded here by exact source fingerprint, not inferred from conflict resolution.

First current gate ran375 SimpleATO tests and exposed a real borrowed-registry retirement leak: admission close retired its listener, but retained host claims in the World arbiter. The SIM host now retires only its completed claims, exact requester dependencies and validator token, retaining pending reservation protection. The enhanced real test failed before this fix and now passes in the repeated375-case run. Independent checker reviewed cleanup. Three old consumer API calls in new tests were migrated without production visibility widening.

## Final current result

Aligned with `dev/signal-system@87fe3e4567c4b9fddbcee3c0b5975e460a7e05c7`. Final48-source fingerprint: `ae862ac844d99e997c76bbf840a6ded5fe589b1d44a037f989e0effabcbcef79`. Every source hash rechecked after the gate; upstream still at the same commit.

- Aggregate gate: BUILD SUCCESSFUL,8 JVM modules /364 suites /2491 tests,0 failures/errors/skips. Gradle reused this session's passed unchanged tasks; Bukkit's full532-case XML was regenerated after focused joint execution. This is current combined-source evidence, not the old2411 result.
- Core505; Basic390; SimpleATO375; SimpleATOBukkit2; Bukkit532; SimpleSignals644; TunnelBridgeBuilder31; WebUI12.
- Frontend108, production frontend build, docs-site check, public Java consumer, module/JAR boundaries, shaded package, Core/Basic/SimpleATO Dokka passed. Dokka retains existing undocumented declarations/external package-list download warnings; the new simulation API emitted no undocumented warning. Kotlin/Gradle and JDK/native-access warnings remain non-fatal.
- New joint cases actually passed in full XML: real official four-light + s100 JS + Dispatcher delay across two independently progressing targets; actual collision-owner coupling preserves pending exact-car JS and resolves incoming controllerless profile. Core separately tests contact detection.
- Guest load/declaration/call failures and post-split exact owner, true destruction stage, grant/edit conflict and target-owned retirement, one-time authoring retry all passed.
- Fixture setup initially missed required finite static speeds and matching AB operation directions; both were authored through real Basic APIs before capture. No signal/gateway/completion assertions or production rules were relaxed. A Java fixture now explicitly supplies upstream's bidirectional constructor flag.
- Independent current-source review: upstream-check-result.md. Machine evidence with XML/log hashes: upstream-final-evidence.json. Byte-verified original backup and complete logs remain in ../simulation-alignment-snapshot-20261020.

No commits, merge into dev, push or deployment. Main source and original candidate retained. The full World task is not archived by this compatibility step; progress/commit and parent acceptance remain explicit task workflow.
