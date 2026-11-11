"""Real-filesystem regression coverage for the offline library migration."""
import contextlib
import copy
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import remove_program_library_archived as migration


class MigrationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.root = self.base / "station-programs"
        (self.root / "world").mkdir(parents=True)
        self.record = {"formatVersion": 1, "id": "1", "name": "1", "revision": 2,
                       "definition": {"parameters": [], "steps": []},
                       "contentIdentity": "sha256:" + "a" * 64}

    def put(self, name, archived=None):
        record = copy.deepcopy(self.record)
        record["id"] = name
        if archived is not None:
            record["archived"] = archived
        path = self.root / "world" / (name + ".json")
        path.write_text(json.dumps(record))
        return path

    def test_dry_run_leaves_every_source_byte_unchanged(self):
        first, second = self.put("1", False), self.put("2", True)
        before = first.read_bytes(), second.read_bytes()
        with patch("sys.argv", ["migration", str(self.root), "--plan", str(self.base / "plan.json")]):
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(migration.main(), 0)
        self.assertEqual((first.read_bytes(), second.read_bytes()), before)
        self.assertTrue((self.base / "plan.json").exists())
        self.assertFalse((self.base / "backup").exists())

    def test_apply_removes_metadata_and_archived_entry_preserving_recovery_and_snapshots(self):
        first, second, current = self.put("1", False), self.put("2", True), self.put("3")
        original = {p.name: p.read_bytes() for p in (first, second, current)}
        document = self.base / "editor-documents" / "world" / "doc.json"
        document.parent.mkdir(parents=True)
        document.write_bytes(b'{"boundPrograms":[{"programId":"2","definition":{}}]}')
        doc_before = document.read_bytes()
        plan, _ = migration.inspect(self.root)
        backup = self.base / "backup"
        migration.apply(self.root, plan, backup)
        self.assertEqual(json.loads(first.read_bytes()), self.record)
        self.assertFalse(second.exists())
        self.assertEqual(current.read_bytes(), original["3.json"])
        self.assertEqual(document.read_bytes(), doc_before)
        for name, data in original.items():
            self.assertEqual((backup / "world" / name).read_bytes(), data)
        self.assertEqual(json.loads((backup / "manifest.json").read_bytes()), plan)

    def test_invalid_record_stops_all_before_backup_or_mutation(self):
        first = self.put("1", False)
        before = first.read_bytes()
        plan, _ = migration.inspect(self.root)
        self.put("2", "false")
        with self.assertRaises(ValueError):
            migration.apply(self.root, plan, self.base / "backup")
        self.assertEqual(first.read_bytes(), before)
        self.assertFalse((self.base / "backup").exists())

    def test_plan_conflict_rejects_changed_and_added_files(self):
        first = self.put("1", False)
        plan, _ = migration.inspect(self.root)
        first.write_bytes(first.read_bytes() + b'\n')
        with self.assertRaisesRegex(ValueError, "differs"):
            migration.apply(self.root, plan, self.base / "backup")
        self.assertFalse((self.base / "backup").exists())
        plan, _ = migration.inspect(self.root)
        self.put("2", True)
        with self.assertRaisesRegex(ValueError, "differs"):
            migration.apply(self.root, plan, self.base / "backup")

    def test_record_shape_and_identity_are_strict(self):
        for field, invalid in (("formatVersion", True), ("revision", 0), ("revision", True),
                               ("contentIdentity", "sha256:bad"), ("definition", {}),
                               ("id", "other"), ("name", "")):
            record = dict(self.record, **{field: invalid}, archived=False)
            with self.subTest(field=field, invalid=invalid), self.assertRaises(ValueError):
                migration.convert(json.dumps(record).encode(), "1")
        with self.assertRaises(ValueError):
            migration.convert(b'{"id":"1","id":"2"}', "1")
        with self.assertRaises(ValueError):
            migration.convert(json.dumps(dict(self.record, extra=1)).encode(), "1")

    def test_symlinks_and_unrelated_entries_rejected(self):
        first = self.put("1", False)
        link = self.root / "world" / "2.json"
        link.symlink_to(first)
        with self.assertRaises(ValueError):
            migration.inspect(self.root)
        link.unlink()
        (self.root / "world" / "unrelated.txt").write_text("do not modify")
        with self.assertRaises(ValueError):
            migration.inspect(self.root)

    def test_backup_inside_library_or_existing_recovery_rejected(self):
        self.put("1", False)
        plan, _ = migration.inspect(self.root)
        with self.assertRaises(ValueError):
            migration.apply(self.root, plan, self.root / "backup")
        backup = self.base / "backup"
        backup.mkdir()
        with self.assertRaises(FileExistsError):
            migration.apply(self.root, plan, backup)

    def test_changes_during_backup_rejected_before_first_mutation(self):
        first = self.put("1", False)
        plan, _ = migration.inspect(self.root)
        original_copy = migration.shutil.copy2
        def copy_and_change(source, target):
            result = original_copy(source, target)
            first.write_bytes(first.read_bytes() + b'\n')
            return result
        with patch.object(migration.shutil, "copy2", side_effect=copy_and_change):
            with self.assertRaisesRegex(ValueError, "during backup"):
                migration.apply(self.root, plan, self.base / "backup")
        self.assertIn("archived", json.loads(first.read_bytes()))


if __name__ == "__main__":
    unittest.main()
