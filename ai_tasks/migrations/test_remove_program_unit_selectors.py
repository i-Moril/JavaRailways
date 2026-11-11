import copy
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import remove_program_unit_selectors as migration
from remove_program_library_archived import decode, digest, output_json


class UnitBridge:
    """Unit stub only; integration tests below run candidate Java validators."""
    jar_sha256 = 'candidate'
    bridge_sha256 = 'helper'
    def verify(self): pass
    def __call__(self, operation, value):
        if operation in ('rawIdentity', 'identity'):
            return dict(identity='sha256:' + digest(output_json(value)))
        return dict(valid=True)


def definition():
    return dict(parameters=[dict(id='units', type='UNIT_SLOTS'), dict(id='title', type='STRING')], steps=[
        dict(id='display', type='SET_DISPLAY', unitSlots=dict(parameterId='units'), values=dict(value={
            'type': 'UNIT_COUNT', 'nested': {'unitSlots': [1], 'parameters': [{'type': 'UNIT_SLOTS'}]},
            'expression': {'parameterId': 'units'}})),
        dict(id='reset', type='RESET_DISPLAY', unitSlots=dict(value=[1])),
        dict(id='info', type='SET_DISPLAY', unitSlots=dict(value=[2]), values=dict(trainInfo={'name': 'NAME', 'units': 'UNIT_COUNT'}))])


def snapshot(bridge, value=None):
    value = definition() if value is None else value
    return dict(programId='p', revision=3, definition=value, estimatedDurationTicks=90,
                contentIdentity=bridge('rawIdentity', value)['identity'])


def record(bridge):
    value = snapshot(bridge)
    value.pop('programId')
    return dict(value, formatVersion=1, id='p', name='P')


def invocation():
    return dict(programId='p', arguments={'units': [1], 'title': 'hello'})


def document(bridge):
    return dict(formatVersion=1, id='doc', periodDays=1, diagramLines=[], arrivalOverrides=[], runs=[],
                boundPrograms=[snapshot(bridge)],
                events=[dict(id='event', tick=10, program=invocation())],
                duties=[dict(id='duty', selector={'type': 'TRAIN_NAME', 'name': 'train'}, elements=[
                    dict(id='task', type='PROGRAM', tick=20, program=invocation()),
                    dict(id='ref', type='EVENT', eventId='event')])],
                stationPrograms=[dict(stationId='station', preparationLead={'value': 20},
                    arrival=dict(parameters=[], steps=[dict(id='a', type='SET_DISPLAY', unitSlots={'value': [1]},
                        values={'trainInfo': {'count': 'UNIT_COUNT'}}), dict(id='end', type='END')]),
                    departurePreparation=dict(parameters=[], steps=[dict(id='end', type='END')]))])


class MigrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.base = Path(self.temp.name)
        self.instance = self.base / 'instance'; self.instance.mkdir()
        self.docs = self.instance / 'documents'; self.docs.mkdir()
        self.library = self.instance / 'library'; self.library.mkdir()
        self.bridge = UnitBridge()
        for root, name, value in [(self.docs, 'doc-5', document(self.bridge)), (self.library, 'p', record(self.bridge))]:
            (root / 'world').mkdir(); (root / 'world' / (name + '.json')).write_bytes(output_json(value))
    def tearDown(self): self.temp.cleanup()
    def inspect(self): return migration.inspect(self.docs, self.library, self.bridge)
    def apply(self, plan):
        return migration.apply(self.docs, self.library, plan, self.base / 'backup', self.bridge, self.instance)
    def sources(self): return {str(p.relative_to(self.instance)): p.read_bytes() for p in self.instance.rglob('*.json')}

    def test_exact_fields_only_and_identity_idempotence(self):
        original = record(self.bridge); value = copy.deepcopy(original)
        removed, changed = migration.convert_snapshot(value, self.bridge)
        self.assertEqual(removed, {'units'}); self.assertEqual(changed, 5)
        self.assertEqual(value['definition']['parameters'], [{'id': 'title', 'type': 'STRING'}])
        self.assertEqual(value['definition']['steps'][0]['values'], original['definition']['steps'][0]['values'])
        self.assertEqual(value['definition']['steps'][2]['values'], {'trainInfo': {'name': 'NAME'}})
        self.assertTrue(all('unitSlots' not in step for step in value['definition']['steps']))
        self.assertEqual(value['revision'], 3); self.assertEqual(value['estimatedDurationTicks'], 90)
        self.assertNotEqual(value['contentIdentity'], original['contentIdentity'])
        self.assertEqual(migration.convert_snapshot(value, self.bridge), (set(), 0))

    def test_sole_unit_information_becomes_literal_empty_object(self):
        value = dict(parameters=[], steps=[dict(id='info', type='EXTENSION', typeId='test:action', version=1,
                      parameters={'trainInfo': {'count': 'UNIT_COUNT'}})])
        self.assertEqual(migration.convert_definition(value), (set(), 1))
        self.assertEqual(value['steps'][0]['parameters'], {'value': {}})

    def test_removed_parameter_reference_requires_manual_resolution(self):
        value = definition()
        value['steps'].append(dict(id='move', type='MOVE', destinationId={'parameterId': 'units'}, viaIds={'value': []}))
        with self.assertRaisesRegex(ValueError, 'manual resolution required: units'):
            migration.convert_definition(value)

    def test_nested_branch_and_async_conversion(self):
        inner = definition()
        value = dict(parameters=inner['parameters'], steps=[dict(id='branch', type='BRANCH', parameters={'value': {}},
            thenSteps=[dict(id='async', type='ASYNC', steps=inner['steps'])], elseSteps=[])])
        self.assertEqual(migration.convert_definition(value), ({'units'}, 5))
        self.assertNotIn('unitSlots', value['steps'][0]['thenSteps'][0]['steps'][0])

    def test_document_uses_frozen_definition_and_preserves_domain_selectors(self):
        value = document(self.bridge)
        # Domain slots and arbitrary JSON with matching names are not program selectors.
        value['operationalTrips'] = [{'segments': [{'unitSlots': [1]}]}]
        value['formationEvents'] = [{'parts': [{'unitSlots': [2]}]}]
        frozen = value['boundPrograms'][0]
        frozen['definition']['parameters'].append({'id': 'otherUnits', 'type': 'UNIT_SLOTS'})
        frozen['contentIdentity'] = self.bridge('rawIdentity', frozen['definition'])['identity']
        value['events'][0]['program']['arguments']['otherUnits'] = [2]
        migration.convert_document(value, self.bridge)
        self.assertEqual(value['events'][0]['program']['arguments'], {'title': 'hello'})
        self.assertEqual(value['duties'][0]['elements'][0]['program']['arguments'], {'title': 'hello'})
        self.assertEqual(value['duties'][0]['elements'][1], {'id': 'ref', 'type': 'EVENT', 'eventId': 'event'})
        self.assertEqual(value['operationalTrips'], [{'segments': [{'unitSlots': [1]}]}])
        self.assertEqual(value['formationEvents'], [{'parts': [{'unitSlots': [2]}]}])
        self.assertEqual(value['stationPrograms'][0]['arrival']['steps'][0]['values'], {'value': {}})

    def test_dry_run_no_source_writes_external_storage_key_and_plan_hashes(self):
        before = self.sources(); plan, _, outputs = self.inspect()
        self.assertEqual(self.sources(), before)
        self.assertGreater(plan['changedFields'], 0)
        self.assertEqual(plan['migration'], 'remove-program-unit-selectors')
        self.assertEqual(plan['jarSha256'], 'candidate'); self.assertEqual(plan['helperSha256'], 'helper')
        self.assertEqual(decode(outputs['documents']['world/doc-5.json'])['id'], 'doc')

    def test_corrupt_identity_and_remaining_reference_never_touch_sources(self):
        path = self.library / 'world/p.json'; value = decode(path.read_bytes())
        value['contentIdentity'] = 'wrong'; path.write_bytes(output_json(value)); before = self.sources()
        with self.assertRaisesRegex(ValueError, 'source program content identity mismatch'): self.inspect()
        self.assertEqual(self.sources(), before)
        value = record(self.bridge); value['definition']['steps'].append(dict(id='wait', type='WAIT_DURATION', durationTicks={'parameterId': 'units'}))
        value['contentIdentity'] = self.bridge('rawIdentity', value['definition'])['identity']; path.write_bytes(output_json(value))
        before = self.sources()
        with self.assertRaisesRegex(ValueError, 'manual resolution required'): self.inspect()
        self.assertEqual(self.sources(), before)

    def test_source_or_jar_change_refused_before_backup(self):
        plan, _, _ = self.inspect(); self.bridge.jar_sha256 = 'changed'
        with self.assertRaisesRegex(ValueError, 'reviewed plan'): self.apply(plan)
        self.bridge.jar_sha256 = 'candidate'
        path = self.library / 'world/p.json'; path.write_bytes(path.read_bytes() + b' ')
        with self.assertRaisesRegex(ValueError, 'reviewed plan'): self.apply(plan)
        self.assertFalse((self.base / 'backup').exists())

    def test_apply_external_backup_metadata_preservation_and_convergence(self):
        path = self.library / 'world/p.json'; path.chmod(0o640); metadata = path.stat()
        plan, originals, _ = self.inspect(); self.apply(plan)
        self.assertEqual(path.stat().st_mode, metadata.st_mode)
        self.assertEqual((path.stat().st_uid, path.stat().st_gid), (metadata.st_uid, metadata.st_gid))
        for storage, records in originals.items():
            for relative, raw in records.items(): self.assertEqual((self.base / 'backup' / storage / relative).read_bytes(), raw)
        self.assertTrue((self.docs / 'world/doc-5.json').exists()); self.assertFalse((self.docs / 'world/doc.json').exists())
        self.assertEqual(self.inspect()[0]['changedFields'], 0)
        with self.assertRaisesRegex(ValueError, 'reviewed plan'): self.apply(plan)

    def test_postreplace_failure_rolls_back_all_attempts(self):
        plan, _, _ = self.inspect(); before = self.sources(); real = migration.atomic_replace; calls = 0
        def injected(path, data):
            nonlocal calls
            calls += 1; real(path, data)
            if calls == 2: raise OSError('post-replace failure')
        with patch.object(migration, 'atomic_replace', injected):
            with self.assertRaisesRegex(ValueError, 'all attempted writes rolled back'): self.apply(plan)
        self.assertEqual(self.sources(), before); self.assertTrue((self.base / 'backup/manifest.json').exists())

    def test_backup_inside_instance_refused(self):
        plan, _, _ = self.inspect()
        with self.assertRaisesRegex(ValueError, 'outside'):
            migration.apply(self.docs, self.library, plan, self.instance / 'backup', self.bridge, self.instance)


@unittest.skipUnless(os.getenv('JR_PROGRAM_UNIT_TEST_JAR'), 'set JR_PROGRAM_UNIT_TEST_JAR to candidate WebUI JAR')
class RealJavaTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls): cls.bridge = migration.JavaBridge(Path(os.environ['JR_PROGRAM_UNIT_TEST_JAR']))

    def test_real_candidate_record_and_document_converge(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory); instance = base / 'instance'; instance.mkdir()
            docs = instance / 'documents'; docs.mkdir(); library = instance / 'library'; library.mkdir()
            for folder in (docs, library): (folder / 'world').mkdir()
            (docs / 'world/doc-5.json').write_bytes(output_json(document(self.bridge)))
            (library / 'world/p.json').write_bytes(output_json(record(self.bridge)))
            plan, originals, _ = migration.inspect(docs, library, self.bridge)
            migration.apply(docs, library, plan, base / 'backup', self.bridge, instance)
            self.assertEqual(migration.inspect(docs, library, self.bridge)[0]['changedFields'], 0)
            migrated = decode((docs / 'world/doc-5.json').read_bytes())
            self.assertEqual(migrated['boundPrograms'][0]['revision'], 3)
            self.assertEqual(migrated['boundPrograms'][0]['estimatedDurationTicks'], 90)
            self.assertEqual(migrated['events'][0]['program']['arguments'], {'title': 'hello'})
            self.assertEqual((base / 'backup/documents/world/doc-5.json').read_bytes(), originals['documents']['world/doc-5.json'])


if __name__ == '__main__': unittest.main()
