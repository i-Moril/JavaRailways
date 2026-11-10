"""Deferred regression coverage for the one-time development source converter."""
import importlib.util
from pathlib import Path
import unittest
import yaml

spec = importlib.util.spec_from_file_location('model_schema_migration', Path(__file__).parents[1] / 'model-schema-consistency' / 'migrate.py')
migration = importlib.util.module_from_spec(spec)
spec.loader.exec_module(migration)


class ModelSchemaConsistencyTest(unittest.TestCase):
    def test_recursive_inline_versions_and_animation_map_preserve_order(self):
        raw = b'''config-version: 1
name: group
type: dummy
parts:
  - config-version: 1
    name: child
    type: dummy
    animations:
      - name: z
        commands:
          - duration: 2
          - duration: 1
      - name: a
    parts:
      - import: repeated
      - import: repeated
'''
        changed = migration.convert(raw)
        data = yaml.safe_load(changed)
        child = data['parts'][0]
        self.assertEqual(1, data['config-version'])
        self.assertNotIn('config-version', child)
        self.assertEqual(['z', 'a'], list(child['animations']))
        self.assertEqual([2, 1], [x['duration'] for x in child['animations']['z']['commands']])
        self.assertEqual([{'import': 'repeated'}] * 2, child['parts'])
        self.assertEqual(changed, migration.convert(changed))

    def test_action_execution_and_composition_remain_byte_identical(self):
        for raw in [b'config-version: 1\nactions:\n  open:\n    animations:\n      - name: doors\n      - name: doors\n',
                    b'config-version: 2\ncomposition: [head, trailer, trailer, head]\n']:
            self.assertEqual(raw, migration.convert(raw))

    def test_duplicate_animation_names_and_yaml_keys_rejected(self):
        with self.assertRaises(ValueError):
            migration.convert(b'config-version: 1\nname: x\ntype: dummy\nanimations:\n  - name: x\n  - name: x\n')
        with self.assertRaises(ValueError):
            migration.convert(b'config-version: 1\nname: x\ntype: dummy\nanimations:\n  x: {}\n  x: {}\n')

    def test_wrong_inline_version_refused(self):
        with self.assertRaises(ValueError):
            migration.convert(b'body-parts:\n  - config-version: 2\n    name: x\n    type: dummy\n')


if __name__ == '__main__':
    unittest.main()
