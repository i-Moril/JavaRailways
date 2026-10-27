import copy
import os
from pathlib import Path
import tempfile
import unittest

from migrate_duty_timeline import convert_document, resolve_events, schedule, inspect, apply, JavaBridge
from remove_program_library_archived import output_json


def document(elements):
    return dict(formatVersion=1, id='doc', periodDays=1, runs=[], diagramLines=[], arrivalOverrides=[],
                duties=[dict(id='duty', selector=dict(type='TRAIN_NAME', name='train'), elements=elements)])


def inline(tick=50):
    return dict(id='element', type='INLINE', tick=tick, definition=dict(parameters=[], steps=[dict(id='s', type='WAIT_DURATION', durationTicks=dict(value=10))]))


class ConversionTests(unittest.TestCase):
    # Unit identity adapter; Java-backed integration below verifies real hashes.
    def convert(self, value, library=None):
        return convert_document(value, 'world', library if library is not None else {}, lambda _: 'sha256:' + '0' * 64)

    def test_initializer_only_preserves_no_snapshot_field(self):
        source = document([dict(id='a', type='FUNCTION', call=dict(id='a', type='PLACE_INITIAL', trainName='train', wayPointId='way'))])
        result = self.convert(source)
        self.assertEqual(result['duties'][0]['initialPosition'], 'way')
        self.assertEqual(result['duties'][0]['elements'], [])
        self.assertNotIn('boundPrograms', result)
        self.assertEqual(len(source['duties'][0]['elements']), 1)

    def test_initializer_must_match_first_named_selector(self):
        first = dict(id='a', type='FUNCTION', call=dict(id='a', type='PLACE_INITIAL', trainName='other', wayPointId='way'))
        with self.assertRaisesRegex(ValueError, 'match named selector'): self.convert(document([first]))
        first['call']['trainName'] = 'train'
        with self.assertRaises(ValueError): self.convert(document([inline(), first]))

    def test_inline_creates_numeric_named_library_and_snapshot(self):
        library = {'old': {'name': '1'}}
        result = self.convert(document([inline()]), library)
        call = result['duties'][0]['elements'][0]['call']
        self.assertEqual(call['tick'], 50)
        self.assertTrue(call['programId'].isdigit())
        self.assertEqual(library[call['programId']]['name'], '2')
        self.assertEqual(result['boundPrograms'][0]['definition'], inline()['definition'])
        with self.assertRaisesRegex(ValueError, 'collision'): self.convert(document([inline()]), library)

    def test_existing_deleted_library_program_is_not_recreated(self):
        value = document([dict(id='e', type='PROGRAM', call=dict(id='c', programId='deleted', tick=5, arguments={}))])
        value['boundPrograms'] = [dict(programId='deleted', revision=3, contentIdentity='hash', definition=inline()['definition'])]
        library = {}
        self.assertEqual(self.convert(value, library), value)
        self.assertEqual(library, {})

    def test_functions_become_library_steps(self):
        cases = [dict(id='f', type='REVERSE', tick=20), dict(id='f', type='WAIT', tick=20, durationTicks=10),
                 dict(id='f', type='STORE_AT_DEPOT', tick=20, depotId='d', stablingPointIds=['a'])]
        for call in cases:
            library = {}; result = self.convert(document([dict(id='e', type='FUNCTION', call=call)]), library)
            step = next(iter(library.values()))['definition']['steps'][0]
            self.assertEqual(step['type'], 'WAIT_DURATION' if call['type'] == 'WAIT' else call['type'])
            self.assertEqual(result['duties'][0]['elements'][0]['call']['tick'], 20)

    def test_untimed_inline_after_run_rejected(self):
        value = document([dict(id='r', type='RUN', runId='run'), inline(None)])
        with self.assertRaisesRegex(ValueError, 'ambiguous inherited cursor'): self.convert(value)

    def test_untimed_inline_after_wait(self):
        first = dict(id='wait', type='FUNCTION', call=dict(id='f', type='WAIT', tick=20, durationTicks=10))
        result = self.convert(document([first, inline(None)]))
        self.assertEqual(result['duties'][0]['elements'][1]['call']['tick'], 30)

    def test_event_resolution_nested_and_outside_period(self):
        value = inline()['definition']; value['steps'] = [dict(id='event', type='WAIT_EVENT', eventId='event', offsetTicks=-2)]
        doc = document([]); doc['events'] = [dict(id='event', tick=100)]
        self.assertEqual(resolve_events(value, doc)['steps'][0]['tick'], {'value': 98})
        doc['events'][0]['tick'] = 1
        with self.assertRaises(ValueError): resolve_events(value, doc)

    def test_static_wait_schedule_keeps_independent_tick(self):
        steps = [dict(id='a', type='WAIT_DURATION', durationTicks={'value': 10}), dict(id='b', type='WAIT_UNTIL', tick={'value': 15})]
        self.assertEqual(schedule(steps, {}, 10, 24000), 20)
        branch = dict(id='branch', type='BRANCH', thenSteps=steps, elseSteps=[])
        self.assertEqual(schedule([branch], {}, 10, 24000), 20)


@unittest.skipUnless(os.environ.get('JR_MIGRATION_WEBUI_JAR'), 'set JR_MIGRATION_WEBUI_JAR for candidate Java validation')
class JavaIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.docs = self.root / 'documents'; self.library = self.root / 'library'
        (self.docs / 'world').mkdir(parents=True); self.library.mkdir()
        self.bridge = JavaBridge(Path(os.environ['JR_MIGRATION_WEBUI_JAR']), Path(os.environ['JR_MIGRATION_JAVA_DIR']) if os.environ.get('JR_MIGRATION_JAVA_DIR') else None)
        self.addCleanup(self.bridge.temporary.cleanup)
        self.source = self.docs / 'world/doc.json'
        self.source.write_bytes(output_json(document([inline()])))
        self.source.chmod(0o640)

    def test_real_java_identity_validation_apply_backup_and_idempotency(self):
        plan, original, output = inspect(self.docs, self.library, self.bridge)
        self.assertFalse(list(self.library.iterdir()))
        apply(self.docs, self.library, plan, self.root / 'backup', self.bridge, self.root)
        self.assertEqual(self.source.stat().st_mode & 0o777, 0o640)
        self.assertEqual((self.root / 'backup/documents/world/doc.json').read_bytes(), original['documents']['world/doc.json'])
        next_plan, _, _ = inspect(self.docs, self.library, self.bridge)
        self.assertTrue(all(f['action'] == 'unchanged' for f in next_plan['files']))
        self.assertEqual(len(list((self.library / 'world').glob('*.json'))), 1)

    def test_precompiled_bridge_for_jre_host_is_hash_pinned(self):
        classes = self.root / 'classes'
        target = classes / 'work/art1st/javarailways/webui/DutyTimelineMigrationBridge.class'
        target.parent.mkdir(parents=True)
        target.write_bytes(self.bridge.class_file.read_bytes())
        java_dir = Path(os.environ['JR_MIGRATION_JAVA_DIR']) if os.environ.get('JR_MIGRATION_JAVA_DIR') else None
        bridge = JavaBridge(self.bridge.jar, java_dir, classes)
        plan, _, _ = inspect(self.docs, self.library, bridge)
        self.assertEqual(plan['bridgeSha256'], self.bridge.bridge_sha256)
        target.write_bytes(target.read_bytes() + b'changed')
        with self.assertRaisesRegex(ValueError, 'changed'): inspect(self.docs, self.library, bridge)

    def test_changed_source_prevents_apply_without_backup_or_writes(self):
        plan, _, _ = inspect(self.docs, self.library, self.bridge)
        self.source.write_bytes(output_json(document([inline(60)])))
        with self.assertRaisesRegex(ValueError, 'changed since'): apply(self.docs, self.library, plan, self.root / 'backup', self.bridge, self.root)
        self.assertFalse((self.root / 'backup').exists())
        self.assertFalse(list(self.library.iterdir()))


if __name__ == '__main__': unittest.main()
