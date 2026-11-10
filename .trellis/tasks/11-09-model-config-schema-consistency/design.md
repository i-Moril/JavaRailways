# Model configuration consistency

## Approved boundary

The user authorizes implementation plus current examples and test-room packages. Fix document wrapping in PartRegistry: standalone parts retain required config-version=1; inline part content forbids its own config-version and inherits the containing validated document. Named part animation definitions become a map keyed by exact name, with no inner name. Keep internal PartDefinition animation list if useful, preserving insertion order and the existing runtime reference contract; do not alter action execution lists or installation/composition lists. Reject duplicate YAML mapping keys before construction at touched model loading boundaries.

Common schema content is reused with distinct standalone and inline wrappers. Synchronize affected test fixtures, examples, distributed models and ZIP entries. Preserve all current display/operational dirty work; no broad reverts. No new published/frozen baseline is evidenced by this development task or server deployment; per repository development policy retain current version numbers, no legacy runtime reader. Saved snapshot DTOs remain unchanged unless source evidence requires it; source YAML conversion is a one-time tool.

## Migration and rollout

Root owns an auditable dry-run converter, semantic before/after validation, exact file hashes, archives and remote replacement. Convert only part definitions and car inline part trees, never action execution animations. Refuse duplicate names/keys, conflicting inline versions and unknown shapes rather than overwrite. Remote test-room packages are explicitly authorized; discover source/ZIP/snapshot copies before choosing exact boundary. Preserve recovery points outside live package roots and replace narrowly; do not deploy a plugin JAR or reload an incompatible old reader as part of package conversion. Record server runtime baseline and resulting compatibility boundary.

## Ownership

Implementation agent owns production PartRegistry/YamlLoading and minimal affected loader changes, official schemas and Kotlin test fixture updates. Root owns examples/model distributions, migration tool, task/spec/documentation, compilation and server operations. No tests or game servers launched now: user requests one final integrated validation. Author regression cases and compile source/test source only. No commit/push.
