#!/usr/bin/env python3
"""One-time offline editor-document preparation lead conversion.

Dry-run: DOCUMENTS_ROOT --webui-jar candidate.jar --plan NEW_PLAN
Apply: same plus --apply --backup NEW_EXTERNAL --instance-root STOPPED.
Existing numeric choices retain their exact meaning as literal expressions.
This does not modify generated JS, model packages or compiled world snapshots.
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

from migrate_duty_timeline import scan, ensure_stopped
from remove_program_library_archived import decode, output_json, digest, outside
from remove_program_unit_selectors import fsync_directory, atomic_replace
from unify_program_string_types import JavaBridge

MIGRATION = 'station-preparation-lead'
MAX_SAFE = 9007199254740991


def convert(document):
    count = 0
    for station in document.get('stationPrograms', []):
        if 'preparationLeadTicks' not in station:
            continue
        if 'preparationLead' in station:
            raise ValueError('document contains ambiguous preparation lead settings')
        ticks = station['preparationLeadTicks']
        if type(ticks) is not int or not 0 <= ticks <= MAX_SAFE:
            raise ValueError('source preparation lead must be a nonnegative safe integer')
        # A manually selected 20 is indistinguishable from the old default.
        # Never silently change a player's exact timing into a vehicle-dependent value.
        station['preparationLead'] = {'value': ticks}
        del station['preparationLeadTicks']
        count += 1
    return count


def inspect(root, bridge):
    originals = scan(root)
    outputs, files = {}, []
    for name, raw in originals.items():
        document = copy.deepcopy(decode(raw))
        if not isinstance(document, dict):
            raise ValueError('document must be an object')
        count = convert(document)
        bridge('validate-document', document)
        output = output_json(document) if count else raw
        outputs[name] = output
        files.append(dict(path=name, action='rewrite' if count else 'unchanged',
                          changedStations=count, beforeSha256=digest(raw), afterSha256=digest(output)))
    bridge.verify()
    plan = dict(migration=MIGRATION, root=str(root.resolve(strict=True)),
                jarSha256=bridge.jar_sha256, helperSha256=bridge.bridge_sha256,
                changedStations=sum(item['changedStations'] for item in files), files=files)
    return plan, originals, outputs


def apply(root, expected, backup, bridge, instance):
    outside(instance, backup)
    outside(root, backup)
    if backup.is_symlink() or any(p.is_symlink() for p in backup.absolute().parents):
        raise ValueError('backup must not use symlinks')
    if not root.resolve().is_relative_to(instance.resolve(strict=True)):
        raise ValueError('storage root must belong to stopped instance')
    ensure_stopped(instance)
    current, originals, outputs = inspect(root, bridge)
    if current != expected:
        raise ValueError('source, candidate JAR or helper differs from reviewed plan')
    backup.mkdir(parents=True, exist_ok=False)
    os.chmod(backup, 0o700)
    for name, raw in originals.items():
        source, target = root / name, backup / 'documents' / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        info = source.stat()
        os.chown(target, info.st_uid, info.st_gid)
        os.chmod(target, stat.S_IMODE(info.st_mode))
        with target.open('rb') as stream:
            os.fsync(stream.fileno())
        if target.read_bytes() != raw:
            raise ValueError('source changed during backup')
    with (backup / 'manifest.json').open('xb') as stream:
        os.fchmod(stream.fileno(), 0o600)
        stream.write(output_json(current))
        stream.flush()
        os.fsync(stream.fileno())
    for directory in [*sorted((p for p in backup.rglob('*') if p.is_dir()), reverse=True), backup]:
        fsync_directory(directory)
    fsync_directory(backup.parent)
    ensure_stopped(instance)
    if inspect(root, bridge)[0] != current:
        raise ValueError('source changed before apply')
    touched = []
    try:
        for item in current['files']:
            if item['action'] == 'unchanged':
                continue
            name = item['path']
            touched.append(name)
            atomic_replace(root / name, outputs[name])
        after, _, _ = inspect(root, bridge)
        if any(item['action'] != 'unchanged' for item in after['files']):
            raise ValueError('migration did not converge')
        for item in current['files']:
            if digest((root / item['path']).read_bytes()) != item['afterSha256']:
                raise ValueError('output differs from reviewed plan')
    except BaseException as failure:
        errors = []
        for name in reversed(touched):
            try:
                atomic_replace(root / name, originals[name])
                if (root / name).read_bytes() != originals[name]:
                    raise ValueError('rollback hash mismatch')
            except BaseException as error:
                errors.append(type(error).__name__)
        if errors:
            raise ValueError('apply failed; rollback incomplete, restore external recovery') from failure
        raise ValueError('apply failed; attempted writes rolled back, recovery retained') from failure
    return current


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('documents_root', type=Path)
    parser.add_argument('--webui-jar', type=Path, required=True)
    parser.add_argument('--java-dir', type=Path)
    parser.add_argument('--bridge-classes', type=Path)
    parser.add_argument('--plan', type=Path, required=True)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--backup', type=Path)
    parser.add_argument('--instance-root', type=Path)
    args = parser.parse_args()
    outside(args.documents_root, args.plan)
    bridge = JavaBridge(args.webui_jar, args.java_dir, args.bridge_classes)
    if args.apply:
        if not args.backup or not args.instance_root:
            parser.error('apply requires external backup and stopped instance root')
        plan = apply(args.documents_root, decode(args.plan.read_bytes()), args.backup, bridge, args.instance_root)
    else:
        if args.backup or args.instance_root:
            parser.error('backup/instance-root require apply')
        plan, _, _ = inspect(args.documents_root, bridge)
        with args.plan.open('xb') as stream:
            os.fchmod(stream.fileno(), 0o600)
            stream.write(output_json(plan))
            stream.flush()
            os.fsync(stream.fileno())
        fsync_directory(args.plan.parent)
    print(json.dumps(dict(mode='apply' if args.apply else 'dry-run',
                          files=len(plan['files']), changedStations=plan['changedStations'])))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, TypeError, subprocess.SubprocessError) as error:
        print('ERROR: ' + str(error), file=sys.stderr)
        sys.exit(1)
