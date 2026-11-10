# Station metadata and WebUI JSON editing

User approved: each world/station owns structured metadata, edited directly as JSON in authenticated WebUI, persisted and projected to screens. Default {}, arbitrary JSON-compatible nested values, unknown custom fields retained. Metadata is data only, never executed. Same station shares one object across berths. Malformed/non-object input must not overwrite stored values or discard editor text. Current/next/remaining station screen objects carry metadata. Save refreshes display input without model rebuild.

Maintain existing station/berth identity and operating behavior, PAPI/per-viewer deferred, no server deployment/commit. Preserve prior dirty changes. Integrated tests/server/client verification deferred by user; compile source/test source and frontend type-check only; author meaningful deferred cases.

Acceptance: immutable station metadata, retention across berth edits, world/station isolation, old snapshot missing field defaults{}, normal world save/restore, authenticated GET/PUT with optimistic old-value conflict, JSON editor state isolation/stale request protection and bilingual errors, screen projection and bounded refresh.
