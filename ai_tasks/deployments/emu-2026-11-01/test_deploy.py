import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import MagicMock, patch

SCRIPT = Path(__file__).with_name('deploy.py')


def module():
    spec = importlib.util.spec_from_file_location('emu_deploy_fixture', SCRIPT)
    obj = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(obj)
    return obj


class DeploymentTests(unittest.TestCase):
    def fixture(self, m, base):
        root, stage, backups = (base / name for name in ('instance', 'stage', 'external-backups'))
        stage.mkdir()
        backups.mkdir()
        for directory in m.DIRS:
            folder = root / 'plugins' / directory
            folder.mkdir(parents=True)
            (folder / 'world.dat').write_bytes((directory + '-original-save').encode())
        old, new = {}, {}
        for index, (rel, name) in enumerate(m.ALLOWED.items()):
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(('old-' + rel).encode())
            path.chmod(0o640 if index % 2 else 0o644)
            candidate = stage / name
            candidate.parent.mkdir(parents=True, exist_ok=True)
            candidate.write_bytes(('new-' + rel).encode())
            old[rel], new[rel] = m.sha(path), m.sha(candidate)
        for car in ('head', 'head_tail', 'motor', 'motor_tail', 'passenger', 'trailer'):
            path = root / f'plugins/JavaRailways/trains/s100/cars/s100/{car}.yml'
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(('custom-live-control-' + car).encode())
        signals = root / 'plugins/SimpleSignals.jar'
        signals.write_bytes(b'unmodified-signals')
        (root / 'plugins/ThirdParty.jar').write_bytes(b'other-plugin')
        (root / 'plugins/JavaRailways/config.yml').write_text('train-model-update-policy: follow-package\n')
        roots = {key: root / 'plugins/JavaRailways' / name for key, name in
                 {'packages': 'train-package-snapshots', 'models': 'model-snapshots', 'worlds': 'worlds'}.items()}
        for folder in roots.values():
            folder.mkdir()
        modified, removed, created = roots['worlds'] / 'world.json', roots['models'] / 'old.json', roots['packages'] / 'new.json'
        modified.write_bytes(b'original-world')
        modified.chmod(0o640)
        removed.write_bytes(b'original-model')
        outputs = {modified: b'migrated-world', removed: None, created: b'created-snapshot'}
        plan = {'sourceCodec': {'jarSha256': old['plugins/JavaRailways.jar']},
                'candidateCodec': {'jarSha256': new['plugins/JavaRailways.jar']},
                'roots': {key: str(path) for key, path in roots.items()}, 'files': []}
        import hashlib
        for path, data in outputs.items():
            storage = next(key for key, folder in roots.items() if path.parent == folder)
            plan['files'].append({'storage': storage, 'path': path.name,
                                  'beforeSha256': m.sha(path) if path.exists() else None,
                                  'afterSha256': hashlib.sha256(data).hexdigest() if data is not None else None,
                                  'action': 'changed'})
        plan_path = stage / 'stopped-equipment-plan.json'
        plan_path.write_text(json.dumps(plan))
        manifest = {'old': old, 'new': new, 'retainSimpleSignalsSha256': m.sha(signals),
                    'sourceCommit': 'fixture-commit', 'equipmentPlanSha256': m.sha(plan_path)}
        (stage / 'manifest.json').write_text(json.dumps(manifest))
        m.ROOT, m.STAGE = root, stage
        actual_path = Path

        def routed_path(*args):
            return backups if args == ('/opt/mcsmanager/backups/javarailways',) else actual_path(*args)

        def migrate(*args, **kwargs):
            command = args[0]
            self.assertIn('--apply', command)
            self.assertIn('--instance-root', command)
            self.assertEqual(str(root), command[command.index('--instance-root') + 1])
            for path, data in outputs.items():
                if data is None:
                    path.unlink()
                else:
                    path.write_bytes(data)

        return root, stage, backups, outputs, patch.object(m, 'Path', side_effect=routed_path), migrate

    def transaction(self, m, route):
        stack = contextlib.ExitStack()
        stack.enter_context(route)
        stack.enter_context(patch.object(m, 'stopped'))
        stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
        return stack

    def test_success_replaces_only_main_jar_and_known_behavior_with_planned_data(self):
        m = module()
        self.assertEqual({'plugins/JavaRailways.jar', 'plugins/JavaRailways/trains/s100/behavior.js'}, set(m.ALLOWED))
        self.assertEqual(1, sum(path.endswith('.jar') for path in m.ALLOWED))
        with tempfile.TemporaryDirectory() as td:
            root, stage, backups, outputs, route, migrate = self.fixture(m, Path(td))
            before = m.files()
            metadata = {rel: (stat.S_IMODE((root / rel).stat().st_mode), (root / rel).stat().st_uid,
                              (root / rel).stat().st_gid) for rel in before}
            with self.transaction(m, route), patch.object(m.subprocess, 'run', side_effect=migrate):
                m.backup(123)
                m.install(123)
            expected = dict(before)
            for path, data in outputs.items():
                rel = str(path.relative_to(root))
                if data is None:
                    expected.pop(rel)
                else:
                    expected[rel] = m.sha(path)
            expected.update(json.loads((stage / 'manifest.json').read_text())['new'])
            self.assertEqual(expected, m.files())
            for rel, digest in before.items():
                if rel.endswith('.yml') and '/cars/s100/' in rel:
                    self.assertEqual(digest, m.sha(root / rel))
            for rel in m.ALLOWED:
                info = (root / rel).stat()
                self.assertEqual(metadata[rel], (stat.S_IMODE(info.st_mode), info.st_uid, info.st_gid))
            recovery = Path(json.loads((stage / 'recovery.json').read_text())['directory'])
            self.assertEqual(backups, recovery.parent)
            self.assertFalse(recovery.is_relative_to(root))
            self.assertEqual(0o700, stat.S_IMODE(recovery.stat().st_mode))
            self.assertEqual(0o600, stat.S_IMODE((recovery / 'recovery.zip').stat().st_mode))
            saved = json.loads((recovery / 'backup-manifest.json').read_text())
            self.assertEqual(before, saved['files'])
            self.assertEqual(set(before), set(saved['metadata']))
            self.assertTrue((stage / 'install.json').is_file())

    def test_each_artifact_write_failure_restores_originals_and_removes_created_data(self):
        for failure_index in range(1, len(module().ALLOWED) + 1):
            with self.subTest(failure_index=failure_index), tempfile.TemporaryDirectory() as td:
                m = module()
                root, stage, _, outputs, route, migrate = self.fixture(m, Path(td))
                before = m.files()
                modes = {rel: stat.S_IMODE((root / rel).stat().st_mode) for rel in before}
                original, calls = m.atomic, 0

                def flaky(*args):
                    nonlocal calls
                    calls += 1
                    original(*args)
                    if calls == failure_index:
                        raise OSError('injected post-replacement failure')

                with self.transaction(m, route), patch.object(m.subprocess, 'run', side_effect=migrate):
                    m.backup(123)
                    with patch.object(m, 'atomic', side_effect=flaky), self.assertRaisesRegex(OSError, 'post-replacement'):
                        m.install(123)
                self.assertEqual(before, m.files())
                self.assertFalse((stage / 'install.json').exists())
                for rel in before:
                    self.assertEqual(modes[rel], stat.S_IMODE((root / rel).stat().st_mode))
                self.assertTrue(all(not path.exists() for path in outputs if str(path.relative_to(root)) not in before))

    def test_partial_migration_failure_restores_modified_deleted_and_created_items(self):
        m = module()
        with tempfile.TemporaryDirectory() as td:
            root, stage, _, _, route, migrate = self.fixture(m, Path(td))
            before = m.files()

            def failed(*args, **kwargs):
                migrate(*args, **kwargs)
                raise RuntimeError('migration failed after writes')

            with self.transaction(m, route), patch.object(m.subprocess, 'run', side_effect=failed):
                m.backup(123)
                with self.assertRaisesRegex(RuntimeError, 'migration failed after writes'):
                    m.install(123)
            self.assertEqual(before, m.files())
            self.assertFalse((stage / 'install.json').exists())

    def test_unplanned_migration_change_restores_original_backup_file(self):
        m = module()
        with tempfile.TemporaryDirectory() as td:
            root, stage, _, _, route, migrate = self.fixture(m, Path(td))
            before = m.files()

            def unplanned(*args, **kwargs):
                migrate(*args, **kwargs)
                (root / 'plugins/SimpleSignals/world.dat').write_bytes(b'unplanned-corruption')

            with self.transaction(m, route), patch.object(m.subprocess, 'run', side_effect=unplanned):
                m.backup(123)
                with self.assertRaises(RuntimeError):
                    m.install(123)
            self.assertEqual(before, m.files())
            self.assertFalse((stage / 'install.json').exists())

    def test_mutation_after_backup_blocks_before_migration_or_atomic_writes(self):
        m = module()
        with tempfile.TemporaryDirectory() as td:
            root, _, _, _, route, _ = self.fixture(m, Path(td))
            with self.transaction(m, route):
                m.backup(123)
                (root / 'plugins/SimpleSignals/world.dat').write_bytes(b'late external mutation')
                with patch.object(m.subprocess, 'run') as migration, patch.object(m, 'atomic') as writer:
                    with self.assertRaisesRegex(RuntimeError, 'source changed after backup'):
                        m.install(123)
                    migration.assert_not_called()
                    writer.assert_not_called()

    def test_changed_plan_or_recovery_archive_blocks_before_migration(self):
        for target in ('plan', 'archive'):
            with self.subTest(target=target), tempfile.TemporaryDirectory() as td:
                m = module()
                _, stage, _, _, route, _ = self.fixture(m, Path(td))
                with self.transaction(m, route):
                    m.backup(123)
                    if target == 'plan':
                        (stage / 'stopped-equipment-plan.json').write_text('{}')
                    else:
                        recovery = Path(json.loads((stage / 'recovery.json').read_text())['directory'])
                        (recovery / 'recovery.zip').write_bytes(b'corrupt')
                    with patch.object(m.subprocess, 'run') as migration, patch.object(m, 'atomic') as writer:
                        with self.assertRaises(RuntimeError):
                            m.install(123)
                        migration.assert_not_called()
                        writer.assert_not_called()

    def test_exact_replacement_set_baseline_and_candidates_are_guarded(self):
        for mutation in ('set', 'baseline', 'candidate'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as td:
                m = module()
                root, stage, _, _, _, _ = self.fixture(m, Path(td))
                plan = json.loads((stage / 'manifest.json').read_text())
                rel = next(iter(m.ALLOWED))
                if mutation == 'set':
                    plan['old']['plugins/other.jar'] = 'unexpected'
                elif mutation == 'baseline':
                    (root / rel).write_bytes(b'changed-live-baseline')
                else:
                    (stage / m.ALLOWED[rel]).write_bytes(b'changed-candidate')
                with self.assertRaises(RuntimeError):
                    m.check_plan(plan)

    def test_backup_aborts_on_source_mutation_without_publishing_recovery_reference(self):
        m = module()
        with tempfile.TemporaryDirectory() as td:
            _, stage, _, _, route, _ = self.fixture(m, Path(td))
            before = m.files()
            with self.transaction(m, route), patch.object(m, 'files', side_effect=[before, before | {'unexpected': 'hash'}]):
                with self.assertRaisesRegex(RuntimeError, 'source changed during recovery'):
                    m.backup(123)
            self.assertFalse((stage / 'recovery.json').exists())

    def test_symlink_in_data_boundary_is_rejected(self):
        m = module()
        with tempfile.TemporaryDirectory() as td:
            root, _, _, _, _, _ = self.fixture(m, Path(td))
            (root / 'plugins/JavaRailways/unsafe').symlink_to('/tmp')
            with self.assertRaisesRegex(RuntimeError, 'symlink'):
                m.files()

    def test_unknown_new_file_is_retained_but_originals_are_restored(self):
        m = module()
        with tempfile.TemporaryDirectory() as td:
            root, stage, _, outputs, route, migrate = self.fixture(m, Path(td))
            before = m.files()
            foreign = root / 'plugins/JavaRailways/foreign.dat'

            def unplanned(*args, **kwargs):
                migrate(*args, **kwargs)
                foreign.write_bytes(b'unknown-new-owner')

            with self.transaction(m, route), patch.object(m.subprocess, 'run', side_effect=unplanned):
                m.backup(123)
                with self.assertRaisesRegex(RuntimeError, 'unknown new files retained'):
                    m.install(123)
            self.assertEqual(b'unknown-new-owner', foreign.read_bytes())
            self.assertEqual(before, {rel: digest for rel, digest in m.files().items() if rel in before})
            self.assertFalse((stage / 'install.json').exists())
            self.assertTrue(all(not path.exists() for path in outputs if str(path.relative_to(root)) not in before))

    def test_foreign_content_at_planned_new_path_is_not_deleted(self):
        m = module()
        with tempfile.TemporaryDirectory() as td:
            root, stage, _, outputs, route, migrate = self.fixture(m, Path(td))
            before = m.files()
            created = next(path for path in outputs if not path.exists())

            def collision(*args, **kwargs):
                migrate(*args, **kwargs)
                created.write_bytes(b'foreign-collision')

            with self.transaction(m, route), patch.object(m.subprocess, 'run', side_effect=collision):
                m.backup(123)
                with self.assertRaisesRegex(RuntimeError, 'no longer owned'):
                    m.install(123)
            self.assertEqual(b'foreign-collision', created.read_bytes())
            self.assertEqual(before, {rel: digest for rel, digest in m.files().items() if rel in before})
            self.assertFalse((stage / 'install.json').exists())

    def test_existing_receipt_is_never_overwritten_and_blocks_before_mutation(self):
        m = module()
        with tempfile.TemporaryDirectory() as td:
            _, stage, _, _, route, _ = self.fixture(m, Path(td))
            receipt = stage / 'install.json'
            receipt.write_bytes(b'pre-existing-receipt')
            before = m.files()
            with self.transaction(m, route), patch.object(m.subprocess, 'run') as migration, patch.object(m, 'atomic') as writer:
                with self.assertRaisesRegex(RuntimeError, 'receipt already exists'):
                    m.install(123)
                migration.assert_not_called()
                writer.assert_not_called()
            self.assertEqual(b'pre-existing-receipt', receipt.read_bytes())
            self.assertEqual(before, m.files())

    def test_receipt_failure_rolls_back_and_preserves_failed_receipt_evidence(self):
        m = module()
        with tempfile.TemporaryDirectory() as td:
            _, stage, backups, _, route, migrate = self.fixture(m, Path(td))
            before = m.files()
            original = m.record

            def failed_record(path, data, *args):
                original(path, data, *args)
                if path.name == 'install.json':
                    raise OSError('injected receipt sync failure')

            with self.transaction(m, route), patch.object(m.subprocess, 'run', side_effect=migrate):
                m.backup(123)
                with patch.object(m, 'record', side_effect=failed_record), self.assertRaisesRegex(OSError, 'receipt sync failure'):
                    m.install(123)
            self.assertEqual(before, m.files())
            self.assertFalse((stage / 'install.json').exists())
            failed = list(backups.glob('*/failed-install-receipt-*.json'))
            self.assertEqual(1, len(failed))
            self.assertEqual('fixture-commit', json.loads(failed[0].read_text())['sourceCommit'])
            self.assertEqual(0o600, stat.S_IMODE(failed[0].stat().st_mode))

    def test_receipt_created_by_another_writer_is_preserved_after_rollback(self):
        m = module()
        with tempfile.TemporaryDirectory() as td:
            _, stage, _, _, route, migrate = self.fixture(m, Path(td))
            before = m.files()
            original = m.record

            def collision(path, data, *args):
                if path.name == 'install.json':
                    path.write_bytes(b'foreign-receipt')
                original(path, data, *args)

            with self.transaction(m, route), patch.object(m.subprocess, 'run', side_effect=migrate):
                m.backup(123)
                with patch.object(m, 'record', side_effect=collision), self.assertRaises(FileExistsError):
                    m.install(123)
            self.assertEqual(before, m.files())
            self.assertEqual(b'foreign-receipt', (stage / 'install.json').read_bytes())

    def test_foreign_replacement_of_our_receipt_does_not_block_original_restore(self):
        m = module()
        with tempfile.TemporaryDirectory() as td:
            _, stage, _, _, route, migrate = self.fixture(m, Path(td))
            before = m.files()
            original = m.record

            def replaced(path, data, *args):
                original(path, data, *args)
                if path.name == 'install.json':
                    replacement = path.with_name('foreign.json')
                    replacement.write_bytes(b'foreign-replacement-receipt')
                    os.replace(replacement, path)
                    raise OSError('receipt replaced by another writer')

            with self.transaction(m, route), patch.object(m.subprocess, 'run', side_effect=migrate):
                m.backup(123)
                with patch.object(m, 'record', side_effect=replaced), self.assertRaisesRegex(RuntimeError, 'foreign installation receipt retained'):
                    m.install(123)
            self.assertEqual(before, m.files())
            self.assertEqual(b'foreign-replacement-receipt', (stage / 'install.json').read_bytes())

    def test_rollback_refuses_writes_if_instance_restarts(self):
        m = module()
        with tempfile.TemporaryDirectory() as td:
            _, _, _, _, route, migrate = self.fixture(m, Path(td))

            def failed(*args, **kwargs):
                migrate(*args, **kwargs)
                raise RuntimeError('migration failed')

            with self.transaction(m, route):
                m.backup(123)
                with patch.object(m, 'stopped', side_effect=[None, RuntimeError('target restarted')]), \
                        patch.object(m.subprocess, 'run', side_effect=failed), patch.object(m, 'atomic') as writer:
                    with self.assertRaisesRegex(RuntimeError, 'target restarted'):
                        m.install(123)
                    writer.assert_not_called()

    def test_original_live_pid_and_invalid_pid_never_consult_mcs_or_socket(self):
        m = module()
        for pid in (os.getpid(), 0, -1, None, True):
            with self.subTest(pid=pid), patch.object(m, 'status') as status, patch.object(m.socket, 'socket') as socket:
                with self.assertRaises(RuntimeError):
                    m.stopped(pid)
                status.assert_not_called()
                socket.assert_not_called()

    def test_stopped_guard_requires_mcs_zero_no_pid_and_both_listeners_closed(self):
        m = module()
        for state in ({'status': 1}, {'status': 0, 'processInfo': {'pid': 123}}):
            with patch.object(m, 'status', return_value=state), patch.object(m.socket, 'socket') as socket:
                with self.assertRaisesRegex(RuntimeError, 'MCS instance not stopped'):
                    m.stopped(999999999)
                socket.assert_not_called()
        for occupied_index in (0, 1):
            clients = [MagicMock(), MagicMock()]
            for index, client in enumerate(clients):
                client.__enter__.return_value = client
                client.connect_ex.return_value = 0 if index == occupied_index else 111
            with patch.object(m, 'status', return_value={'status': 0}), patch.object(m.socket, 'socket', side_effect=clients):
                with self.assertRaisesRegex(RuntimeError, 'listener still active'):
                    m.stopped(999999999)
        clients = [MagicMock(), MagicMock()]
        for client in clients:
            client.__enter__.return_value = client
            client.connect_ex.return_value = 111
        with patch.object(m, 'status', return_value={'status': 0}), patch.object(m.socket, 'socket', side_effect=clients):
            m.stopped(999999999)
        self.assertEqual([('127.0.0.1', 8765), ('127.0.0.1', 29002)],
                         [client.connect_ex.call_args.args[0] for client in clients])


if __name__ == '__main__':
    unittest.main()
