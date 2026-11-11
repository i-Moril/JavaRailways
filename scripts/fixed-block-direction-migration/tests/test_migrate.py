"""Transformation and candidate IO tests; current reader validation is a separate real-JAR check."""

import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "migrate.py"
SPEC = importlib.util.spec_from_file_location("fixed_block_migration", SCRIPT)
MIGRATION = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MIGRATION)
FIXTURE = Path(__file__).parent / "fixtures" / "old-definitions.json"
WORLD = "00000000-0000-0000-0000-000000000078"
NAME = WORLD + "-definitions.json"


class FixedBlockMigrationTest(unittest.TestCase):
    def setUp(self):
        self.raw = FIXTURE.read_bytes()
        self.document = json.loads(self.raw)

    def convert(self, document):
        return MIGRATION.convert_bytes(json.dumps(document).encode(), WORLD)

    def test_fixed_nonempty_fixture_only_deletes_target_bytes_and_keeps_both_entrances(self):
        converted, changes = MIGRATION.convert_bytes(self.raw, WORLD)
        self.assertEqual(converted, self.raw.replace(b'"bidirectional": true,\n    ', b""))
        self.assertEqual(changes, [{"path": "fixedBlocks[0].bidirectional", "blockId": "test:shared", "removedValue": True}])
        expected = copy.deepcopy(self.document)
        del expected["fixedBlocks"][0]["bidirectional"]
        self.assertEqual(json.loads(converted), expected)
        self.assertEqual([signal["entryDirection"] for signal in expected["fixedBlockSignals"]], ["FORWARD", "REVERSE"])

    def test_current_is_byte_for_byte_noop_including_missing_default_arrays(self):
        current, _ = MIGRATION.convert_bytes(self.raw, WORLD)
        self.assertEqual(MIGRATION.convert_bytes(current, WORLD), (current, []))
        empty = {key: value for key, value in self.document.items() if key in MIGRATION.ROOT_REQUIRED}
        raw = json.dumps(empty, indent=4).encode() + b"\n\n"
        self.assertEqual(MIGRATION.convert_bytes(raw, WORLD), (raw, []))

    def test_first_middle_last_escaped_key_and_multiple_blocks(self):
        for position in range(4):
            with self.subTest(position=position):
                root = copy.deepcopy(self.document)
                items = [(key, value) for key, value in root["fixedBlocks"][0].items() if key != "bidirectional"]
                items.insert(position, ("bidirectional", False))
                root["fixedBlocks"] = [dict(items), copy.deepcopy(root["fixedBlocks"][0])]
                raw = json.dumps(root, indent=2).replace('"bidirectional"', '"bidir\\u0065ctional"').encode()
                converted, changes = MIGRATION.convert_bytes(raw, WORLD)
                for block in root["fixedBlocks"]:
                    del block["bidirectional"]
                self.assertEqual(json.loads(converted), root)
                self.assertEqual(len(changes), 2)
                self.assertFalse(changes[0]["removedValue"])

    def test_unicode_and_exact_number_spelling_are_preserved(self):
        raw = self.raw.replace(b'2.0, "upperAbMeters"', b'2.0000000000000000000001e+0, "upperAbMeters"')
        raw = raw.replace(b'"test:reverse"', '"test:返程"'.encode())
        converted, _ = MIGRATION.convert_bytes(raw, WORLD)
        self.assertIn(b'2.0000000000000000000001e+0', converted)
        self.assertIn('"test:返程"'.encode(), converted)
        self.assertEqual(converted, raw.replace(b'"bidirectional": true,\n    ', b""))

    def test_unknown_layout_and_non_boolean_flag_are_refused(self):
        for value in (None, 0, 1, "true", [], {}):
            with self.subTest(flag=value):
                root = copy.deepcopy(self.document)
                root["fixedBlocks"][0]["bidirectional"] = value
                with self.assertRaisesRegex(ValueError, "Boolean"):
                    self.convert(root)
        for location in ("root", "block"):
            root = copy.deepcopy(self.document)
            (root if location == "root" else root["fixedBlocks"][0])["unknown"] = 1
            with self.assertRaisesRegex(ValueError, "Unfamiliar"):
                self.convert(root)
        for field in ("blockId", "revision", "traversals"):
            root = copy.deepcopy(self.document)
            del root["fixedBlocks"][0][field]
            with self.assertRaisesRegex(ValueError, "Unfamiliar"):
                self.convert(root)

    def test_schema_world_duplicate_key_and_invalid_json_are_refused(self):
        for version in (True, 2, "1", None):
            root = copy.deepcopy(self.document)
            root["schemaVersion"] = version
            with self.assertRaisesRegex(ValueError, "schemaVersion"):
                self.convert(root)
        root = copy.deepcopy(self.document)
        root["worldId"] = "00000000-0000-0000-0000-000000000079"
        with self.assertRaisesRegex(ValueError, "worldId"):
            self.convert(root)
        for raw in (b'{"key":1,"key":2}', b'{"number":NaN}', b'{"number":Infinity}', b'{"x":'):
            with self.assertRaises(ValueError):
                MIGRATION.convert_bytes(raw, WORLD)

    def test_primary_and_backup_dry_run_hashes_and_new_candidate(self):
        with tempfile.TemporaryDirectory() as directory:
            parent = Path(directory)
            source = parent / "source"
            source.mkdir()
            (source / NAME).write_bytes(self.raw)
            current, _ = MIGRATION.convert_bytes(self.raw, WORLD)
            (source / (NAME + ".bak")).write_bytes(current)
            report, converted = MIGRATION.plan(source)
            self.assertEqual(report["removedFields"], 1)
            self.assertEqual(len(report["files"]), 2)
            self.assertEqual((source / NAME).read_bytes(), self.raw)
            self.assertEqual(sorted(path.name for path in source.iterdir()), [NAME, NAME + ".bak"])
            output = parent / "candidate"
            result = MIGRATION.write_candidate(source, output, report, converted, report)
            self.assertTrue(result["complete"])
            self.assertEqual((output / NAME).read_bytes(), current)
            self.assertEqual((output / (NAME + ".bak")).read_bytes(), current)
            self.assertEqual((source / NAME).read_bytes(), self.raw)
            self.assertFalse(list(output.glob("*.tmp")))
            with self.assertRaises(FileExistsError):
                MIGRATION.write_candidate(source, output, report, converted, report)

    def test_source_hash_guard_rejects_modification_and_changed_inventory_before_output(self):
        for added in (False, True):
            with self.subTest(added=added), tempfile.TemporaryDirectory() as directory:
                parent = Path(directory)
                source = parent / "source"
                source.mkdir()
                (source / NAME).write_bytes(self.raw)
                report, converted = MIGRATION.plan(source)
                (source / (NAME + ".bak") if added else source / NAME).write_bytes(self.raw + b"\n")
                output = parent / "candidate"
                with self.assertRaisesRegex(ValueError, "Source changed"):
                    MIGRATION.write_candidate(source, output, report, converted, report)
                self.assertFalse(output.exists())

    def test_independent_output_report_mismatch_and_symlinks_are_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            parent = Path(directory)
            source = parent / "source"
            source.mkdir()
            (source / NAME).write_bytes(self.raw)
            report, converted = MIGRATION.plan(source)
            for output in (source, source / "child", parent):
                with self.assertRaisesRegex(ValueError, "independent"):
                    MIGRATION.write_candidate(source, output, report, converted, report)
            wrong = copy.deepcopy(report)
            wrong["sourceSha256"][NAME] = "different"
            with self.assertRaisesRegex(ValueError, "Dry-run report"):
                MIGRATION.write_candidate(source, parent / "bad", report, converted, wrong)
            (source / (NAME + ".bak")).symlink_to(source / NAME)
            with self.assertRaisesRegex(ValueError, "non-regular"):
                MIGRATION.plan(source)
            (source / (NAME + ".bak")).unlink()
            linked = parent / "linked"
            linked.symlink_to(source, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, "Symlink"):
                MIGRATION.plan(linked)

    def test_backup_failure_rejects_whole_plan_without_writes(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory)
            (source / NAME).write_bytes(self.raw)
            (source / (NAME + ".bak")).write_text("corrupted", encoding="utf-8")
            before = {path.name: path.read_bytes() for path in source.iterdir()}
            with self.assertRaises(ValueError):
                MIGRATION.plan(source)
            self.assertEqual({path.name: path.read_bytes() for path in source.iterdir()}, before)

    def test_empty_source_and_changed_candidate_bytes_are_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source"
            source.mkdir()
            with self.assertRaisesRegex(ValueError, "no definition sidecars"):
                MIGRATION.plan(source)
            (source / NAME).write_bytes(self.raw)
            report, converted = MIGRATION.plan(source)
            converted[NAME] += b"\n"
            with self.assertRaisesRegex(ValueError, "Candidate bytes"):
                MIGRATION.write_candidate(source, Path(directory) / "candidate", report, converted, report)

    def test_concurrent_source_change_during_output_leaves_no_complete_report(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source"
            source.mkdir()
            (source / NAME).write_bytes(self.raw)
            report, converted = MIGRATION.plan(source)
            output = Path(directory) / "candidate"
            write = MIGRATION.atomic_write

            def concurrent_write(path, raw):
                write(path, raw)
                (source / NAME).write_bytes(self.raw + b"\n")

            with patch.object(MIGRATION, "atomic_write", side_effect=concurrent_write):
                with self.assertRaisesRegex(ValueError, "Source changed while writing"):
                    MIGRATION.write_candidate(source, output, report, converted, report)
            self.assertTrue((output / NAME).is_file())
            self.assertFalse((output / "migration-report.json").exists())
            self.assertEqual((source / NAME).read_bytes(), self.raw + b"\n")

    def test_io_failure_during_candidate_write_never_marks_it_complete_or_changes_source(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source"
            source.mkdir()
            (source / NAME).write_bytes(self.raw)
            report, converted = MIGRATION.plan(source)
            output = Path(directory) / "candidate"
            with patch.object(MIGRATION, "atomic_write", side_effect=OSError("simulated full disk")):
                with self.assertRaisesRegex(OSError, "full disk"):
                    MIGRATION.write_candidate(source, output, report, converted, report)
            self.assertFalse((output / "migration-report.json").exists())
            self.assertEqual((source / NAME).read_bytes(), self.raw)


if __name__ == "__main__":
    unittest.main()
