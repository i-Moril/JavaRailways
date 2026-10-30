"""Filesystem and pure conversion regression tests for shared timed operations."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import unify_timed_operations as migration


class Bridge:
    jar_sha256 = 'candidate'
    bridge_sha256 = 'bridge'

    def __init__(self):
        self.validated = []

    def __call__(self, operation, value):
        assert operation == 'validate'
        self.validated.append(copy.deepcopy(value))
        # These tests isolate file transactions; root runs actual candidate Java
        # validation as a separate deployment gate, not this deliberately small seam.
        return {}


class TimedOperationMigrationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.instance = self.base / 'instance'
        self.root = self.instance / 'editor-documents'
        (self.root / 'world').mkdir(parents=True)
        self.document = dict(formatVersion=1, id='doc', periodDays=1, runs=[], diagramLines=[], arrivalOverrides=[],
            events=[dict(id='marker', stationId='A', tick=5, label='keep')],
            boundPrograms=[dict(programId='p', revision=1, contentIdentity='unchanged', definition={})],
            duties=[dict(id='d', selector=dict(type='TRAIN_NAME', name='T'), elements=[
                dict(id='element', type='PROGRAM', call=dict(id='old-call', programId='p', tick=100, arguments={'x': [1, 2]}),
                     duration=dict(type='MANUAL', ticks=20)),
                dict(id='r', type='RUN', runId='1')])])
        self.path = self.root / 'world' / 'doc.json'
        self.original = json.dumps(self.document).encode()
        self.path.write_bytes(self.original)
        self.bridge = Bridge()
        self.stopped = patch.object(migration, 'ensure_stopped')
        self.stopped.start()
        self.addCleanup(self.stopped.stop)

    def test_pure_conversion_preserves_owner_identity_parameters_snapshots_and_markers(self):
        converted = migration.convert(self.document)
        self.assertEqual(self.document['duties'][0]['elements'][0]['call']['id'], 'old-call')
        element = converted['duties'][0]['elements'][0]
        self.assertEqual(element, dict(id='element', type='PROGRAM', tick=100,
            program=dict(programId='p', arguments={'x': [1, 2]}), duration=dict(type='MANUAL', ticks=20)))
        self.assertEqual(converted['events'], self.document['events'])
        self.assertEqual(converted['boundPrograms'], self.document['boundPrograms'])
        self.assertEqual(migration.convert(converted), converted)
        self.assertEqual(converted['formatVersion'], 1)

    def test_dry_run_only_reads_and_apply_backs_up_all_bytes_then_verifies_current_model(self):
        current = self.root / 'world' / 'current.json'
        current.write_bytes(json.dumps(dict(self.document, duties=[])).encode())
        plan, _, outputs = migration.inspect(self.root, self.bridge)
        self.assertEqual(self.path.read_bytes(), self.original)
        self.assertEqual([f['action'] for f in plan['files']], ['unchanged', 'rewrite'])
        self.assertNotIn('duties', json.dumps(plan))
        self.assertEqual(len(self.bridge.validated), 1)
        backup = self.base / 'backup'
        migration.apply(self.root, plan, backup, self.bridge, self.instance)
        self.assertEqual((backup / 'world' / 'doc.json').read_bytes(), self.original)
        self.assertEqual(self.path.read_bytes(), outputs['world/doc.json'])
        self.assertEqual((backup / 'world' / 'current.json').read_bytes(), current.read_bytes())
        self.assertEqual(json.loads((backup / 'manifest.json').read_bytes()), plan)
        self.assertTrue(all(f['action'] == 'unchanged' for f in migration.inspect(self.root, self.bridge)[0]['files']))

    def test_current_document_bytes_not_reformatted(self):
        self.path.write_bytes(b' {"formatVersion":1, "duties":[]} \n')
        plan, _, outputs = migration.inspect(self.root, self.bridge)
        self.assertEqual(plan['files'][0]['action'], 'unchanged')
        self.assertEqual(outputs['world/doc.json'], self.path.read_bytes())

    def test_changed_added_or_removed_sources_invalidate_plan_before_backup(self):
        for change in ('edit', 'add', 'remove'):
            self.path.write_bytes(self.original)
            extra = self.root / 'world' / 'extra.json'
            extra.unlink(missing_ok=True)
            plan, _, _ = migration.inspect(self.root, self.bridge)
            if change == 'edit': self.path.write_bytes(self.original + b'\n')
            elif change == 'add': extra.write_bytes(self.original)
            else: self.path.unlink()
            with self.subTest(change=change), self.assertRaisesRegex(ValueError, 'changed'):
                migration.apply(self.root, plan, self.base / 'backup', self.bridge, self.instance)
            self.assertFalse((self.base / 'backup').exists())

    def test_candidate_validator_failure_prevents_any_backup_or_write(self):
        plan, _, _ = migration.inspect(self.root, self.bridge)
        def reject(*_): raise ValueError('candidate rejected')
        with self.assertRaisesRegex(ValueError, 'candidate rejected'):
            migration.apply(self.root, plan, self.base / 'backup', reject, self.instance)
        self.assertEqual(self.path.read_bytes(), self.original)
        self.assertFalse((self.base / 'backup').exists())

    def test_change_during_backup_is_rejected_without_conversion(self):
        plan, _, _ = migration.inspect(self.root, self.bridge)
        copy2 = migration.shutil.copy2
        def copy_and_edit(source, target):
            result = copy2(source, target)
            self.path.write_bytes(self.original + b'\n')
            return result
        with patch.object(migration.shutil, 'copy2', side_effect=copy_and_edit):
            with self.assertRaisesRegex(ValueError, 'changed before apply'):
                migration.apply(self.root, plan, self.base / 'backup', self.bridge, self.instance)
        self.assertIn('call', json.loads(self.path.read_bytes())['duties'][0]['elements'][0])

    def test_recovery_inside_source_existing_recovery_and_unstopped_instance_rejected(self):
        plan, _, _ = migration.inspect(self.root, self.bridge)
        with self.assertRaises(ValueError):
            migration.apply(self.root, plan, self.root / 'backup', self.bridge, self.instance)
        backup = self.base / 'backup'
        backup.mkdir()
        with self.assertRaises(FileExistsError):
            migration.apply(self.root, plan, backup, self.bridge, self.instance)
        with patch.object(migration, 'ensure_stopped', side_effect=ValueError('running')):
            with self.assertRaisesRegex(ValueError, 'running'):
                migration.apply(self.root, plan, self.base / 'other-backup', self.bridge, self.instance)

    def test_invalid_and_mixed_shapes_are_not_silently_dropped(self):
        for mutation in ('extra-element', 'extra-call', 'mixed', 'version'):
            value = copy.deepcopy(self.document)
            element = value['duties'][0]['elements'][0]
            if mutation == 'extra-element': element['extra'] = 5
            elif mutation == 'extra-call': element['call']['extra'] = 5
            elif mutation == 'mixed': element['program'] = dict(programId='p', arguments={})
            else: value['formatVersion'] = True
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                migration.convert(value)

    def test_linked_or_unexpected_storage_entries_rejected(self):
        extra = self.root / 'world' / 'linked.json'
        extra.symlink_to(self.path)
        with self.assertRaises(ValueError): migration.inspect(self.root, self.bridge)
        extra.unlink()
        (self.root / 'world' / 'unrelated.txt').write_text('keep')
        with self.assertRaises(ValueError): migration.inspect(self.root, self.bridge)


if __name__ == '__main__':
    unittest.main()
