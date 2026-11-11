# Research: Core codec closeout / CR-029

> 实施采用三处map属性显式绑定`ExtensionDataMapSerializer`，保留既有`SavedTrainRenderState.version`字段；下文删除该字段或基类serializer注解只是研究备选，不代表最终改动。当前实现合同由Core extension-data规范拥有，构建/迁移验证见任务implement.md。

- Query: What is the smallest explicit serializer-registry, version-preflight and offline-migration change remaining after the frame-boundary series?
- Scope: internal; source and existing fetched-save research only. No build, SSH, Git, deployment, or runtime mutation.
- Date: 2026-10-14

## Findings

### Current gap and approval

`Core/src/main/java/work/art1st/javarailways/core/persistence/WorldSnapshotCodec.kt:19-25` dynamically finds serializers by runtime class and executes `Class.forName` on the stored discriminator. Both decode functions check `formatVersion` only after decoding the complete document (`:39-55`). This is CR-029, not a newly discovered architecture project. The current Core persistence and extension specs describe the desired registry but do not provide one.

The existing task PRD, design, repository rules and `shared/persistence.md` already authorize strict current-only namespaced identities and auditable manual migration of the development-server save. No additional product decision is needed to implement the following minimum. Preserve current Core infrastructure=2/runtime=1 and all unrelated outer/Basic versions. The existence of old development-server files is not a released/frozen baseline and does not authorize a version bump or runtime legacy alias.

### Actual payload inventory

- `BukkitPlugin/.../extension/train/TrainRenderPersistence.kt:7-12`: `SavedCarRenderState`; written by `CarExtension.save` and consumed by `CarExtension.initialize` (`CarExtension.kt:78-99`). Suggested stable ID `javarailways:car_render_state`, schemaVersion=1.
- Same file `:15-19`: `SavedTrainRenderState`; written by `TrainExtension.save` (`TrainExtension.kt:158`) and read by `initialize` (`:43-49`). Suggested stable ID `javarailways:train_render_state`, schemaVersion=1.
- `BukkitPlugin/.../render/config/ModelConfig.kt:40-42`: `CompositeModelConfigV1` also inherits the base, but the full repository search finds only its declaration, no writer, constructor call, save consumer or registry. Do not invent a legacy/model payload registration merely because inheritance exists. Removing that obsolete inheritance is an independently reviewable cleanup if needed; no whole model-pack rewrite is required.
- No production concrete payload exists in Core, Basic or SimpleATO. Full repository search for `SerializableExtensionData(` found exactly the three declarations above.
- Runtime maps are `SavedTrainV1.extension`, `CarConfigV1.extension`, `SavedBogieV1.extension` (`Core/.../physics/train/TrainPersistence.kt:19,82,112`). The stable extension namespace key `BUKKIT` is independent from the payload type ID; do not rename it or persist `Plugin.identifier` slots.

### Minimum runtime implementation

Keep generated Kotlin serializers for concrete payloads and the existing strict Json. Avoid changing global Json `classDiscriminator`: it would also affect unrelated polymorphic curve/config DTOs.

1. Add one Core-owned explicit registry at the extension-data persistence boundary. A concrete API can be `register(typeId: String, schemaVersion: Int, payloadClass: KClass<T>, serializer: KSerializer<T>)` for `T : SerializableExtensionData`. Two maps indexed by exact KClass and stable ID suffice; no classpath scanning, reflection, plugin discovery or serializer inference. Reject ID/class conflicts. Re-registering an identical owner/type/version during ordinary initialization may be idempotent, but must not silently remap an ID to a different class. Match existing startup registration conventions rather than creating runtime hot-reload/discovery infrastructure.
2. A small custom `KSerializer<SerializableExtensionData>` writes/reads exactly `{typeId,schemaVersion,payload}`. It looks up the registered generated serializer and encodes only `payload` with it. `@Serializable(with=...)` on the base is a plausible least-invasive way to make all three typed maps use it; alternatively use an explicit map-value serializer on these three declarations. Verify the selected Kotlin serialization integration with a real nested map test rather than assuming polymorphic default registration changes the envelope correctly. Keep transport logic out of Train/Car/Bogie business code.
3. Unknown class on write, unknown ID/version on read and malformed envelope all fail explicitly. Validate envelope version before invoking the registered payload decoder. Do not add upcasters until a supported old version actually exists. Preserve strict unknown-field checks for envelope and payload. The registry is startup configuration, not a new per-tick lookup/cache concern.
4. `WorldSnapshotCodec.decodeInfrastructure/decodeRuntime` first parse a JSON object, validate exact expected `formatVersion`, then decode that same tree with the current serializer. This prevents extension decoder invocation for unsupported root versions; parsing a tree is not materialization. No new schema or second world reader. Keep encodeDefaults=true and ignoreUnknownKeys=false.
5. Put Bukkit's two explicit generated serializer registrations in one testable function/object, e.g. `BukkitExtensionDataSerializers.register()`, invoked during `BukkitPlugin.onEnable` before `worldManager.start` (`BukkitPlugin.kt:159,296`). Tests using the codec without a live plugin invoke that same function, not an independent fake registration table. Core must not import Bukkit classes. A public startup registration entrypoint is sufficient for future external owners.
6. `SavedTrainRenderState.version` is currently unused by its consumer. Once schemaVersion is enforced by the envelope, avoid maintaining two independent versions for the same payload. Removing that redundant body field can be done in this current undeployed-format change; the offline converter must check old `version==1` and move it to the envelope rather than silently accepting arbitrary versions. This does not require a new user decision or root version bump.

`ConfigLib/.../persistence/Persistence.kt:34` has `registerDependencyEntity`, but it binds a domain object to a serializer plus live materialization dependency. It is not an extension type registry and should not be repurposed: this boundary must decode DTOs without DI/world/platform side effects. Reuse Kotlin's generated serializers/Json, not a parallel reflection or generalized loader framework.

### Minimum migration deliverable and scope

The preserved save research is `research/dev-server-save-audit.md`. Its local evidence directory `/tmp/javarailways-dev-save-6c0B2pq1` still exists at inspection, containing source.tar.gz, before/after manifests, worlds, rails and trains. It is temporary, not a durable backup. Do not change it.

Implement a one-shot offline transform of the three known extension-map locations only, for explicitly allowed FQCN discriminators:

- `work.art1st.javarailways.bukkit.extension.train.SavedCarRenderState` -> car_render_state;
- `work.art1st.javarailways.bukkit.extension.train.SavedTrainRenderState` -> train_render_state;
- unknown discriminator, wrong payload owner/shape, conflicting already-current/legacy shape -> fail with file and JSON path, not guessed/ignored;
- preserve all train/car/bogie IDs, order, placement, references and unrelated JSON. Read the real legacy serialized discriminator shape from a fixed fixture/current-before-change serializer evidence; do not assume it is the proposed envelope.

A script under `scripts/` fits the existing one-shot migration convention (`scripts/migrate_physics_config.py`), but should default to read-only dry-run, report source hashes and counts, and write only to a distinct newly created output directory on explicit application. Keep source archive/manifests immutable, report each changed path, reject collisions, and validate transformed core runtime with the current Kotlin codec. Do not put legacy aliases back into the plugin reader. Fixed fixtures belong to offline-tool tests; the production current reader should reject the old FQCN shape.

All 12 actual runtime documents in the fetched sample have zero trains; extension transformation count is therefore **zero**. A dry-run and current Core runtime decode on that sample plus nonempty fixed offline fixtures is meaningful CR-029 evidence, but cannot prove conversion of nonempty real trains.

The sample also has old outer1/Basic1 ownership and six invalid curve point pairs. These are preexisting separate migration gaps. `RailInfrastructureMetadataMigration.migrate(source)` already performs a pure infrastructure ownership conversion with strict DTO revalidation (`BukkitPlugin/.../persistence/RailInfrastructureMetadataMigration.kt:38-91`). It does not migrate complete runtime ownership or repair curve geometry. Do not turn CR-029 into rebuilding all migration machinery, and do not claim full development-server migration/materialization has passed after an empty extension conversion. Whole-save game-test readiness still requires that separate work; source audit closure and deployment readiness must remain separate.

### Tests / files to change

- Core registry + serializer in `core/api/util/plugin` or `core/persistence`, keeping one authoritative registry, plus `SerializableExtensionData.kt` if its serializer annotation is used.
- `WorldSnapshotCodec.kt`: remove both reflection fallbacks; preflight versions from JsonElement.
- New Core extension codec tests with one minimal generated test payload: fixed current envelope and nested map round-trip; unknown type/class/version; duplicate mapping; malformed/unknown envelope and payload fields; registered decoder counter remains zero on future root/payload version. Test registry instances in isolation rather than introducing a production global-clear API for tests.
- `WorldPersistenceRoundTripTest.kt`: add future root version containing otherwise malformed/unknown nested payload; assert root version error wins, proving ordering.
- Bukkit registration helper, `BukkitPlugin.kt`, `TrainRenderPersistence.kt` if redundant body version removed, and `WorldSnapshotExtensionDataTest.kt`.
- Improve that existing Bukkit codec test: currently it puts `SavedCarRenderState` in a *train* extension map (`WorldSnapshotExtensionDataTest.kt:162-189`). Use train state at train scope and a real car fixture with car config extension at car scope; assert stable IDs, hashes/heldScenes preserved, and absence of FQCN in output. Reuse existing fixture builders; no requirement to populate live Bukkit JS/render objects for a DTO codec test.
- Audit other codec tests referencing `SavedCarRenderState` / `SavedTrainRenderState` and call the same registration helper wherever data is encoded/decoded without plugin startup. Do not broaden empty Core snapshot tests to depend on Bukkit.
- Offline transform script/test fixtures; preserve real-source manifests and report old/nonempty fixture versus real zero-record evidence separately.
- Main-owned contract updates: Core `persistence.md`, `extension-data.md`, shared persistence paragraph currently labeling reflection as legacy; issue CR-029 and execution log. Do not claim all world-load rollback/prevalidation targets become implemented.

## Related Specs / External References

- `.trellis/spec/javarailways/shared/versioning.md` and `ai_tasks/persistence_schema_evolution_rules.md` §§7–10: unchanged development versions, envelope identity, current-only business and one-shot old-data repair.
- `.trellis/spec/javarailways/core/persistence.md` and `extension-data.md`: current boundary versions, host ownership, startup ordering.
- No external docs or dependency queries used; no unverified kotlinx API snippet should substitute for compilation/tests of the selected serializer binding.

## Caveats / Not Found

- No code edits, tests/builds, Git operations, SSH or save transformation were performed by this research.
- The registry design is an implementation plan, not a statement that it exists or the custom serializer binding has compiled.
- No outside-repository plugins exist per the user's prior confirmation; future owner registration remains supported explicitly, without retaining arbitrary FQCN deserialization.
- The complete old-server migration, durability of `/tmp` evidence and in-game validation remain distinct follow-ups; none should be marked passed by this source inspection.
