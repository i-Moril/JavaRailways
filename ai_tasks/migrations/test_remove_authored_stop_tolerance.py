"""Filesystem and document-boundary tests for the one-off tolerance removal."""
import copy
import json
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch

import remove_authored_stop_tolerance as migration


class StopToleranceMigrationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        (self.base / "world").mkdir()
        self.path = self.base / "world" / "infrastructure.json"
        self.backup = self.base / "recovery"
        self.snapshot = {"formatVersion": 1, "infrastructureRevision": 17,
                         "destinations": [{"id": "station", "positionToleranceMeters": 1.0,
                                           "extension": {"positionToleranceMeters": 9}}],
                         "lines": [], "lineRevision": 8, "other": "preserved"}

    def write(self, value):
        original = migration.encode(value)
        self.path.write_bytes(original)
        return original

    def test_default_dry_run_is_read_only_and_reports_old_values_and_hashes(self):
        before = self.write(self.snapshot)
        report = migration.migrate(self.path)
        self.assertEqual(self.path.read_bytes(), before)
        self.assertEqual(report["removed"], [{"index": 0, "id": "station", "oldValue": 1.0}])
        self.assertEqual(report["beforeSha256"], migration.digest(before))
        self.assertFalse(self.backup.exists())

    def test_apply_preserves_recovery_mode_and_unrelated_values_then_is_idempotent(self):
        original = self.write(self.snapshot)
        self.path.chmod(0o640)
        report = migration.migrate(self.path, True, self.backup)
        expected = copy.deepcopy(self.snapshot)
        del expected["destinations"][0]["positionToleranceMeters"]
        self.assertEqual(json.loads(self.path.read_bytes()), expected)
        self.assertEqual((self.backup / "source.json").read_bytes(), original)
        self.assertEqual(json.loads((self.backup / "manifest.json").read_bytes()), report)
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o640)
        current = self.path.read_bytes()
        self.assertEqual(migration.migrate(self.path, True, self.backup)["removedCount"], 0)
        self.assertEqual(self.path.read_bytes(), current)

    def test_outer_preserves_other_embedded_strings_versions_and_revisions(self):
        payload = '{ "formatVersion": 2, "infrastructureRevision": 17, "other": "x" }'
        outer = {"formatVersion": 2, "infrastructureRevision": 17,
                 "coreSnapshotJson": payload, "basicSnapshotJson": payload,
                 "simpleAtoSnapshotJson": json.dumps(self.snapshot), "unknown": 42}
        self.write(outer)
        migration.migrate(self.path, True, self.backup)
        actual = json.loads(self.path.read_bytes())
        for key in outer.keys() - {"simpleAtoSnapshotJson"}:
            self.assertEqual(actual[key], outer[key])
        self.assertEqual(json.loads(actual["simpleAtoSnapshotJson"])["infrastructureRevision"], 17)

    def test_invalid_input_fails_before_mutation_or_backup(self):
        for field, value in (("formatVersion", 2), ("formatVersion", True),
                             ("destinations", {}), ("lineRevision", -1),
                             ("physicsEpoch", 0), ("infrastructureRevision", True)):
            invalid = dict(self.snapshot, **{field: value})
            before = self.write(invalid)
            with self.subTest(field=field), self.assertRaises(ValueError):
                migration.migrate(self.path, True, self.backup)
            self.assertEqual(self.path.read_bytes(), before)
            self.assertFalse(self.backup.exists())
        for value in ("0.3", None, True, -0.1):
            invalid = copy.deepcopy(self.snapshot)
            invalid["destinations"][0]["positionToleranceMeters"] = value
            with self.subTest(value=value), self.assertRaises(ValueError):
                migration.convert(migration.encode(invalid))
        for data in (b'{"a":1,"a":2}', b'{"a":NaN}', b'{"a":1e999}', b'{'):
            with self.subTest(data=data), self.assertRaises(ValueError):
                migration.convert(data)

    def test_embedded_revision_mismatch_and_runtime_rejected(self):
        for key in ("coreSnapshotJson", "basicSnapshotJson", "simpleAtoSnapshotJson"):
            outer = {"formatVersion": 2, "infrastructureRevision": 17,
                     "coreSnapshotJson": '{"formatVersion":2,"infrastructureRevision":17}',
                     "basicSnapshotJson": '{"formatVersion":2,"infrastructureRevision":17}',
                     "simpleAtoSnapshotJson": json.dumps(self.snapshot)}
            inner = json.loads(outer[key])
            inner["infrastructureRevision"] = 18
            outer[key] = json.dumps(inner)
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, "revision mismatch"):
                migration.convert(migration.encode(outer))
        runtime = dict(outer, physicsEpoch=1)
        with self.assertRaises(ValueError):
            migration.convert(migration.encode(runtime))

    def test_backup_collision_missing_backup_and_inside_backup_never_mutate(self):
        before = self.write(self.snapshot)
        self.backup.mkdir()
        for backup in (self.backup, None, self.path.parent / "backup"):
            with self.subTest(backup=backup), self.assertRaises((ValueError, FileExistsError)):
                migration.migrate(self.path, True, backup)
            self.assertEqual(self.path.read_bytes(), before)

    def test_source_symlink_and_changes_during_backup_rejected(self):
        original = self.write(self.snapshot)
        link = self.path.parent / "link.json"
        link.symlink_to(self.path)
        with self.assertRaises(OSError):
            migration.migrate(link)
        create = migration.durable_create
        def create_and_change(path, data):
            create(path, data)
            if path.name == "manifest.json":
                self.path.write_bytes(original + b'\n')
        with patch.object(migration, "durable_create", side_effect=create_and_change):
            with self.assertRaisesRegex(ValueError, "source changed"):
                migration.migrate(self.path, True, self.backup)
        self.assertEqual(self.path.read_bytes(), original + b'\n')
        self.assertEqual((self.backup / "source.json").read_bytes(), original)


if __name__ == "__main__":
    unittest.main()
