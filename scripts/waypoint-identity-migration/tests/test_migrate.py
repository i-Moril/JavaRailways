import contextlib
import copy
import gzip
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock


TOOL = Path(__file__).resolve().parents[1] / "migrate.py"
SPEC = importlib.util.spec_from_file_location("waypoint_migrate", TOOL)
migrate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(migrate)
NAME = "00000000-0000-0000-0000-000000000001-infrastructure.json"


def snapshot(basic=None, ato=None):
    if basic is None:
        basic = ('{ "formatVersion":2,"infrastructureRevision":7,"wayPoints":['
                 '{"displayName":"old#2", "id":"point", "railId":"rail",'
                 '"arcLengthInAbMeters":1.2300e+02,"extension":{"displayName":"keep nested"}}],'
                 '"unrelated":-0.000e-02}')
    if ato is None:
        ato = ('{"formatVersion":1,"infrastructureRevision":7,"destinations":['
               '{"id":"station@one","displayName":"old@U","stationDisplayName":"Station",'
               '"depotDisplayName":"Depot","arcLengthInAbMeters":0.12345678901234567890123456789},'
               '{"id":"other","displayName":"other name"}],'
               '"lines":[{"id":"line","displayName":"Line name"}]}')
    return ('{ "formatVersion":2, "infrastructureRevision":7, "coreSnapshotJson":"{}",'
            '"basicSnapshotJson":' + json.dumps(basic) + ', "simpleAtoSnapshotJson":' + json.dumps(ato)
            + ',"extraNumber":9007199254740993123456789,"label":"\\u8eca\\ud83d\\ude82\\/" }\n').encode()


class ConversionTest(unittest.TestCase):
    def test_only_exact_point_members_are_deleted_losslessly(self):
        source = snapshot()
        result, changes = migrate.convert_bytes(source)
        expected = source.replace(b'\\"displayName\\":\\"old#2\\", ', b'')
        expected = expected.replace(b'\\"displayName\\":\\"old@U\\",', b'')
        expected = expected.replace(b',\\"displayName\\":\\"other name\\"', b'')
        self.assertEqual(expected, result)
        self.assertEqual(3, len(changes))
        self.assertEqual(["point", "station@one", "other"], [entry["id"] for entry in changes])
        root = json.loads(result)
        ato = json.loads(root["simpleAtoSnapshotJson"])
        self.assertEqual("Station", ato["destinations"][0]["stationDisplayName"])
        self.assertEqual("Depot", ato["destinations"][0]["depotDisplayName"])
        self.assertEqual("Line name", ato["lines"][0]["displayName"])
        self.assertEqual("keep nested", json.loads(root["basicSnapshotJson"])["wayPoints"][0]["extension"]["displayName"])

    def test_nested_escaped_unicode_and_slashes_keep_original_bytes(self):
        source = snapshot(basic='{"wayPoints":[{"id":"车🚂/point","displayName":"old"}],"label":"车🚂/"}')
        # Outer string uses surrogate pairs, nonstandard slash escaping and escaped key spelling.
        source = source.replace(b'point\\",\\"displayName', b'point\\",\\"display\\u004eame')
        source = source.replace(b'/', b'\\/')
        result, changes = migrate.convert_bytes(source)
        self.assertEqual(3, len(changes))
        self.assertIn(b'\\ud83d\\ude82', result)
        self.assertIn(b'\\/', result)
        self.assertIn(b'\\u8f66', result)

    def test_noop_retains_plain_and_compressed_bytes(self):
        plain, _ = migrate.convert_bytes(snapshot())
        for raw in (plain, gzip.compress(plain, mtime=123456789)):
            with self.subTest(compressed=raw.startswith(b"\x1f\x8b")):
                self.assertEqual((raw, []), migrate.convert_bytes(raw))

    def test_compressed_input_is_converted_and_remains_compressed(self):
        source = snapshot()
        expected, _ = migrate.convert_bytes(source)
        converted, changes = migrate.convert_bytes(gzip.compress(source, mtime=1234))
        self.assertEqual(3, len(changes))
        self.assertEqual(expected, gzip.decompress(converted))

    def test_first_middle_last_and_only_member(self):
        for point in ('{"displayName":"old","id":"a"}', '{"id":"a","displayName":"old","x":1}',
                      '{"id":"a","displayName":"old"}', '{"displayName":"old"}'):
            with self.subTest(point=point):
                converted, changes = migrate.convert_bytes(snapshot(basic='{"wayPoints":[' + point + ']}', ato='{}'))
                actual = json.loads(json.loads(converted)["basicSnapshotJson"])["wayPoints"][0]
                expected = json.loads(point)
                del expected["displayName"]
                self.assertEqual(expected, actual)
                self.assertEqual(1, len(changes))

    def test_malformed_or_duplicate_json_cannot_be_converted(self):
        invalid = [b'{', b'{"basicSnapshotJson": "{}", "basicSnapshotJson":"{}"}',
                   snapshot(basic='{"wayPoints":[],"wayPoints":[]}'),
                   snapshot(basic='{"wayPoints":[{"id":"a","id":"b"}]}'),
                   snapshot(basic='{"wayPoints":[true]}'), snapshot(ato='{"destinations":{}}'),
                   snapshot(basic='{"wayPoints":[],"value":NaN}'),
                   snapshot().replace(b'"coreSnapshotJson":"{}"', b'"coreSnapshotJson":"{\\"x\\":1,\\"x\\":2}"'),
                   b'\x1f\x8bnot-valid-gzip']
        for raw in invalid:
            with self.subTest(raw=raw[:80]):
                with self.assertRaises((ValueError, OSError, EOFError)):
                    migrate.convert_bytes(raw)


class CandidateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "worlds"
        self.source.mkdir()
        self.raw = snapshot()
        (self.source / NAME).write_bytes(self.raw)
        (self.source / (NAME + ".bak")).write_bytes(gzip.compress(self.raw, mtime=11))
        (self.source / "world-runtime.json").write_bytes(b"leave alone")

    def report(self):
        report, converted = migrate.plan(self.source)
        report.update(dryRun=True, currentCodec={"result": "CURRENT_INFRASTRUCTURE_OK files=2"})
        return report, converted

    def test_primary_backup_plan_and_independent_output_leave_source_unchanged(self):
        before = {p.name: p.read_bytes() for p in self.source.iterdir()}
        report, converted = self.report()
        self.assertEqual(6, report["removedFields"])
        self.assertEqual({NAME, NAME + ".bak"}, set(converted))
        output = self.root / "candidate"
        written = migrate.write_candidate(self.source, output, report, converted, copy.deepcopy(report))
        self.assertTrue(written["complete"])
        self.assertFalse(written["dryRun"])
        self.assertEqual(converted[NAME], (output / NAME).read_bytes())
        self.assertEqual(before, {p.name: p.read_bytes() for p in self.source.iterdir()})
        self.assertFalse((output / "world-runtime.json").exists())
        with self.assertRaises(FileExistsError):
            migrate.write_candidate(self.source, output, report, converted, report)

    def test_mismatched_review_and_mutated_source_or_candidate_cannot_write(self):
        report, converted = self.report()
        for key in ("migration", "source", "sourceSha256", "files", "currentCodec", "dryRun"):
            with self.subTest(key=key):
                expected = copy.deepcopy(report)
                expected[key] = None
                with self.assertRaises(ValueError):
                    migrate.write_candidate(self.source, self.root / "candidate", report, converted, expected)
        bad = {**converted, NAME: b"changed"}
        with self.assertRaises(ValueError):
            migrate.write_candidate(self.source, self.root / "candidate", report, bad, report)
        (self.source / NAME).write_bytes(self.raw + b" ")
        with self.assertRaises(ValueError):
            migrate.write_candidate(self.source, self.root / "candidate", report, converted, report)
        self.assertFalse((self.root / "candidate").exists())

    def test_output_cannot_be_nested_existing_or_source(self):
        report, converted = self.report()
        for output in (self.source, self.source / "candidate", self.root):
            with self.subTest(output=output):
                with self.assertRaises(ValueError):
                    migrate.write_candidate(self.source, output, report, converted, report)

    def test_symlink_and_empty_inputs_are_not_accepted(self):
        empty = self.root / "empty"
        empty.mkdir()
        with self.assertRaises(ValueError):
            migrate.plan(empty)
        (self.source / (NAME + ".bak")).unlink()
        (self.source / (NAME + ".bak")).symlink_to(self.source / NAME)
        with self.assertRaises(ValueError):
            migrate.plan(self.source)

    def test_cli_dry_run_requires_current_codec_and_writes_no_candidate(self):
        before = {p.name: p.read_bytes() for p in self.source.iterdir()}
        command = [str(TOOL), "--source", str(self.source), "--classpath", "current.jar"]
        with mock.patch("sys.argv", command), mock.patch.object(migrate, "validate_current", return_value={"result": "test-only"}) as validate:
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                migrate.main()
            self.assertTrue(json.loads(output.getvalue())["dryRun"])
            validate.assert_called_once()
        with mock.patch("sys.argv", command), mock.patch.object(migrate, "validate_current", side_effect=ValueError("codec failed")):
            with self.assertRaisesRegex(ValueError, "codec failed"):
                migrate.main()
        self.assertEqual(before, {p.name: p.read_bytes() for p in self.source.iterdir()})
        self.assertEqual(["worlds"], [p.name for p in self.root.iterdir()])

    def test_validator_uses_source_helper_and_audits_current_jar(self):
        jar = self.root / "current.jar"
        jar.write_bytes(b"unit-test placeholder; not a current codec acceptance test")
        _, converted = self.report()
        def run(command, **kwargs):
            self.assertEqual("java25", command[0])
            self.assertEqual(str(jar), command[2])
            self.assertEqual("ValidateInfrastructure.java", Path(command[3]).name)
            self.assertEqual(converted[NAME], (Path(command[4]) / NAME).read_bytes())
            return mock.Mock(returncode=0, stdout="CURRENT_INFRASTRUCTURE_OK files=2\n")
        with mock.patch.object(migrate.subprocess, "run", side_effect=run):
            result = migrate.validate_current(converted, str(jar), "java25")
        self.assertEqual(migrate.digest(jar.read_bytes()), result["classpathSha256"][str(jar)])
        with mock.patch.object(migrate.subprocess, "run", return_value=mock.Mock(returncode=1, stderr="invalid schema")):
            with self.assertRaisesRegex(ValueError, "Current codec rejected"):
                migrate.validate_current(converted, str(jar), "java25")


if __name__ == "__main__":
    unittest.main()
