#!/usr/bin/env python3
"""One-time offline conversion of development duty blocks into timeline tasks.

Dry-run: DOCUMENTS_ROOT LIBRARY_ROOT --webui-jar candidate.jar --plan plan.json
Apply: same arguments plus --apply --backup NEW_RECOVERY --instance-root SERVER
The plan contains paths and hashes, never document bodies. No runtime reader.
"""
from __future__ import annotations
import argparse
import copy
import stat
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

from remove_program_library_archived import decode, output_json, digest, ID, FIELDS, outside, atomic_replace

MIGRATION = 'duty-timeline'
MAX = 9007199254740991


def integer(value):
    if type(value) is not int or not 0 <= value <= MAX:
        raise ValueError('expected a non-negative safe integer')
    return value


class JavaBridge:
    def __init__(self, jar, java_dir=None, bridge_classes=None):
        self.jar = jar.resolve(strict=True)
        self.jar_sha256 = digest(self.jar.read_bytes())
        self.java = str(java_dir / 'java') if java_dir else 'java'
        self.temporary = None
        if bridge_classes is None:
            self.temporary = tempfile.TemporaryDirectory(prefix='jr-duty-migration-java-')
            self.classes = Path(self.temporary.name)
            javac = str(java_dir / 'javac') if java_dir else 'javac'
            subprocess.run([javac, '--release', '17', '-cp', str(self.jar), '-d', str(self.classes),
                            str(Path(__file__).with_name('DutyTimelineMigrationBridge.java'))], check=True,
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        else:
            self.classes = bridge_classes.resolve(strict=True)
        self.class_file = self.classes / 'work/art1st/javarailways/webui/DutyTimelineMigrationBridge.class'
        self.bridge_sha256 = digest(self.class_file.read_bytes())

    def __call__(self, operation, value):
        if digest(self.jar.read_bytes()) != self.jar_sha256 or digest(self.class_file.read_bytes()) != self.bridge_sha256:
            raise ValueError('candidate JAR changed during inspection')
        result = subprocess.run([self.java, '-cp', str(self.jar) + os.pathsep + str(self.classes),
                                 'work.art1st.javarailways.webui.DutyTimelineMigrationBridge', operation],
                                input=output_json(value), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode:
            # Validator errors can contain user data; print only the operation.
            raise ValueError('candidate Java validator rejected migration ' + operation)
        if digest(self.jar.read_bytes()) != self.jar_sha256 or digest(self.class_file.read_bytes()) != self.bridge_sha256:
            raise ValueError('candidate JAR changed during validation')
        return decode(result.stdout)


def scan(root):
    if root.is_symlink() or not root.is_dir() or any(p.is_symlink() for p in root.absolute().parents):
        raise ValueError('storage root must be an existing non-symlink directory')
    records = {}
    for world in sorted(root.iterdir()):
        if world.is_symlink() or not world.is_dir() or not ID.fullmatch(world.name):
            raise ValueError('unexpected world directory')
        for path in sorted(world.iterdir()):
            if path.is_symlink() or not path.is_file() or path.suffix != '.json' or not ID.fullmatch(path.stem) or path.stat().st_nlink != 1:
                raise ValueError('unexpected or linked storage file')
            records[path.relative_to(root).as_posix()] = path.read_bytes()
    return records


def resolve_events(definition, document):
    result = copy.deepcopy(definition)
    events = {e['id']: e for e in document.get('events', [])}
    def convert(steps):
        for index, step in enumerate(steps):
            if step['type'] == 'WAIT_EVENT':
                tick = integer(events[step['eventId']]['tick'] + step['offsetTicks'])
                if tick >= document['periodDays'] * 24000:
                    raise ValueError('event wait outside period')
                steps[index] = dict(id=step['id'], type='WAIT_UNTIL', tick={'value': tick})
            else:
                for key in ('steps', 'thenSteps', 'elseSteps'):
                    if key in step: convert(step[key])
    convert(result['steps'])
    return result


def schedule(steps, args, start, period):
    # Exact static wait accounting from stationPrograms.ts. Unknown operations
    # cannot establish an inherited start for an untimed INLINE.
    def visit(values, tick, earliest):
        def value(field):
            return integer(field['value'] if 'value' in field else args[field['parameterId']])
        for step in values:
            kind = step['type']
            if kind == 'WAIT_UNTIL':
                tick = max(tick, value(step['tick'])); earliest = max(earliest, value(step['tick']))
            elif kind == 'WAIT_DURATION': earliest += value(step['durationTicks'])
            elif kind == 'BRANCH':
                yes = visit(step['thenSteps'], tick, earliest)
                no = visit(step['elseSteps'], tick, earliest)
                tick, earliest = max(yes[0], no[0]), max(yes[1], no[1])
            elif kind == 'ASYNC': earliest = max(earliest, visit(step['steps'], tick, earliest)[1])
            elif kind not in ('SET_DISPLAY', 'RESET_DISPLAY', 'REVERSE', 'END', 'AWAIT'):
                raise ValueError('cannot infer inherited cursor across ' + kind)
            if earliest >= period: raise ValueError('inherited cursor crosses period')
        return tick, earliest
    return visit(steps, start, start)[1]


def convert_document(document, world, library, identity, storage_id=None):
    result = copy.deepcopy(document)
    snapshots = result.get('boundPrograms', [])
    snapshot_map = {s['programId']: s for s in snapshots}
    used = set(library) | set(snapshot_map)
    names = {record['name'] for record in library.values()}
    period = result['periodDays'] * 24000
    for duty in result['duties']:
        cursor = 0 if duty['selector']['type'] == 'TRAIN_NAME' else None
        converted = []
        for index, element in enumerate(duty['elements']):
            kind = element['type']
            try:
                if kind == 'RUN':
                    # Movement DSL cursor does not equal terminal arrival. Do
                    # not invent a start tick for an untimed INLINE after RUN.
                    cursor = None
                    converted.append(element); continue
                if kind == 'PROGRAM':
                    call = element['call']; snap = snapshot_map[call['programId']]
                    try: cursor = schedule(snap['definition']['steps'], call['arguments'], integer(call['tick']), period)
                    except ValueError: cursor = None
                    converted.append(element); continue
                if kind == 'FUNCTION':
                    call = element['call']; action = call['type']
                    if action == 'PLACE_INITIAL':
                        if index != 0 or 'initialPosition' in duty or duty['selector'] != {'type': 'TRAIN_NAME', 'name': call['trainName']}:
                            raise ValueError('initial placement must be first, unique and match named selector')
                        duty['initialPosition'] = call['wayPointId']; continue
                    start = integer(call['tick'])
                    step = {'id': call['id'], 'type': action}
                    if action == 'WAIT': step.update(type='WAIT_DURATION', durationTicks={'value': integer(call['durationTicks'])})
                    elif action == 'STORE_AT_DEPOT': step.update(depotId={'value': call['depotId']}, stablingPointIds={'value': call['stablingPointIds']})
                    elif action != 'REVERSE': raise ValueError('unsupported function')
                    definition = {'parameters': [], 'steps': [step]}
                elif kind == 'INLINE':
                    if element.get('tick') is None and cursor is None:
                        raise ValueError('untimed inline has ambiguous inherited cursor; supply an audited explicit tick')
                    start = integer(element['tick']) if element.get('tick') is not None else integer(cursor)
                    definition = resolve_events(element['definition'], result)
                    if definition['parameters']: raise ValueError('inline parameters cannot be migrated')
                else: raise ValueError('unknown duty element')
                seed = output_json([world, storage_id if storage_id is not None else result['id'], duty['id'], element['id']])
                program_id = str(int.from_bytes(hashlib.sha256(seed).digest()[:16], 'big'))
                if program_id in used: raise ValueError('deterministic program ID collision')
                used.add(program_id)
                number = 1
                while str(number) in names: number += 1
                name = str(number); names.add(name)
                content_identity = identity(definition)
                record = dict(formatVersion=1, id=program_id, name=name, revision=1, definition=definition, contentIdentity=content_identity)
                library[program_id] = record
                snapshot = dict(programId=program_id, revision=1, definition=copy.deepcopy(definition), contentIdentity=content_identity)
                result['boundPrograms'] = snapshots
                snapshots.append(snapshot); snapshot_map[program_id] = snapshot
                converted.append(dict(id=element['id'], type='PROGRAM', call=dict(id=element['id'], programId=program_id, tick=start, arguments={})))
                # Unknown-duration actions are safe if no following untimed inline
                # needs their cursor. Explicit following times reset it.
                try: cursor = schedule(definition['steps'], {}, start, period)
                except ValueError: cursor = None
            except (ValueError, KeyError, StopIteration, TypeError) as error:
                raise ValueError(f"{world}/{result['id']}: duty {duty['id']} element {element['id']}: {error}") from None
        duty['elements'] = converted
    return result


def inspect(documents_root, library_root, bridge):
    originals = {'documents': scan(documents_root), 'library': scan(library_root)}
    library = {}
    for relative, raw in originals['library'].items():
        world, filename = relative.split('/'); record = decode(raw)
        if set(record) != FIELDS or record['id'] != Path(filename).stem: raise ValueError('invalid current library record')
        library.setdefault(world, {})[record['id']] = record
    outputs = {'documents': {}, 'library': dict(originals['library'])}
    documents = []
    for relative, raw in originals['documents'].items():
        world = relative.split('/')[0]
        document = decode(raw)
        converted = convert_document(document, world, library.setdefault(world, {}), lambda d: bridge('identity', d)['identity'], storage_id=Path(relative).stem)
        documents.append(converted)
        outputs['documents'][relative] = raw if converted == document else output_json(converted)
    for world, records in library.items():
        for record_id, record in records.items():
            relative = f'{world}/{record_id}.json'
            if relative not in outputs['library']: outputs['library'][relative] = output_json(record)
    bridge('validate', {'documents': documents, 'library': [r for records in library.values() for r in records.values()]})
    files = []
    for storage, values in outputs.items():
        for relative, raw in sorted(values.items()):
            before = originals[storage].get(relative)
            files.append(dict(storage=storage, path=relative, beforeSha256=digest(before) if before is not None else None,
                              afterSha256=digest(raw), action='create' if before is None else 'unchanged' if before == raw else 'rewrite'))
    plan = dict(migration=MIGRATION, documentsRoot=str(documents_root.resolve()), libraryRoot=str(library_root.resolve()),
                jarSha256=bridge.jar_sha256, bridgeSha256=bridge.bridge_sha256, files=files)
    return plan, originals, outputs


def ensure_stopped(instance):
    instance = str(instance.resolve(strict=True))
    for proc in Path('/proc').iterdir():
        if not proc.name.isdigit(): continue
        try:
            if (proc / 'exe').resolve(strict=True).name != 'java': continue
            command = (proc / 'cmdline').read_bytes()
            cwd = str((proc / 'cwd').resolve(strict=True))
            if instance in cwd or instance.encode() in command:
                raise ValueError('instance Java process is running: ' + proc.name)
        except (FileNotFoundError, ProcessLookupError): continue
        except PermissionError: raise ValueError('cannot verify Java process; run apply with sufficient privileges') from None


def apply(documents_root, library_root, plan, backup, bridge, instance):
    for root in (documents_root, library_root):
        outside(root, backup)
        if not root.resolve().is_relative_to(instance.resolve()):
            raise ValueError('storage roots must belong to the stopped instance')
    ensure_stopped(instance)
    current, originals, outputs = inspect(documents_root, library_root, bridge)
    if plan != current: raise ValueError('storage or candidate JAR changed since reviewed dry-run')
    backup.mkdir(parents=True, exist_ok=False)
    roots = {'documents': documents_root, 'library': library_root}
    for storage, values in originals.items():
        for relative, raw in values.items():
            source = roots[storage] / relative; target = backup / storage / relative
            target.parent.mkdir(parents=True, exist_ok=True); shutil.copy2(source, target)
            with target.open('rb') as stream: os.fsync(stream.fileno())
            if target.read_bytes() != raw: raise ValueError('source changed during backup')
    with (backup / 'manifest.json').open('xb') as stream:
        stream.write(output_json(plan)); stream.flush(); os.fsync(stream.fileno())
    ensure_stopped(instance)
    if inspect(documents_root, library_root, bridge)[0] != current: raise ValueError('source changed before apply')
    for directory in [backup, *[p for p in backup.rglob('*') if p.is_dir()]]:
        descriptor = os.open(directory, os.O_RDONLY)
        try: os.fsync(descriptor)
        finally: os.close(descriptor)
    # Install library first so a partial I/O failure never leaves migrated docs
    # referring to a missing newly-created library entry. Recovery holds all data.
    for storage in ('library', 'documents'):
        for item in current['files']:
            if item['storage'] != storage or item['action'] == 'unchanged': continue
            path = roots[storage] / item['path']; raw = outputs[storage][item['path']]
            if item['action'] == 'rewrite': atomic_replace(path, raw)
            else:
                if not path.parent.exists():
                    path.parent.mkdir()
                    root_stat = roots[storage].stat()
                    os.chown(path.parent, root_stat.st_uid, root_stat.st_gid)
                    os.chmod(path.parent, stat.S_IMODE(root_stat.st_mode))
                descriptor, temporary = tempfile.mkstemp(prefix='.timeline-', dir=path.parent)
                try:
                    with os.fdopen(descriptor, 'wb') as stream: stream.write(raw); stream.flush(); os.fsync(stream.fileno())
                    parent = path.parent.stat(); os.chown(temporary, parent.st_uid, parent.st_gid)
                    exemplar = next(iter(path.parent.glob('*.json')), None)
                    mode = stat.S_IMODE(exemplar.stat().st_mode) if exemplar else 0o600
                    os.chmod(temporary, mode)
                    os.link(temporary, path)  # no clobber if another process created it
                finally: Path(temporary).unlink(missing_ok=True)
                descriptor = os.open(path.parent, os.O_RDONLY)
                try: os.fsync(descriptor)
                finally: os.close(descriptor)
    return current


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('documents_root', type=Path); parser.add_argument('library_root', type=Path)
    parser.add_argument('--webui-jar', type=Path, required=True); parser.add_argument('--java-dir', type=Path)
    parser.add_argument('--bridge-classes', type=Path, help='precompiled helper classes for a JRE-only host')
    parser.add_argument('--plan', type=Path, required=True); parser.add_argument('--backup', type=Path)
    parser.add_argument('--instance-root', type=Path); parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    for root in (args.documents_root, args.library_root): outside(root, args.plan)
    if args.documents_root.resolve() == args.library_root.resolve() or args.documents_root.resolve().is_relative_to(args.library_root.resolve()) or args.library_root.resolve().is_relative_to(args.documents_root.resolve()): raise ValueError('storage roots must be separate')
    bridge = JavaBridge(args.webui_jar, args.java_dir, args.bridge_classes)
    if args.apply:
        if not args.backup or not args.instance_root: parser.error('apply requires backup and stopped instance root')
        plan = apply(args.documents_root, args.library_root, decode(args.plan.read_bytes()), args.backup, bridge, args.instance_root)
    else:
        if args.backup: parser.error('backup requires apply')
        plan, _, _ = inspect(args.documents_root, args.library_root, bridge)
        with args.plan.open('xb') as stream: stream.write(output_json(plan))
    print(json.dumps({'mode': 'apply' if args.apply else 'dry-run', 'files': len(plan['files']),
                      'changed': sum(f['action'] != 'unchanged' for f in plan['files'])}))


if __name__ == '__main__':
    try: main()
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print('ERROR: ' + str(error), file=sys.stderr); sys.exit(1)
