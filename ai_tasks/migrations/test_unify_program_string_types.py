import copy
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import unify_program_string_types as migration
from remove_program_library_archived import decode, digest, output_json


def definition(kind='DESTINATION'):
    return dict(parameters=[dict(id='place', type=kind)], steps=[
        dict(id='move', type='MOVE', destinationId=dict(parameterId='place'), viaIds=dict(value=[])),
        dict(id='store', type='STORE_AT_DEPOT', depotId=dict(parameterId='place'), stablingPointIds=dict(value=[])),
        dict(id='display', type='SET_DISPLAY', unitSlots=dict(value=[1]), values=dict(value={
            '10': 1, '2': 2.0, 'type': 'DESTINATION', 'nested': {'parameters': [{'type': 'STATION'}]}}))])


class UnitBridge:
    """Deterministic unit stub only; real-JAR tests below own canonical validation."""
    jar_sha256 = 'candidate'
    bridge_sha256 = 'helper'
    def verify(self): pass
    def __call__(self, operation, value):
        if operation in ('rawIdentity', 'identity'):
            return dict(identity='sha256:' + digest(output_json(value)))
        return dict(valid=True)


def record(bridge, value=None):
    value = definition() if value is None else value
    return dict(formatVersion=1, id='1', name='1', revision=7, estimatedDurationTicks=25,
                definition=value, contentIdentity=bridge('rawIdentity', value)['identity'])


def document(bridge):
    frozen = definition('STATION')
    return dict(formatVersion=1, id='doc', periodDays=1, runs=[], duties=[], diagramLines=[], arrivalOverrides=[],
                boundPrograms=[dict(programId='deleted', revision=3, definition=frozen,
                                    contentIdentity=bridge('rawIdentity', frozen)['identity'])])


class MigrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.base = Path(self.temp.name)
        self.instance = self.base / 'instance'; self.instance.mkdir()
        self.docs = self.instance / 'documents'; self.docs.mkdir()
        self.library = self.instance / 'library'; self.library.mkdir()
        self.bridge = UnitBridge()
        for root, name, value in [(self.docs, 'doc', document(self.bridge)), (self.library, '1', record(self.bridge))]:
            (root / 'world').mkdir()
            (root / 'world' / (name + '.json')).write_bytes(output_json(value))
    def tearDown(self): self.temp.cleanup()
    def inspect(self): return migration.inspect(self.docs, self.library, self.bridge)
    def apply(self, plan):
        return migration.apply(self.docs, self.library, plan, self.base / 'backup', self.bridge, self.instance)
    def snapshot(self): return {str(path.relative_to(self.instance)): path.read_bytes() for path in self.instance.rglob('*.json')}

    def test_dry_run_has_no_source_writes_and_pins_both_roots(self):
        before = self.snapshot(); plan, _, outputs = self.inspect()
        self.assertEqual(before, self.snapshot())
        self.assertEqual(plan['changedParameters'], 2)
        self.assertEqual(plan['jarSha256'], 'candidate')
        self.assertEqual(plan['helperSha256'], 'helper')
        self.assertEqual(decode(outputs['documents']['world/doc.json'])['boundPrograms'][0]['programId'], 'deleted')

    def test_transform_only_definition_parameter_types(self):
        source = record(self.bridge); original = copy.deepcopy(source)
        self.assertEqual(migration.convert_snapshot(source, self.bridge), 1)
        self.assertEqual(source['definition']['parameters'][0]['type'], 'STRING')
        self.assertEqual(source['definition']['steps'], original['definition']['steps'])
        self.assertEqual(source['revision'], 7)
        self.assertEqual(source['estimatedDurationTicks'], 25)
        self.assertNotEqual(source['contentIdentity'], original['contentIdentity'])
        self.assertEqual(migration.convert_snapshot(source, self.bridge), 0)

    def test_bad_source_identity_refused_without_mutation(self):
        source = record(self.bridge); source['contentIdentity'] = 'invalid'
        with self.assertRaisesRegex(ValueError, 'source program content identity'):
            migration.convert_snapshot(source, self.bridge)
        self.assertEqual(source['definition']['parameters'][0]['type'], 'DESTINATION')

    def test_source_change_refused_before_backup(self):
        plan, _, _ = self.inspect()
        path = self.library / 'world/1.json'; path.write_bytes(path.read_bytes() + b' ')
        before = self.snapshot()
        with self.assertRaisesRegex(ValueError, 'reviewed plan'): self.apply(plan)
        self.assertEqual(before, self.snapshot()); self.assertFalse((self.base / 'backup').exists())

    def test_candidate_and_helper_changes_refused(self):
        plan, _, _ = self.inspect()
        for field in ('jar_sha256', 'bridge_sha256'):
            previous = getattr(self.bridge, field); setattr(self.bridge, field, 'changed')
            with self.assertRaisesRegex(ValueError, 'reviewed plan'): self.apply(plan)
            setattr(self.bridge, field, previous)
        self.assertFalse((self.base / 'backup').exists())

    def test_apply_preserves_metadata_backup_and_is_idempotent(self):
        path = self.library / 'world/1.json'; path.chmod(0o640); original_stat = path.stat()
        plan, before, _ = self.inspect(); self.apply(plan)
        self.assertEqual(path.stat().st_mode, original_stat.st_mode)
        self.assertEqual((path.stat().st_uid, path.stat().st_gid), (original_stat.st_uid, original_stat.st_gid))
        for storage, records in before.items():
            for relative, raw in records.items(): self.assertEqual((self.base / 'backup' / storage / relative).read_bytes(), raw)
        next_plan, _, _ = self.inspect(); self.assertEqual(next_plan['changedParameters'], 0)
        self.assertTrue(all(item['action'] == 'unchanged' for item in next_plan['files']))
        with self.assertRaisesRegex(ValueError, 'reviewed plan'): self.apply(plan)
        with self.assertRaises(FileExistsError): self.apply(next_plan)

    def test_midwrite_failure_rolls_back_every_attempted_path(self):
        plan, _, _ = self.inspect(); before = self.snapshot()
        real = migration.atomic_replace; calls = 0
        def fail_after_replace(path, content):
            nonlocal calls
            calls += 1; real(path, content)
            if calls == 2: raise OSError('injected post-replace fsync failure')
        with patch.object(migration, 'atomic_replace', fail_after_replace):
            with self.assertRaisesRegex(ValueError, 'all attempted writes rolled back'): self.apply(plan)
        self.assertEqual(before, self.snapshot()); self.assertTrue((self.base / 'backup/manifest.json').exists())

    def test_backup_inside_instance_refused(self):
        plan, _, _ = self.inspect()
        with self.assertRaisesRegex(ValueError, 'outside'):
            migration.apply(self.docs, self.library, plan, self.instance / 'backup', self.bridge, self.instance)


@unittest.skipUnless(os.getenv('JR_PROGRAM_STRING_TEST_JAR'), 'set JR_PROGRAM_STRING_TEST_JAR to the candidate WebUI JAR')
class RealJavaTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        java_dir = Path(os.environ['JR_PROGRAM_STRING_JAVA_DIR']) if os.getenv('JR_PROGRAM_STRING_JAVA_DIR') else None
        cls.bridge = migration.JavaBridge(Path(os.environ['JR_PROGRAM_STRING_TEST_JAR']), java_dir)

    def test_jackson_numeric_property_key_and_double_canonical_identity(self):
        integer = definition(); decimal = copy.deepcopy(integer)
        integer['steps'][2]['values']['value'] = {'10': 1, '2': 2, 'fraction': 0.1}
        decimal['steps'][2]['values']['value'] = {'fraction': 0.1, '2': 2.0, '10': 1.0}
        self.assertEqual(self.bridge('rawIdentity', integer), self.bridge('rawIdentity', decimal))
        value = record(self.bridge, integer)
        self.assertEqual(migration.convert_snapshot(value, self.bridge), 1)
        self.assertEqual(value['contentIdentity'], self.bridge('identity', value['definition'])['identity'])
        self.assertTrue(self.bridge('validate-record', value)['valid'])
        value['estimatedDurationTicks'] = -1
        with self.assertRaises(ValueError): self.bridge('validate-record', value)

    def test_real_documents_independent_frozen_programs_and_roundtrip(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); instance = root / 'instance'; instance.mkdir()
            docs = instance / 'documents'; docs.mkdir(); library = instance / 'library'; library.mkdir()
            for folder in (docs, library): (folder / 'world').mkdir()
            (docs / 'world/doc.json').write_bytes(output_json(document(self.bridge)))
            (library / 'world/1.json').write_bytes(output_json(record(self.bridge)))
            plan, originals, _ = migration.inspect(docs, library, self.bridge)
            migration.apply(docs, library, plan, root / 'recovery', self.bridge, instance)
            self.assertEqual(migration.inspect(docs, library, self.bridge)[0]['changedParameters'], 0)
            bound = decode((docs / 'world/doc.json').read_bytes())['boundPrograms'][0]
            self.assertEqual(bound['revision'], 3); self.assertEqual(bound['programId'], 'deleted')
            self.assertEqual(bound['definition']['parameters'][0]['type'], 'STRING')
            self.assertEqual((root / 'recovery/documents/world/doc.json').read_bytes(), originals['documents']['world/doc.json'])


if __name__ == '__main__': unittest.main()
