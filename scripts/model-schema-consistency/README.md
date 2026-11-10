# Development train model schema conversion

One-time source conversion for the unpublished development train model format. Runtime readers accept the current shape only; versions remain unchanged. Requires Python 3.10+ and PyYAML. This tool does not alter saved world data or parsed snapshot DTOs.

The conversion removes `config-version: 1` from inline train part definitions and replaces named animation definition lists with maps keyed by their exact former names. Standalone document versions stay intact. Installation lists, action execution lists, composition, animation commands/keyframes, resource identifiers and order stay intact. Duplicate keys/names, conflicting inline versions and unsupported shapes fail before any source writes. Every converted document is decoded again and compared with the exact planned semantic transformation.

```sh
python3 scripts/model-schema-consistency/migrate.py models --report /tmp/model-schema-plan.json
python3 scripts/model-schema-consistency/migrate.py models --apply \
  --backup /tmp/model-schema-originals --report /tmp/model-schema-applied.json
```

`--apply` requires a new backup directory outside the input root. All affected originals and a before/after SHA256 manifest are backed up before the first replacement. Each file is replaced via a same-directory staged rename after checking that planned source bytes remain unchanged. Restore the exact manifest-listed originals to roll back. For a live server, keep an additional durable external archive and coordinate the reader/plugin deployment; do not reload an old reader against converted sources. ZIP files preserve member order, metadata, comments and all untouched member bytes; only YAML members whose content changes are rewritten. Minecraft resource pack contents are unchanged.

The tool intentionally does not recognize ordinary standalone model scene animation formats as train part definitions. Those are separate contracts. It also does not scan or rewrite JSON world/model snapshots.

## 2026-11-10 execution record

Local `models/`: 16 standalone s100 part YAML files plus `s100.zip` and `jr-route-indicator.zip` converted. The latter contained inline part versions. The parent display task's parts-tree example was converted. Local originals and SHA256 plans: `/tmp/jr-schema-models-backup`, `/tmp/jr-schema-examples-backup`, `/tmp/jr-schema-models-applied.json`.

Test-room instance: `836082a48c4f48fcb1fea4e83c57dc96` at `user@192.168.1.230`. Source boundary is `plugins/JavaRailways/trains` plus its distributed `config.zip`; 16 YAML files and `config.zip` change. `train-package-snapshots` and `model-snapshots` are already parsed DTOs and remain byte-identical. Server candidates were derived from the actual captured server package, not from the local example package.

Durable server recovery directory: `/opt/mcsmanager/backups/javarailways/model-schema-20261110/`. It contains `originals.tar`, `candidate.tar` and `deployment-state.json`, with exact original/candidate file hashes and the installed reader JAR hash. Recovery archive SHA256: `9278e6347dfa9a42b781461453fb92b7d5d3d86a321730b468f866185dd69d7d`; candidate archive SHA256: `3fecd9f3e3f73bd15b0ffd5f7665e188702a540d3c884e60f20fef49e4804a1d`.

Status: candidates staged on the server, live package files unchanged pending coordinated deployment with the new reader. No plugin deployed or reload/restart performed. Source/test source compilation passed (`/tmp/jr-model-schema-compile.log`); test suites, actual server parsing and game-client acceptance are deferred to unified verification. This staging record must not be represented as a completed live package migration.
