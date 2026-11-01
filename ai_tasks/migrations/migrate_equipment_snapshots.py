#!/usr/bin/env python3
"""Offline equipment snapshot rehash and typed Bukkit world-reference update.

Dry-run: PACKAGE_ROOT MODEL_ROOT WORLDS_ROOT --source-jar OLD --candidate-jar NEW
 --java-dir JDK25/bin --plan NEW_PLAN. Apply additionally requires --apply,
 --backup NEW_EXTERNAL_DIRECTORY and --instance-root STOPPED_INSTANCE.
Both primary and fallback runtime files are inspected. Opaque extension/user JSON
is never searched recursively. This helper is not part of the production plugin.
"""
from __future__ import annotations
import argparse
import copy
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import tempfile

from migrate_duty_timeline import ensure_stopped
from remove_program_library_archived import decode, output_json, digest, outside
from remove_program_unit_selectors import atomic_replace, fsync_directory

MIGRATION = 'equipment-snapshots'
HASH = re.compile(r'[0-9a-f]{64}')

class Bridge:
    def __init__(self, jar, java_dir=None, bridge_classes=None):
        self.jar = jar.resolve(strict=True)
        self.jar_sha256 = digest(self.jar.read_bytes())
        self.java = str(java_dir / 'java') if java_dir else 'java'
        javac = str(java_dir / 'javac') if java_dir else 'javac'
        self.temporary = tempfile.TemporaryDirectory(prefix='jr-equipment-codec-') if bridge_classes is None else None
        self.classes = Path(self.temporary.name) if self.temporary else bridge_classes.resolve(strict=True)
        self.source = Path(__file__).with_name('EquipmentSnapshotsMigrationBridge.java')
        self.source_sha256 = digest(self.source.read_bytes())
        try:
            if bridge_classes is None:
                subprocess.run([javac, '--release', '17', '-cp', str(self.jar), '-d', str(self.classes), str(self.source)],
                               check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            self.class_file = self.classes / 'EquipmentSnapshotsMigrationBridge.class'
            self.helper_sha256 = digest(self.class_file.read_bytes())
            if bridge_classes is not None:
                receipt=decode((self.classes/'receipt.json').read_bytes())
                if receipt != dict(jarSha256=self.jar_sha256, helperSourceSha256=self.source_sha256, helperSha256=self.helper_sha256):
                    raise ValueError('precompiled helper receipt mismatch')
        except Exception:
            if self.temporary:self.temporary.cleanup()
            raise

    def close(self):
        if self.temporary:self.temporary.cleanup()

    def __enter__(self):
        return self

    def __exit__(self, *failure):
        self.close()

    def verify(self):
        if (digest(self.jar.read_bytes()) != self.jar_sha256 or digest(self.class_file.read_bytes()) != self.helper_sha256
                or digest(self.source.read_bytes()) != self.source_sha256):
            raise ValueError('codec JAR or helper changed')

    def __call__(self, operation, value):
        self.verify()
        result = subprocess.run([self.java, '-cp', str(self.jar) + os.pathsep + str(self.classes),
                                 'EquipmentSnapshotsMigrationBridge', operation], input=output_json(value),
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.verify()
        if result.returncode: raise ValueError('real codec rejected ' + operation)
        return decode(result.stdout)

    def provenance(self):
        return dict(jar=str(self.jar), jarSha256=self.jar_sha256, helperSha256=self.helper_sha256,
                    helperSourceSha256=self.source_sha256)


def scan(root, snapshots=False):
    if root.is_symlink() or not root.is_dir() or any(p.is_symlink() for p in root.absolute().parents):
        raise ValueError('storage root must be an existing non-symlink directory')
    result = {}
    for path in sorted(root.iterdir()):
        if path.is_symlink() or not path.is_file() or path.stat().st_nlink != 1:
            raise ValueError('storage files must be regular unlinked files')
        if snapshots and (path.suffix != '.json' or not HASH.fullmatch(path.stem)):
            raise ValueError('invalid snapshot filename')
        result[path.name] = path.read_bytes()
    return result


def convert_core(core, mappings, bridge):
    """Only known extension envelopes at real SavedTrain/SavedCar locations."""
    changed = 0
    def extensions(values, kind):
        nonlocal changed
        if not isinstance(values, dict): raise ValueError('invalid extension map')
        for envelope in values.values():
            if not isinstance(envelope, dict): raise ValueError('invalid extension envelope')
            type_id = envelope.get('typeId')
            if type_id not in ('javarailways:car_render_state', 'javarailways:train_render_state'): continue
            expected = 'javarailways:' + kind + '_render_state'
            if (type_id != expected or set(envelope) != {'typeId','schemaVersion','payload'}
                    or type(envelope.get('schemaVersion')) is not int or envelope['schemaVersion'] != 1):
                raise ValueError('render extension is at an invalid location or schema')
            payload = envelope['payload']
            bridge(kind + '-ref', payload)
            for field, store in [('packageSnapshotHash', 'packages')] + ([('modelSnapshotHash', 'models')] if kind == 'car' else []):
                old = payload.get(field)
                if old is None: continue
                if not isinstance(old, str) or not HASH.fullmatch(old) or old not in mappings[store]:
                    raise ValueError('render extension references missing or invalid ' + store + ' snapshot')
                new = mappings[store][old]
                if new != old: payload[field] = new; changed += 1
            bridge(kind + '-ref', payload)
    if not isinstance(core, dict) or not isinstance(core.get('trains'), list): raise ValueError('invalid core runtime')
    for train in core['trains']:
        extensions(train.get('extension', {}), 'train')
        if not isinstance(train.get('cars'), list): raise ValueError('invalid saved train cars')
        for car in train['cars']: extensions(car['config'].get('extension', {}), 'car')
    return changed


class S100BehaviorReplacement:
    """Explicit offline exact-source replacement; never used by runtime loaders."""
    def __init__(self, sources, target):
        self.sources = [path.resolve(strict=True) for path in sources]
        self.target = target.resolve(strict=True)
        if not self.sources:
            raise ValueError('S100 replacement requires exact source scripts')
        paths = [*self.sources, self.target]
        if any(path.is_symlink() or not path.is_file() for path in [*sources, target]):
            raise ValueError('behavior scripts must be regular files')
        self.pinned = {str(path): digest(path.read_bytes()) for path in paths}
        self.accepted = {path.read_text(encoding='utf-8') for path in self.sources}
        self.output = self.target.read_text(encoding='utf-8')
    def verify(self):
        if any(digest(Path(name).read_bytes()) != value for name,value in self.pinned.items()):
            raise ValueError('pinned S100 behavior script changed')
    def convert(self, value):
        self.verify()
        if value.get('packageName') != 's100':
            return value
        behavior = value.get('behavior')
        if not isinstance(behavior, dict) or behavior.get('fileName') != 'behavior.js':
            raise ValueError('S100 behavior shape requires explicit review')
        if behavior.get('source') == self.output:
            return value
        if behavior.get('source') not in self.accepted:
            raise ValueError('S100 behavior differs from exact reviewed sources')
        value = copy.deepcopy(value)
        value['behavior']['source'] = self.output
        return value
    def provenance(self):
        self.verify()
        return dict(sourceScripts={str(path):self.pinned[str(path)] for path in self.sources},
                    candidateScript={str(self.target):self.pinned[str(self.target)]})


def inspect(roots, source, candidate, behavior=None):
    originals = {key: scan(root, key != 'worlds') for key, root in roots.items()}
    roots = {key: path.resolve(strict=True) for key, path in roots.items()}
    if any(a == b or a.is_relative_to(b) or b.is_relative_to(a) for i,a in enumerate(roots.values()) for b in list(roots.values())[i+1:]):
        raise ValueError('storage roots must be separate')
    outputs = {key: {} for key in roots}
    metadata = {key: {} for key in roots}
    mappings = {'packages': {}, 'models': {}}
    canonical = {'packages': {}, 'models': {}}
    for store, operation in [('packages', 'package'), ('models', 'model')]:
        for name, raw in originals[store].items():
            value = decode(raw)
            if not isinstance(value, dict) or value.get('hash') != Path(name).stem: raise ValueError('snapshot hash differs from filename')
            old = value['hash']
            try: verified = source(operation, value)
            except ValueError: verified = candidate(operation, value)
            if verified.get('hash') != old: raise ValueError('source snapshot hash does not match actual codec')
            current = candidate(operation, behavior.convert(value) if behavior is not None and store == 'packages' else value)
            new = current['hash']
            if not isinstance(new, str) or not HASH.fullmatch(new): raise ValueError('candidate returned invalid hash')
            mappings[store][old] = new
            target = new + '.json'
            content = raw if new == old else output_json(current)
            if target in canonical[store] and canonical[store][target] != current: raise ValueError('snapshot hash collision')
            canonical[store][target] = current
            if target not in outputs[store] or target == name:
                outputs[store][target] = content
                metadata[store][target] = name
    refs = 0
    for name, raw in originals['worlds'].items():
        output = raw
        if name.endswith(('-runtime.json', '-runtime.json.bak')):
            value = copy.deepcopy(decode(raw))
            if not isinstance(value, dict) or value.get('formatVersion') != 2 or not isinstance(value.get('coreSnapshotJson'), str):
                raise ValueError('expected current split runtime file')
            candidate('runtime-file', value)
            core = decode(value['coreSnapshotJson'].encode())
            changes = convert_core(core, mappings, candidate)
            refs += changes
            if changes:
                value['coreSnapshotJson'] = output_json(core).decode()
                candidate('runtime-file', value)
                output = output_json(value)
        outputs['worlds'][name] = output
        metadata['worlds'][name] = name
    files = []
    for store in roots:
        for name in sorted(originals[store].keys() | outputs[store].keys()):
            before, after = originals[store].get(name), outputs[store].get(name)
            original_name = name if before is not None else metadata[store][name]
            info = (roots[store] / original_name).stat()
            files.append(dict(storage=store, path=name, source=original_name,
                action='delete' if after is None else 'create' if before is None else 'unchanged' if before == after else 'rewrite',
                beforeSha256=digest(before) if before is not None else None,
                afterSha256=digest(after) if after is not None else None,
                uid=info.st_uid, gid=info.st_gid, mode=stat.S_IMODE(info.st_mode)))
    source.verify(); candidate.verify()
    plan = dict(migration=MIGRATION, roots={key:str(value) for key,value in roots.items()},
                sourceCodec=source.provenance(), candidateCodec=candidate.provenance(),
                referenceChanges=refs, mappings=mappings, files=files)
    if behavior is not None: plan['s100BehaviorReplacement'] = behavior.provenance()
    return plan, originals, outputs


def create_atomic(path, content, item, on_created=lambda: None):
    descriptor, temporary = tempfile.mkstemp(prefix='.equipment-', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(content); stream.flush(); os.fchown(stream.fileno(),item['uid'],item['gid'])
            os.fchmod(stream.fileno(),item['mode']); os.fsync(stream.fileno())
        # Never overwrite a concurrently created content-addressed target.
        os.link(temporary, path); on_created(); fsync_directory(path.parent)
    finally:
        Path(temporary).unlink(missing_ok=True)
        fsync_directory(path.parent)


def apply(roots, expected, backup, source, candidate, instance, behavior=None):
    outside(instance, backup)
    if backup.is_symlink() or any(p.is_symlink() for p in backup.absolute().parents): raise ValueError('backup cannot use symlinks')
    for root in roots.values():
        outside(root, backup)
        if not root.resolve(strict=True).is_relative_to(instance.resolve(strict=True)): raise ValueError('storage root must belong to stopped instance')
    ensure_stopped(instance)
    current, originals, outputs = inspect(roots, source, candidate, behavior)
    if current != expected: raise ValueError('source inventory or codec differs from reviewed plan')
    backup.mkdir(parents=True,exist_ok=False);os.chmod(backup,0o700)
    for store, records in originals.items():
        target_root=backup/store;target_root.mkdir()
        for name, raw in records.items():
            source_path,target=roots[store]/name,target_root/name
            shutil.copy2(source_path,target);info=source_path.stat()
            os.chown(target,info.st_uid,info.st_gid);os.chmod(target,stat.S_IMODE(info.st_mode))
            with target.open('rb') as stream:os.fsync(stream.fileno())
            if target.read_bytes()!=raw:raise ValueError('source changed during backup')
        fsync_directory(target_root)
    with (backup/'manifest.json').open('xb') as stream:
        os.fchmod(stream.fileno(),0o600);stream.write(output_json(current));stream.flush();os.fsync(stream.fileno())
    fsync_directory(backup);fsync_directory(backup.parent)
    ensure_stopped(instance)
    if inspect(roots,source,candidate,behavior)[0]!=current:raise ValueError('source changed before apply')
    touched=[]; owned_creations=set()
    # New/updated snapshots first, references second, obsolete snapshots last.
    ordered=sorted((item for item in current['files'] if item['action']!='unchanged'),
                   key=lambda item: (2 if item['action']=='delete' else 1 if item['storage']=='worlds' else 0,item['storage'],item['path']))
    try:
        for item in ordered:
            store,name=item['storage'],item['path'];path=roots[store]/name;touched.append(item)
            if item['action']=='delete':path.unlink();fsync_directory(path.parent)
            elif item['action']=='create':create_atomic(path,outputs[store][name],item,lambda: owned_creations.add((store,name)))
            else:atomic_replace(path,outputs[store][name])
        after,_,_=inspect(roots,source,candidate,behavior)
        if any(item['action']!='unchanged' for item in after['files']):raise ValueError('migration did not converge')
        for store in roots:
            if scan(roots[store],store!='worlds')!=outputs[store]:raise ValueError('output inventory differs from reviewed plan')
    except Exception as failure:
        errors=[]
        for item in reversed(touched):
            store,name=item['storage'],item['path'];path=roots[store]/name
            try:
                if name not in originals[store]:
                    if (store,name) in owned_creations:path.unlink(missing_ok=True);fsync_directory(path.parent)
                elif path.exists():atomic_replace(path,originals[store][name])
                else:create_atomic(path,originals[store][name],item)
            except Exception as error:errors.append(type(error).__name__)
        if errors:raise ValueError('apply failed; rollback incomplete, restore external backup') from failure
        raise ValueError('apply failed; attempted changes rolled back, external backup retained') from failure
    return current


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('packages',type=Path);parser.add_argument('models',type=Path);parser.add_argument('worlds',type=Path)
    parser.add_argument('--source-jar',type=Path,required=True);parser.add_argument('--candidate-jar',type=Path,required=True)
    parser.add_argument('--source-bridge-classes',type=Path)
    parser.add_argument('--candidate-bridge-classes',type=Path)
    parser.add_argument('--s100-source-behavior',type=Path,action='append',default=[])
    parser.add_argument('--s100-candidate-behavior',type=Path)
    parser.add_argument('--java-dir',type=Path);parser.add_argument('--plan',type=Path,required=True)
    parser.add_argument('--apply',action='store_true');parser.add_argument('--backup',type=Path);parser.add_argument('--instance-root',type=Path)
    args=parser.parse_args();roots={key:getattr(args,key) for key in ('packages','models','worlds')}
    for root in roots.values():outside(root,args.plan)
    if bool(args.s100_source_behavior) != bool(args.s100_candidate_behavior):parser.error('both source and candidate S100 behavior scripts are required')
    behavior=S100BehaviorReplacement(args.s100_source_behavior,args.s100_candidate_behavior) if args.s100_candidate_behavior else None
    with Bridge(args.source_jar,args.java_dir,args.source_bridge_classes) as source, Bridge(args.candidate_jar,args.java_dir,args.candidate_bridge_classes) as candidate:
        if args.apply:
            if not args.backup or not args.instance_root:parser.error('apply requires external backup and stopped instance')
            plan=apply(roots,decode(args.plan.read_bytes()),args.backup,source,candidate,args.instance_root,behavior)
        else:
            if args.backup or args.instance_root:parser.error('backup/instance-root require apply')
            plan,_,_=inspect(roots,source,candidate,behavior)
            with args.plan.open('xb') as stream:os.fchmod(stream.fileno(),0o600);stream.write(output_json(plan))
    print('equipment snapshot migration:',sum(item['action']!='unchanged' for item in plan['files']),'file changes;',plan['referenceChanges'],'typed references')

if __name__=='__main__':main()
