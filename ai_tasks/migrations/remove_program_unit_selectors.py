#!/usr/bin/env python3
"""Offline removal of program unit selectors; never a runtime compatibility path.

Dry-run: DOCUMENTS_ROOT LIBRARY_ROOT --webui-jar candidate.jar --plan NEW_PLAN
Apply: same plus --apply --backup NEW_EXTERNAL --instance-root STOPPED.
Changes only program definitions and their invocation arguments. Domain formation
and operational-trip unit slots are preserved. Source/frozen identities are checked
before transformation and recalculated with the candidate Java implementation.
"""
from __future__ import annotations
import argparse
import copy
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile

from migrate_duty_timeline import scan, ensure_stopped
from remove_program_library_archived import decode, output_json, digest, outside
from unify_program_string_types import JavaBridge

MIGRATION = 'remove-program-unit-selectors'

# Only expression-bearing action fields: literal JSON payloads are opaque.
EXPRESSION_FIELDS = {
    'SET_DISPLAY': ('values', 'removeKeys'),
    'WAIT_UNTIL': ('tick',), 'WAIT_DURATION': ('durationTicks',),
    'MOVE': ('destinationId', 'viaIds'), 'SELECT_STOP': ('stationId', 'berthIds'),
    'STORE_AT_DEPOT': ('depotId', 'stablingPointIds'),
    'EXTENSION': ('parameters',), 'BRANCH': ('parameters',),
}


def expression_parameters(expression):
    if not isinstance(expression, dict): return set()
    if set(expression) == {'parameterId'}: return {expression['parameterId']}
    if set(expression) == {'operation'}:
        return set().union(*(expression_parameters(child) for child in expression['operation']['operands']))
    if set(expression) == {'object'}:
        return set().union(*(expression_parameters(child) for child in expression['object'].values()))
    return set()


def convert_definition(definition):
    """Return removed parameter IDs and a count; ordinary JSON values remain opaque."""
    removed = {parameter['id'] for parameter in definition['parameters'] if parameter['type'] == 'UNIT_SLOTS'}
    definition['parameters'] = [parameter for parameter in definition['parameters'] if parameter['id'] not in removed]
    count = len(removed)
    references = set()
    def visit(steps):
        nonlocal count
        for step in steps:
            kind = step['type']
            if kind in ('SET_DISPLAY', 'RESET_DISPLAY') and 'unitSlots' in step:
                del step['unitSlots']; count += 1
            for name in EXPRESSION_FIELDS.get(kind, ()):
                if name not in step: continue
                expression = step[name]
                if name in ('values', 'parameters') and isinstance(expression, dict) and set(expression) == {'trainInfo'}:
                    bindings = expression['trainInfo']
                    obsolete = [key for key, field in bindings.items() if field == 'UNIT_COUNT']
                    for key in obsolete: del bindings[key]
                    count += len(obsolete)
                    if obsolete and not bindings: step[name] = {'value': {}}
                references.update(expression_parameters(step[name]))
            if kind == 'BRANCH':
                visit(step['thenSteps']); visit(step['elseSteps'])
            elif kind == 'ASYNC': visit(step['steps'])
    visit(definition['steps'])
    if removed & references:
        raise ValueError('removed unit parameter still referenced; manual resolution required: ' + ', '.join(sorted(removed & references)))
    return removed, count


def convert_snapshot(snapshot, bridge):
    definition = snapshot['definition']
    if snapshot['contentIdentity'] != bridge('rawIdentity', definition)['identity']:
        raise ValueError('source program content identity mismatch')
    removed, count = convert_definition(definition)
    identity = bridge('identity', definition)['identity']
    if count: snapshot['contentIdentity'] = identity
    elif snapshot['contentIdentity'] != identity: raise ValueError('unchanged definition identity differs')
    return removed, count


def convert_document(document, bridge):
    count = 0
    removed_by_program = {}
    for snapshot in document.get('boundPrograms', []):
        program_id = snapshot['programId']
        if program_id in removed_by_program: raise ValueError('duplicate bound program')
        removed, changed = convert_snapshot(snapshot, bridge)
        removed_by_program[program_id] = removed
        count += changed
    for station in document.get('stationPrograms', []):
        for name in ('arrival', 'departurePreparation'):
            _, changed = convert_definition(station[name]); count += changed
    def invocation(program):
        nonlocal count
        # Binding lookup must use this document's independent frozen snapshot.
        removed = removed_by_program.get(program['programId'], set())
        for name in removed:
            if name in program['arguments']:
                del program['arguments'][name]; count += 1
    for event in document.get('events', []):
        if 'program' in event: invocation(event['program'])
    for duty in document.get('duties', []):
        for element in duty.get('elements', []):
            if element['type'] == 'PROGRAM': invocation(element['program'])
    return count


def inspect(documents_root, library_root, bridge):
    roots = {'documents': documents_root.resolve(strict=True), 'library': library_root.resolve(strict=True)}
    if roots['documents'].is_relative_to(roots['library']) or roots['library'].is_relative_to(roots['documents']):
        raise ValueError('storage roots must be separate')
    originals = {'documents': scan(documents_root), 'library': scan(library_root)}
    outputs = {storage: {} for storage in roots}
    files = []
    for storage, records in originals.items():
        for relative, raw in records.items():
            value = copy.deepcopy(decode(raw))
            if not isinstance(value, dict): raise ValueError('record must be an object')
            if storage == 'library':
                if value.get('id') != Path(relative).stem: raise ValueError('record ID differs from filename')
                _, count = convert_snapshot(value, bridge)
                bridge('validate-record', value)
            else:
                count = convert_document(value, bridge)
                bridge('validate-document', value)
            converted = output_json(value) if count else raw
            outputs[storage][relative] = converted
            files.append(dict(storage=storage, path=relative, action='rewrite' if count else 'unchanged',
                              changedFields=count, beforeSha256=digest(raw), afterSha256=digest(converted)))
    bridge.verify()
    plan = dict(migration=MIGRATION, roots={key: str(path) for key, path in roots.items()},
                jarSha256=bridge.jar_sha256, helperSha256=bridge.bridge_sha256,
                changedFields=sum(item['changedFields'] for item in files), files=files)
    return plan, originals, outputs


def fsync_directory(path):
    descriptor = os.open(path, os.O_RDONLY)
    try: os.fsync(descriptor)
    finally: os.close(descriptor)


def atomic_replace(path, content):
    original = path.stat()
    descriptor, temporary = tempfile.mkstemp(prefix='.program-units-', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(content)
            stream.flush()
            os.fchown(stream.fileno(), original.st_uid, original.st_gid)
            # chown can clear set-id bits; set the exact original mode afterwards.
            os.fchmod(stream.fileno(), stat.S_IMODE(original.st_mode))
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        fsync_directory(path.parent)
    finally:
        Path(temporary).unlink(missing_ok=True)


def apply(documents_root, library_root, expected, backup, bridge, instance):
    roots = {'documents': documents_root, 'library': library_root}
    if backup.is_symlink() or any(path.is_symlink() for path in backup.absolute().parents):
        raise ValueError('backup must not use symlinks')
    outside(instance, backup)
    for root in roots.values():
        outside(root, backup)
        if not root.resolve().is_relative_to(instance.resolve()):
            raise ValueError('storage roots must belong to the stopped instance')
    ensure_stopped(instance)
    current, originals, outputs = inspect(documents_root, library_root, bridge)
    if current != expected: raise ValueError('source, candidate JAR or helper differs from reviewed plan')
    backup.mkdir(parents=True, exist_ok=False)
    for storage, records in originals.items():
        for relative, raw in records.items():
            source, target = roots[storage] / relative, backup / storage / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
            info = source.stat()
            os.chown(target, info.st_uid, info.st_gid)
            os.chmod(target, stat.S_IMODE(info.st_mode))
            with target.open('rb') as stream: os.fsync(stream.fileno())
            if target.read_bytes() != raw: raise ValueError('source changed during backup')
    with (backup / 'manifest.json').open('xb') as stream:
        stream.write(output_json(current)); stream.flush(); os.fsync(stream.fileno())
    for directory in [*sorted((p for p in backup.rglob('*') if p.is_dir()), reverse=True), backup]:
        fsync_directory(directory)
    fsync_directory(backup.parent)
    ensure_stopped(instance)
    if inspect(documents_root, library_root, bridge)[0] != current:
        raise ValueError('source changed before apply')
    touched = []
    try:
        for item in current['files']:
            if item['action'] == 'unchanged': continue
            storage, relative = item['storage'], item['path']
            path = roots[storage] / relative
            # Include failed atomic writes: replacement may have succeeded before fsync failed.
            touched.append((storage, relative))
            atomic_replace(path, outputs[storage][relative])
        after, _, _ = inspect(documents_root, library_root, bridge)
        if any(item['action'] != 'unchanged' for item in after['files']):
            raise ValueError('migration did not converge')
        for item in current['files']:
            if digest((roots[item['storage']] / item['path']).read_bytes()) != item['afterSha256']:
                raise ValueError('output differs from reviewed plan')
    except Exception as failure:
        rollback_errors = []
        for storage, relative in reversed(touched):
            try:
                atomic_replace(roots[storage] / relative, originals[storage][relative])
                if (roots[storage] / relative).read_bytes() != originals[storage][relative]:
                    raise ValueError('rollback hash mismatch')
            except Exception as error: rollback_errors.append(type(error).__name__)
        if rollback_errors:
            raise ValueError('apply failed; rollback incomplete, restore verified external backup') from failure
        raise ValueError('apply failed; all attempted writes rolled back, backup retained') from failure
    return current


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('documents_root', type=Path); parser.add_argument('library_root', type=Path)
    parser.add_argument('--webui-jar', type=Path, required=True); parser.add_argument('--java-dir', type=Path)
    parser.add_argument('--bridge-classes', type=Path); parser.add_argument('--plan', type=Path, required=True)
    parser.add_argument('--apply', action='store_true'); parser.add_argument('--backup', type=Path)
    parser.add_argument('--instance-root', type=Path)
    args = parser.parse_args()
    for root in (args.documents_root, args.library_root): outside(root, args.plan)
    bridge = JavaBridge(args.webui_jar, args.java_dir, args.bridge_classes)
    if args.apply:
        if not args.backup or not args.instance_root: parser.error('apply requires external backup and stopped instance root')
        plan = apply(args.documents_root, args.library_root, decode(args.plan.read_bytes()), args.backup, bridge, args.instance_root)
    else:
        if args.backup or args.instance_root: parser.error('backup/instance-root require apply')
        plan, _, _ = inspect(args.documents_root, args.library_root, bridge)
        with args.plan.open('xb') as stream:
            stream.write(output_json(plan)); stream.flush(); os.fsync(stream.fileno())
        fsync_directory(args.plan.parent)
    print(json.dumps(dict(mode='apply' if args.apply else 'dry-run', files=len(plan['files']),
                          changedFields=plan['changedFields'])))


if __name__ == '__main__':
    try: main()
    except (ValueError, OSError, KeyError, TypeError, subprocess.SubprocessError) as error:
        print('ERROR: ' + str(error), file=sys.stderr); sys.exit(1)
