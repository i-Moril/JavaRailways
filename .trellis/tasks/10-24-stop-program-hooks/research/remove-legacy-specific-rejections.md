# Remove old-format-specific rejection logic

User requested removal on2026-10-27 after clarifying generic current schema validation vs dedicated oldfield recognition. Existing task continues.

Delete runtime/frontend special checks for old per-call arrivalProgram and corresponding migration strings/tests. Remove dedicated old archived/arrivalSteps/rows/DISABLED/ARRIVAL and removed DSL API negative fixtures. Retain only current field/enum/type/revision validation; current call field allowlist uses ordinary unexpectedField tests. Offline migration tools stay separate and do not enter runtime. No persisted model or version change, no further data migration needed.

Ownership: remove_legacy_guards owns frontend + EditorDocumentValidator/test; root owns otherobsolete-specific tests, docs and gates. Run full frontend tests/build, fullWebUItests and affected SimpleATO/BukkitScript suites.

## Verification
Frontend221/fullWebUI36/focusedSimpleATO5/focusedBukkit37 tests pass; TypeScript/Vite build pass. Dedicated oldformat fixtures removed instead of treating them as a supported input family. Currentcalls minimal/full fieldset+neutral unknownfield test retained. Reviewer found no codeissues. No persistence format change or migration.
