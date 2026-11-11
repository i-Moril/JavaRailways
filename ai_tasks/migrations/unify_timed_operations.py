#!/usr/bin/env python3
"""Offline conversion of development duty PROGRAM calls to shared timed operations.

Dry-run: DOCUMENTS_ROOT --webui-jar candidate.jar --plan /recovery/plan.json
Apply: same plus --apply --instance-root SERVER --backup NEW_RECOVERY_DIRECTORY
Stop the instance before apply. Keeps formatVersion 1, element identity, snapshots
and all existing graph events. Candidate Java validation is mandatory. No runtime
compatibility reader or data export is added.
"""
from __future__ import annotations

import argparse
import copy
import os
from pathlib import Path
import shutil
import subprocess
import sys

from migrate_duty_timeline import JavaBridge, scan, ensure_stopped
from remove_program_library_archived import decode, output_json, digest, outside, atomic_replace

MIGRATION = 'unify-timed-operations'


def convert(document):
    if not isinstance(document, dict) or type(document.get('formatVersion')) is not int or document['formatVersion'] != 1:
        raise ValueError('expected editor document formatVersion 1')
    result = copy.deepcopy(document)
    if not isinstance(result.get('duties'), list):
        raise ValueError('expected duties array')
    for duty in result['duties']:
        if not isinstance(duty, dict) or not isinstance(duty.get('elements'), list):
            raise ValueError('invalid duty elements')
        for index, element in enumerate(duty['elements']):
            if not isinstance(element, dict):
                raise ValueError('invalid duty element')
            if element.get('type') != 'PROGRAM' or 'call' not in element:
                continue
            if set(element) not in ({'id', 'type', 'call'}, {'id', 'type', 'call', 'duration'}):
                raise ValueError('unexpected old program element fields')
            call = element['call']
            if not isinstance(call, dict) or set(call) != {'id', 'programId', 'tick', 'arguments'}:
                raise ValueError('unexpected old program call fields')
            if not isinstance(call['id'], str) or not call['id'].strip():
                raise ValueError('invalid old program call identity')
            converted = dict(id=element['id'], type='PROGRAM', tick=call['tick'],
                             program=dict(programId=call['programId'], arguments=call['arguments']))
            if 'duration' in element:
                converted['duration'] = element['duration']
            duty['elements'][index] = converted
    return result


def inspect(root, bridge):
    originals = scan(root)
    outputs, documents = {}, []
    for relative, raw in originals.items():
        original = decode(raw)
        converted = convert(original)
        documents.append(converted)
        outputs[relative] = raw if original == converted else output_json(converted)
    bridge('validate', {'documents': documents, 'library': []})
    files = [dict(path=relative, beforeSha256=digest(raw), afterSha256=digest(outputs[relative]),
                  action='unchanged' if raw == outputs[relative] else 'rewrite')
             for relative, raw in originals.items()]
    plan = dict(migration=MIGRATION, root=str(root.resolve()), jarSha256=bridge.jar_sha256,
                bridgeSha256=bridge.bridge_sha256, files=files)
    return plan, originals, outputs


def sync_directory(path):
    descriptor = os.open(path, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def apply(root, expected, backup, bridge, instance):
    outside(root, backup)
    if not root.resolve().is_relative_to(instance.resolve(strict=True)):
        raise ValueError('document root must belong to the stopped instance')
    ensure_stopped(instance)
    current, originals, outputs = inspect(root, bridge)
    if current != expected:
        raise ValueError('documents or candidate JAR changed since reviewed dry-run')
    backup.mkdir(parents=True, exist_ok=False)
    for relative, raw in originals.items():
        target = backup / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(root / relative, target)
        with target.open('rb') as stream:
            os.fsync(stream.fileno())
        if target.read_bytes() != raw:
            raise ValueError('source changed during backup; no migration applied')
    with (backup / 'manifest.json').open('xb') as stream:
        stream.write(output_json(current))
        stream.flush()
        os.fsync(stream.fileno())
    for directory in [backup, *[p for p in backup.rglob('*') if p.is_dir()]]:
        sync_directory(directory)
    sync_directory(backup.parent)
    ensure_stopped(instance)
    if inspect(root, bridge)[0] != current:
        raise ValueError('source changed before apply; no migration applied')
    for item in current['files']:
        if item['action'] == 'rewrite':
            atomic_replace(root / item['path'], outputs[item['path']])
    # Verify complete current tree and the exact planned hashes after replacement.
    after, _, _ = inspect(root, bridge)
    if [(f['path'], f['beforeSha256']) for f in after['files']] != [(f['path'], f['afterSha256']) for f in current['files']]:
        raise ValueError('post-apply verification failed; restore the recovery directory')
    return current


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('--webui-jar', type=Path, required=True)
    parser.add_argument('--java-dir', type=Path)
    parser.add_argument('--bridge-classes', type=Path)
    parser.add_argument('--plan', type=Path, required=True)
    parser.add_argument('--backup', type=Path)
    parser.add_argument('--instance-root', type=Path)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    outside(args.root, args.plan)
    bridge = JavaBridge(args.webui_jar, args.java_dir, args.bridge_classes)
    if args.apply:
        if not args.backup or not args.instance_root:
            parser.error('apply requires --backup and --instance-root; stop the instance first')
        plan = apply(args.root, decode(args.plan.read_bytes()), args.backup, bridge, args.instance_root)
    else:
        if args.backup or args.instance_root:
            parser.error('backup and instance-root require --apply')
        plan, _, _ = inspect(args.root, bridge)
        with args.plan.open('xb') as stream:
            stream.write(output_json(plan))
            stream.flush()
            os.fsync(stream.fileno())
    print('mode:', 'APPLY' if args.apply else 'DRY-RUN')
    print('files:', len(plan['files']))
    print('rewrite:', sum(f['action'] == 'rewrite' for f in plan['files']))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print('ERROR: ' + str(error), file=sys.stderr)
        sys.exit(1)
