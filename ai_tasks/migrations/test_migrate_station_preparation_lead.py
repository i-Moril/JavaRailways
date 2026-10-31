import copy
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import migrate_station_preparation_lead as migration
from remove_program_library_archived import decode, output_json


class UnitBridge:
    """Only unit tests use this stub; RealJavaTests uses the built candidate codec."""
    jar_sha256 = 'candidate'
    bridge_sha256 = 'helper'
    def verify(self): pass
    def __call__(self, operation, value): return {'valid': True}


def document():
    return dict(formatVersion=1, id='doc', periodDays=1, diagramLines=[], runs=[], duties=[], arrivalOverrides=[],
                stationPrograms=[dict(stationId='a', preparationLeadTicks=20,
                    arrival=dict(parameters=[], steps=[dict(id='end', type='END')]),
                    departurePreparation=dict(parameters=[], steps=[dict(id='end', type='END')]))])


class MigrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.base = Path(self.temp.name)
        self.instance = self.base / 'instance'; self.instance.mkdir()
        self.root = self.instance / 'documents'; (self.root / 'world').mkdir(parents=True)
        self.path = self.root / 'world/doc-5.json'; self.path.write_bytes(output_json(document())); self.path.chmod(0o640)
        self.bridge = UnitBridge(); self.backup = self.base / 'recovery'
    def tearDown(self): self.temp.cleanup()
    def inspect(self): return migration.inspect(self.root, self.bridge)
    def apply(self, plan): return migration.apply(self.root, plan, self.backup, self.bridge, self.instance)

    def test_preserves_manual_twenty_and_opaque_json(self):
        doc = document(); opaque = {'preparationLeadTicks': 100, 'preparationLead': {'value': 5}}
        doc['stationPrograms'][0]['arrival']['steps'].insert(0, dict(id='display', type='SET_DISPLAY', values={'value': opaque}))
        self.assertEqual(1, migration.convert(doc))
        self.assertEqual({'value': 20}, doc['stationPrograms'][0]['preparationLead'])
        self.assertNotIn('preparationLeadTicks', doc['stationPrograms'][0])
        self.assertEqual(opaque, doc['stationPrograms'][0]['arrival']['steps'][0]['values']['value'])
        self.assertEqual(0, migration.convert(doc))

    def test_current_auto_expression_is_unchanged(self):
        doc = document(); station = doc['stationPrograms'][0]
        station.pop('preparationLeadTicks'); station['preparationLead'] = {'doorCloseTicks': True}
        before = copy.deepcopy(doc); self.assertEqual(0, migration.convert(doc)); self.assertEqual(before, doc)

    def test_invalid_or_ambiguous_source(self):
        for value in (True, -1, 1.5, '20', 9007199254740992):
            doc = document(); doc['stationPrograms'][0]['preparationLeadTicks'] = value
            with self.assertRaises(ValueError): migration.convert(doc)
        doc = document(); doc['stationPrograms'][0]['preparationLead'] = {'value': 0}
        with self.assertRaises(ValueError): migration.convert(doc)

    def test_dry_run_does_not_write_and_preserves_external_key(self):
        before = self.path.read_bytes(); plan, _, outputs = self.inspect()
        self.assertEqual(before, self.path.read_bytes()); self.assertEqual(1, plan['changedStations'])
        self.assertEqual('doc', decode(outputs['world/doc-5.json'])['id'])

    def test_source_change_is_refused_before_backup(self):
        plan, _, _ = self.inspect(); self.path.write_bytes(self.path.read_bytes() + b' ')
        before = self.path.read_bytes()
        with self.assertRaisesRegex(ValueError, 'reviewed plan'): self.apply(plan)
        self.assertEqual(before, self.path.read_bytes()); self.assertFalse(self.backup.exists())

    def test_jar_helper_change_is_refused(self):
        plan, _, _ = self.inspect()
        for field in ('jar_sha256', 'bridge_sha256'):
            old = getattr(self.bridge, field); setattr(self.bridge, field, 'changed')
            with self.assertRaisesRegex(ValueError, 'reviewed plan'): self.apply(plan)
            setattr(self.bridge, field, old)
        self.assertFalse(self.backup.exists())

    def test_apply_backup_permissions_and_idempotency(self):
        before = self.path.read_bytes(); plan, _, _ = self.inspect(); self.apply(plan)
        self.assertEqual(before, (self.backup / 'documents/world/doc-5.json').read_bytes())
        self.assertEqual(0o640, self.path.stat().st_mode & 0o777)
        self.assertEqual({'value': 20}, decode(self.path.read_bytes())['stationPrograms'][0]['preparationLead'])
        self.assertEqual(0, self.inspect()[0]['changedStations'])

    def test_stopped_instance_required(self):
        plan, _, _ = self.inspect()
        with patch.object(migration, 'ensure_stopped', side_effect=ValueError('running')):
            with self.assertRaisesRegex(ValueError, 'running'): self.apply(plan)
        self.assertFalse(self.backup.exists())

    def test_write_failure_rolls_back_even_after_replace(self):
        before = self.path.read_bytes(); plan, _, _ = self.inspect(); original = migration.atomic_replace; calls = 0
        def fail_once(path, data):
            nonlocal calls
            calls += 1; original(path, data)
            if calls == 1: raise OSError('post-replace fsync failed')
        with patch.object(migration, 'atomic_replace', side_effect=fail_once):
            with self.assertRaisesRegex(ValueError, 'rolled back'): self.apply(plan)
        self.assertEqual(before, self.path.read_bytes()); self.assertTrue(self.backup.exists())


@unittest.skipUnless(os.getenv('JR_PREPARATION_TEST_JAR'), 'set JR_PREPARATION_TEST_JAR to candidate WebUI JAR')
class RealJavaTests(unittest.TestCase):
    def test_real_candidate_editor_document_and_apply(self):
        bridge = migration.JavaBridge(Path(os.environ['JR_PREPARATION_TEST_JAR']))
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory); instance = base / 'instance'; root = instance / 'documents'; (root / 'world').mkdir(parents=True)
            path = root / 'world/doc-5.json'; path.write_bytes(output_json(document()))
            plan, _, _ = migration.inspect(root, bridge)
            migration.apply(root, plan, base / 'recovery', bridge, instance)
            self.assertEqual(0, migration.inspect(root, bridge)[0]['changedStations'])
            self.assertEqual({'value': 20}, decode(path.read_bytes())['stationPrograms'][0]['preparationLead'])


if __name__ == '__main__': unittest.main()
