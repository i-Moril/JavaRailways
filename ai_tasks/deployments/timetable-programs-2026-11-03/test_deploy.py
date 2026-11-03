import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
import zipfile

HERE = Path(__file__).parent
SCRIPT = HERE / 'deploy.py'
INSTANCE = '836082a48c4f48fcb1fea4e83c57dc96'


def module():
    spec = importlib.util.spec_from_file_location('timed_ops_deploy_fixture', SCRIPT)
    obj = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(obj)
    return obj


def candidate_jar(main_script: bytes):
    inner = io.BytesIO()
    with zipfile.ZipFile(inner, 'w') as bundle:
        bundle.writestr('public/index.html', b'<html></html>')
        bundle.writestr('public/assets/index-test.js', main_script)
    outer = io.BytesIO()
    with zipfile.ZipFile(outer, 'w') as bundle:
        bundle.writestr('webui/JavaRailways-WebUI.jar', inner.getvalue())
    return outer.getvalue(), inner.getvalue()


class DeploymentTests(unittest.TestCase):
    def fixture(self, m, base):
        root = base / 'instance'
        stage = base / 'stage'
        backups = base / 'backups'
        stage.mkdir()
        backups.mkdir()
        for directory in m.DIRS:
            folder = root / 'plugins' / directory
            folder.mkdir(parents=True)
            (folder / 'world.dat').write_bytes((directory + '-original-save').encode())
        old = root / 'plugins/JavaRailways.jar'
        old.write_bytes(b'old-main-jar')
        signals = root / 'plugins/SimpleSignals.jar'
        signals.write_bytes(b'unmodified-signals')
        bridge = root / 'plugins/TunnelBridgeBuilder.jar'
        bridge.write_bytes(b'unmodified-bridge')
        (root / 'plugins/ThirdParty.jar').write_bytes(b'other-plugin')
        (root / 'plugins/JavaRailways/config.yml').write_text(
            'train-model-update-policy: follow-package\n')
        main_script = b'console.log("timed ops");\n'
        jar, webui = candidate_jar(main_script)
        (stage / 'JavaRailways.jar').write_bytes(jar)
        manifest = {
            'sourceCommit': 'fixture-commit',
            'branch': 'fixture-branch',
            'old': {'plugins/JavaRailways.jar': hashlib.sha256(old.read_bytes()).hexdigest()},
            'new': {'plugins/JavaRailways.jar': hashlib.sha256(jar).hexdigest()},
            'retainSimpleSignalsSha256': hashlib.sha256(signals.read_bytes()).hexdigest(),
            'retainTunnelBridgeBuilderSha256': hashlib.sha256(bridge.read_bytes()).hexdigest(),
            'webuiSha256': hashlib.sha256(webui).hexdigest(),
            'mainScript': '/assets/index-test.js',
            'mainScriptEntry': 'public/assets/index-test.js',
            'mainScriptSha256': hashlib.sha256(main_script).hexdigest(),
        }
        (stage / 'manifest.json').write_text(json.dumps(manifest))
        return root, stage, backups, patch.object(m, 'ROOT', root), \
            patch.object(m, 'STAGE', stage), patch.object(m, 'BASE', backups), \
            patch.object(m, 'stopped')

    def test_backup_install_and_rollback_preserve_unrelated_bytes(self):
        m = module()
        with tempfile.TemporaryDirectory() as td:
            base = Path(td)
            (root, stage, backups, root_patch, stage_patch, base_patch,
             stopped_patch) = self.fixture(m, base)
            with root_patch, stage_patch, base_patch, stopped_patch:
                before = m.inventory()
                modes = {rel: stat.S_IMODE((root / rel).stat().st_mode) for rel in m.ALLOWED}
                with contextlib.redirect_stdout(io.StringIO()):
                    m.preflight(123)
                    m.backup(123)
                    m.install(123)
                installed = m.inventory()
                recovery = json.loads((stage / 'recovery.json').read_text())
                recovery_dir = Path(recovery['directory'])
                self.assertEqual(0o700, stat.S_IMODE(recovery_dir.stat().st_mode))
                self.assertEqual(0o600, stat.S_IMODE(
                    (recovery_dir / 'recovery.zip').stat().st_mode))
                self.assertTrue((stage / 'install.json').is_file())
                receipt = json.loads((stage / 'install.json').read_text())
                expected = dict(before)
                expected['plugins/JavaRailways.jar'] = json.loads(
                    (stage / 'manifest.json').read_text())['new']['plugins/JavaRailways.jar']
                self.assertEqual(expected, installed)
                for rel in m.ALLOWED:
                    self.assertEqual(modes[rel], stat.S_IMODE((root / rel).stat().st_mode))
                with self.assertRaises(Exception):
                    with contextlib.redirect_stdout(io.StringIO()):
                        m.install(123)
                with contextlib.redirect_stdout(io.StringIO()):
                    m.rollback(123, str(recovery_dir))
                self.assertEqual(before, m.inventory())
                self.assertEqual('fixture-commit', receipt['sourceCommit'])

    def test_candidate_mismatch_blocks_preflight(self):
        m = module()
        with tempfile.TemporaryDirectory() as td:
            base = Path(td)
            (root, stage, backups, root_patch, stage_patch, base_patch,
             stopped_patch) = self.fixture(m, base)
            (stage / 'JavaRailways.jar').write_bytes(b'wrong')
            with root_patch, stage_patch, base_patch, stopped_patch, \
                    self.assertRaisesRegex(RuntimeError, 'candidate mismatch'):
                m.preflight(123)


if __name__ == '__main__':
    unittest.main()
