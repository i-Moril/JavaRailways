# Obsolete development compatibility removal

User explicitly requested removal of all currently unused old compatibility on 2026-10-27. Existing task stays active; no new task.

## Evidence and boundary
Module inventory and selected caller tracing: obsolete-compat-inventory.md. Confirmed obsolete production paths: archived program record field/UI/adoption guards; parallel per-call arrivalSteps/arrivalProgram DSL, saved model and dispatcher continuation; old ARRIVAL cursor branch; dead service-row editor compiler and rows-only metadata; orphan legacy text and unused Core SavedTrain/SavedCar/SavedBogie aliases.

Remove current unused paths coherently, not only parser fields. Current shared arrival/departure preparation, standard stop minimum-dwell behavior, runtime unit compatibility, immutable bound program snapshots, physical/resource-pack adapters and explicit offline migrations remain current functionality. Strict current decode rejects removed inputs. Existing development schema numbers stay unchanged, per user's explicit development status and repository versioning policy; no runtime reader/upcast.

## Ownership
- remove_archive: WebUI archived record/model/UI/tests.
- remove_old_arrivals: SimpleATO/Bukkit scriptworker parallel arrival execution/cursor/DSL/persistence/tests; dead rows metadata and frontend wire/navigation.
- archive_migration: one-time offline archived-record removal tool+tests only.
- root: dead schedule module/tests and Core aliases, docs, integration/tests/commit/deployment.

## Live data preflight
Remote in-place aggregate scan (no document content export): plugins/JavaRailways JSON has one archived=false record; no arrivalSteps/arrivalProgram fields. timetables/ and timetable-js/ contain no files; worlds contains13JSON+12bak. Library root webui/drafts/station-programs. Recheck after normal stop and use backed-up hash-pinned migration plan before installation. Existing snapshots are not reinterpreted by runtime compatibility.

## Acceptance
Run full affected Core/Basic/SimpleATO/Bukkit/WebUI tests with bounded heap; full frontend tests/build; migration dry-run/apply/conflict tests; shadow packaging; cross-layer review. Deployment only after normal save/stop, complete recovery snapshot, exact offline migration, narrow artifact replacement and current-validator/live health checks. Do not claim line-by-line entire-repository audit: inventory searched12modules and traced concrete candidates.

Additional live rows audit:160 generic rows hits all belong to model-snapshots parts/*/platform geometry (columns/rows/spacingX/spacingZ), not old timetable editor metadata; these files are intentionally untouched. No old timetable rows or ARRIVAL checkpoint arm found. Local isolated library migration dry-run/apply converted1record with separate recovery;8migrationtests and222frontendtests/build passed.

## Final gates
Core524 + Basic433 + SimpleATO630 + BukkitPlugin641 + WebUI37 =2265 backend tests passed with0failures/errors/skips. Frontend222 passed; TypeScript/Vite build passed; migration8passed; both shadowJARs packaged and nested/staticasset bytes matched. Current Java repository validated the locally converted library1record (no legacyreader).
