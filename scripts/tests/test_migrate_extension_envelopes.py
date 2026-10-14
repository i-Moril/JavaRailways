"""Fixed offline fixtures; these are not runtime compatibility tests."""

import copy
import gzip
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "migrate_extension_envelopes.py"
SPEC = importlib.util.spec_from_file_location("migrate_extension_envelopes", SCRIPT)
MIGRATION = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MIGRATION)
FIXTURE = Path(__file__).parent / "fixtures" / "legacy_extension_runtime.json"


class ExtensionMigrationTest(unittest.TestCase):
    def setUp(self):
        self.original = FIXTURE.read_bytes()
        self.legacy = json.loads(self.original)

    def convert(self, root):
        return MIGRATION.convert_bytes(json.dumps(root).encode(), "fixture.json")

    def test_fixed_nonempty_fixture_preserves_all_other_data_and_current_is_noop(self):
        output, changes = MIGRATION.convert_bytes(self.original, "fixture.json")
        self.assertEqual(len(changes), 2)
        converted = json.loads(output)
        train = converted["trains"][0]
        self.assertEqual(train["extension"]["BUKKIT"], {
            "typeId": "javarailways:train_render_state", "schemaVersion": 1,
            "payload": {"version": 1, "packageName": "example", "packageSnapshotHash": "train-package-hash"},
        })
        car_entry = train["cars"][0]["config"]["extension"]["BUKKIT"]
        self.assertEqual(car_entry["typeId"], "javarailways:car_render_state")
        self.assertEqual(car_entry["payload"]["heldScenes"], {"door": "open"})
        for entry in (train["extension"], train["cars"][0]["config"]["extension"]):
            envelope = entry["BUKKIT"]
            old_type = next(key for key, (type_id, _) in MIGRATION.LEGACY_TYPES.items()
                            if type_id == envelope["typeId"])
            entry["BUKKIT"] = {"type": old_type, **envelope["payload"]}
        self.assertEqual(converted, self.legacy)
        self.assertEqual(MIGRATION.convert_bytes(output, "current.json"), (output, []))

    def test_outer_and_gzip_transports_preserve_unrelated_data(self):
        outer = {"formatVersion": 1, "coreSnapshotJson": self.original.decode(),
                 "basicSnapshotJson": "  {\"unrelated\":true}  ", "note": {"type": "do-not-traverse"}}
        compressed = gzip.compress(json.dumps(outer).encode())
        result, changes = MIGRATION.convert_bytes(compressed, "runtime.json.gz")
        actual = json.loads(gzip.decompress(result))
        self.assertEqual(len(changes), 2)
        self.assertEqual(actual["basicSnapshotJson"], outer["basicSnapshotJson"])
        self.assertEqual(actual["note"], outer["note"])
        self.assertEqual(actual["formatVersion"], 1)
        self.assertEqual(MIGRATION.convert_bytes(result, "current.gz"), (result, []))

    def test_fixed_current_envelopes_and_empty_runtime_preserve_exact_bytes(self):
        entries = json.loads(FIXTURE.with_name("current_extension_envelopes.json").read_bytes())
        self.legacy["trains"][0]["extension"]["BUKKIT"] = entries["train"]
        self.legacy["trains"][0]["cars"][0]["config"]["extension"]["BUKKIT"] = entries["car"]
        current = json.dumps(self.legacy, indent=4).encode()
        self.assertEqual(MIGRATION.convert_bytes(current, "fixed-current"), (current, []))
        empty = b'{"formatVersion":1,"infrastructureRevision":9,"physicsEpoch":10,"trains":[]}\n'
        self.assertEqual(MIGRATION.convert_bytes(empty, "empty"), (empty, []))

    def test_unknown_future_ambiguous_and_wrong_owner_are_rejected_with_path(self):
        current = json.loads(MIGRATION.convert_bytes(self.original, "old")[0])
        cases = [
            ({"type": "unknown.Class"}, "unknown legacy"),
            ({"typeId": "unknown:id", "schemaVersion": 1, "payload": {}}, "unknown extension"),
            ({"typeId": "javarailways:train_render_state", "schemaVersion": 2, "payload": {}}, "schema version"),
            ({"typeId": "javarailways:train_render_state", "schemaVersion": True, "payload": {}}, "schema version"),
            ({"type": next(iter(MIGRATION.LEGACY_TYPES)), "carModelName": "car"}, "belongs to car"),
            ({"type": list(MIGRATION.LEGACY_TYPES)[1], "typeId": "ambiguous"}, "unknown payload"),
            ({"typeId": "javarailways:train_render_state", "schemaVersion": 1, "payload": {"version": 2}}, "version 1"),
            ({"typeId": "javarailways:train_render_state", "schemaVersion": 1, "payload": {"extra": 1}}, "unknown payload"),
        ]
        for entry, message in cases:
            with self.subTest(entry=entry):
                candidate = copy.deepcopy(current)
                candidate["trains"][0]["extension"]["BUKKIT"] = entry
                with self.assertRaisesRegex(ValueError, r"fixture.json.trains\[0\].extension.*" + message):
                    self.convert(candidate)
        self.legacy["trains"][0]["cars"][0]["bogieA"]["extension"] = {
            "BUKKIT": self.legacy["trains"][0]["extension"]["BUKKIT"]}
        with self.assertRaisesRegex(ValueError, r"bogieA.extension.*not bogie"):
            self.convert(self.legacy)

    def test_malformed_json_and_non_extension_fields_are_not_guessed(self):
        with self.assertRaisesRegex(ValueError, "duplicate JSON key"):
            MIGRATION.convert_bytes(b'{"formatVersion":1,"formatVersion":1}', "duplicate")
        with self.assertRaisesRegex(ValueError, "invalid JSON number"):
            MIGRATION.convert_bytes(b'{"x":NaN}', "nan")
        for version in (None, "1", True, 3):
            with self.subTest(outer_version=version):
                outer = {"coreSnapshotJson": self.original.decode()}
                if version is not None:
                    outer["formatVersion"] = version
                with self.assertRaisesRegex(ValueError, "formatVersion: expected supported outer runtime version"):
                    self.convert(outer)
        self.legacy["trains"][0]["cars"][0]["config"]["extension"] = None
        with self.assertRaisesRegex(ValueError, "config.extension: expected object"):
            self.convert(self.legacy)

    def test_dry_run_distinct_output_no_overwrite_and_path_confinement(self):
        with tempfile.TemporaryDirectory() as directory:
            parent = Path(directory)
            source = parent / "worlds"
            source.mkdir()
            runtime = source / "world-runtime.json"
            runtime.write_bytes(self.original)
            (source / "secrets.yml").write_text("must-not-copy")
            report = MIGRATION.migrate(source)
            self.assertTrue(report["dryRun"])
            self.assertEqual(runtime.read_bytes(), self.original)
            self.assertEqual(len(list(source.iterdir())), 2)
            for destination in (source, runtime, source / "inside"):
                with self.assertRaises(ValueError):
                    MIGRATION.migrate(source, destination)
            output = parent / "converted"
            report = MIGRATION.migrate(source, output)
            self.assertEqual(report["conversions"], 2)
            self.assertEqual({p.name for p in output.iterdir()}, {runtime.name, "migration-report.json"})
            self.assertEqual(runtime.read_bytes(), self.original)
            with self.assertRaises(ValueError):
                MIGRATION.migrate(source, output)
            (source / "linked-runtime.json").symlink_to(runtime)
            with self.assertRaisesRegex(ValueError, "non-symlink"):
                MIGRATION.migrate(source, parent / "not-created")
            self.assertFalse((parent / "not-created").exists())


if __name__ == "__main__":
    unittest.main()
