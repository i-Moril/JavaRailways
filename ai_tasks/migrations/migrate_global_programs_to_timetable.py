#!/usr/bin/env python3
"""Move the dev server's global program library into one timetable.

This is a one-time development-data migration. It copies every current global
program into the target timetable's own program directory, strips the removed
program revision/contentIdentity fields, removes boundPrograms from the target
editor document, deletes every other editor document, and finally deletes the
global program library. The script is dry-run by default and writes a recovery
copy before applying.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import tempfile
from pathlib import Path

DEFAULT_ROOT = Path('/opt/mcsmanager/daemon/data/InstanceData/836082a48c4f48fcb1fea4e83c57dc96/plugins/JavaRailways/webui')
WORLD = 'world'
TARGET = 'new-timetable-9'
PROGRAM_KEYS = {'formatVersion', 'id', 'name', 'definition', 'estimatedDurationTicks'}


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_json(path: Path):
    return json.loads(path.read_text())


def write_atomic(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=path.name + '.migrate-', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def program_ids(document: dict) -> set[str]:
    result: set[str] = set()
    for event in document.get('events', []):
        program = event.get('program') or {}
        if isinstance(program.get('programId'), str):
            result.add(program['programId'])
    for duty in document.get('duties', []):
        for element in duty.get('elements', []):
            program = element.get('program') or {}
            if isinstance(program.get('programId'), str):
                result.add(program['programId'])
    return result


def normalize_program(raw: dict, expected_id: str) -> dict:
    if not isinstance(raw, dict):
        raise SystemExit(f'program {expected_id} is not an object')
    unsupported = set(raw) - (PROGRAM_KEYS | {'revision', 'contentIdentity'})
    if unsupported:
        raise SystemExit(f'program {expected_id} has unsupported fields: {sorted(unsupported)}')
    if raw.get('formatVersion') != 1 or raw.get('id') != expected_id:
        raise SystemExit(f'program {expected_id} has an invalid version or id')
    if not isinstance(raw.get('name'), str) or not raw['name'].strip():
        raise SystemExit(f'program {expected_id} has no name')
    if not isinstance(raw.get('definition'), dict):
        raise SystemExit(f'program {expected_id} has no definition')
    result = {'formatVersion': 1, 'id': expected_id, 'name': raw['name'], 'definition': raw['definition']}
    if 'estimatedDurationTicks' in raw:
        value = raw['estimatedDurationTicks']
        if not isinstance(value, int) or isinstance(value, bool) or value < 0 or value > 9_007_199_254_740_991:
            raise SystemExit(f'program {expected_id} has an invalid estimated duration')
        result['estimatedDurationTicks'] = value
    return result


def collect(root: Path, target: str):
    docs_dir = root / 'drafts' / 'editor-documents' / WORLD
    source_dir = root / 'drafts' / 'station-programs' / WORLD
    target_path = docs_dir / f'{target}.json'
    if not target_path.is_file():
        raise SystemExit(f'target editor document not found: {target_path}')
    documents = sorted(path for path in docs_dir.glob('*.json') if path.is_file())
    source_files = sorted(path for path in source_dir.glob('*.json') if path.is_file()) if source_dir.is_dir() else []
    if not source_files:
        raise SystemExit(f'global program library is empty: {source_dir}')
    programs = [normalize_program(read_json(path), path.stem) for path in source_files]
    program_ids_set = {program['id'] for program in programs}
    document = read_json(target_path)
    referenced = program_ids(document)
    missing = referenced - program_ids_set
    if missing:
        raise SystemExit(f'target timetable references programs absent from the global library: {sorted(missing)}')
    deleted_documents = [path for path in documents if path.name != target_path.name]
    return docs_dir, source_dir, target_path, documents, source_files, programs, document, referenced, deleted_documents


def plan(root: Path, target: str) -> dict:
    docs_dir, source_dir, target_path, documents, source_files, programs, document, referenced, deleted_documents = collect(root, target)
    return {
        'mode': 'DRY-RUN',
        'root': str(root),
        'targetDocument': str(target_path),
        'targetDocumentSha256': sha(target_path),
        'programs': [{'id': program['id'], 'name': program['name'], 'sourceSha256': sha(source_file)} for program, source_file in zip(programs, source_files)],
        'referencedProgramIds': sorted(referenced),
        'deleteDocuments': [path.name for path in deleted_documents],
        'deleteGlobalProgramFiles': [path.name for path in source_files],
        'globalProgramDirectory': str(source_dir),
        'documentCount': len(documents),
    }


def apply(root: Path, target: str, backup_dir: Path) -> dict:
    docs_dir, source_dir, target_path, documents, source_files, programs, document, referenced, deleted_documents = collect(root, target)
    backup_dir.mkdir(parents=True, exist_ok=False)
    manifest = {'createdAt': __import__('datetime').datetime.now(__import__('datetime').timezone.utc).isoformat(), 'files': {}}
    for path in [*documents, *source_files]:
        relative = path.relative_to(root)
        destination = backup_dir / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, destination)
        manifest['files'][str(relative)] = sha(path)
    (backup_dir / 'backup-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')

    target_program_dir = root / 'drafts' / 'timetable-programs' / WORLD / target
    for program in programs:
        write_atomic(target_program_dir / f'{program["id"]}.json', (json.dumps(program, ensure_ascii=False, indent=2) + '\n').encode())
    migrated = {program['id']: program for program in programs}
    rewritten = dict(document)
    rewritten.pop('boundPrograms', None)
    write_atomic(target_path, (json.dumps(rewritten, ensure_ascii=False, indent=2) + '\n').encode())
    for path in deleted_documents:
        path.unlink()
    for path in source_files:
        path.unlink()
    if source_dir.is_dir():
        try:
            source_dir.rmdir()
        except OSError:
            pass

    # Verify the current model after the destructive part.
    stored = {path.stem: read_json(path) for path in target_program_dir.glob('*.json')}
    if set(stored) != set(migrated):
        raise SystemExit('migrated program set does not match the source library')
    if 'boundPrograms' in read_json(target_path):
        raise SystemExit('target document still contains boundPrograms')
    if program_ids(read_json(target_path)) - set(stored):
        raise SystemExit('target document has unresolved program references')
    if [path for path in docs_dir.glob('*.json') if path.name != target_path.name]:
        raise SystemExit('other editor documents remain')
    if any(path.exists() for path in source_files):
        raise SystemExit('global program files remain')
    return {
        'mode': 'APPLIED',
        'backupDirectory': str(backup_dir),
        'targetDocument': str(target_path),
        'programs': sorted(stored),
        'deletedDocuments': [path.name for path in deleted_documents],
        'deletedGlobalPrograms': len(source_files),
        'referencedProgramIds': sorted(referenced),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=Path, default=DEFAULT_ROOT)
    parser.add_argument('--target', default=TARGET)
    parser.add_argument('--backup-dir', type=Path)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    root = args.root.resolve()
    if not root.is_dir():
        raise SystemExit(f'webui root not found: {root}')
    if not args.apply:
        print(json.dumps(plan(root, args.target), ensure_ascii=False, indent=2))
        return
    if args.backup_dir is None:
        raise SystemExit('--backup-dir is required with --apply')
    print(json.dumps(apply(root, args.target, args.backup_dir), ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
