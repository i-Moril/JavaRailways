import contextlib, importlib.util, io, json, os, stat, tempfile, unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT=Path(__file__).with_name('deploy.py')

def module():
    spec=importlib.util.spec_from_file_location('deploy_fixture',SCRIPT)
    obj=importlib.util.module_from_spec(spec);spec.loader.exec_module(obj);return obj

class DeploymentTests(unittest.TestCase):
    def fixture(self, m, base):
        root=base/'instance';stage=base/'stage';backups=base/'backups';stage.mkdir();backups.mkdir()
        for directory in m.DIRS:
            folder=root/'plugins'/directory;folder.mkdir(parents=True)
            (folder/'world.dat').write_bytes((directory+'-original-save').encode())
        old={};new={}
        for index,(rel,name) in enumerate(m.ALLOWED.items()):
            p=root/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(('old-'+name).encode());p.chmod(0o640 if index else 0o644)
            candidate=stage/name;candidate.write_bytes(('new-'+name).encode());old[rel]=m.sha(p);new[rel]=m.sha(candidate)
        signals=root/'plugins/SimpleSignals.jar';signals.write_bytes(b'unmodified-signals')
        (root/'plugins/ThirdParty.jar').write_bytes(b'other-plugin')
        (root/'plugins/JavaRailways/config.yml').write_text('train-model-update-policy: follow-package\n')
        plan={'old':old,'new':new,'retainSimpleSignalsSha256':m.sha(signals),'sourceCommit':'fixture-commit'}
        (stage/'manifest.json').write_text(json.dumps(plan));m.ROOT=root;m.STAGE=stage
        actual_path=Path
        def routed_path(*args):
            return backups if args==('/opt/mcsmanager/backups/javarailways',) else actual_path(*args)
        return root,stage,patch.object(m,'Path',side_effect=routed_path)

    def test_success_preserves_unrelated_bytes_and_target_permissions(self):
        m=module()
        with tempfile.TemporaryDirectory() as td:
            root,stage,path_patch=self.fixture(m,Path(td));before=m.files()
            permissions={rel:stat.S_IMODE((root/rel).stat().st_mode) for rel in m.ALLOWED}
            owners={rel:((root/rel).stat().st_uid,(root/rel).stat().st_gid) for rel in m.ALLOWED}
            with path_patch,patch.object(m,'stopped') as stopped,contextlib.redirect_stdout(io.StringIO()):
                m.backup(123);m.install(123)
                self.assertGreaterEqual(stopped.call_count,3)
            expected=before|json.loads((stage/'manifest.json').read_text())['new'];self.assertEqual(expected,m.files())
            for rel in m.ALLOWED:
                p=root/rel;self.assertEqual(permissions[rel],stat.S_IMODE(p.stat().st_mode));self.assertEqual(owners[rel],(p.stat().st_uid,p.stat().st_gid))
            recovery=json.loads((stage/'recovery.json').read_text());self.assertEqual(0o700,stat.S_IMODE(Path(recovery['directory']).stat().st_mode))
            self.assertEqual(0o600,stat.S_IMODE((Path(recovery['directory'])/'recovery.zip').stat().st_mode))
            self.assertTrue((stage/'install.json').is_file())

    def test_failure_on_first_write_rolls_back_entire_replacement_set(self):
        m=module()
        with tempfile.TemporaryDirectory() as td:
            root,stage,path_patch=self.fixture(m,Path(td));before=m.files();original=m.atomic;calls=0
            def flaky(*args):
                nonlocal calls
                calls+=1
                result=original(*args)
                if calls==1:raise OSError('injected post-replacement failure')
                return result
            with path_patch,patch.object(m,'stopped'),contextlib.redirect_stdout(io.StringIO()):
                m.backup(123)
                with patch.object(m,'atomic',side_effect=flaky),self.assertRaisesRegex(OSError,'post-replacement'):
                    m.install(123)
            self.assertEqual(before,m.files());self.assertFalse((stage/'install.json').exists())

    def test_mutation_after_backup_blocks_install_before_any_write(self):
        m=module()
        with tempfile.TemporaryDirectory() as td:
            root,stage,path_patch=self.fixture(m,Path(td))
            with path_patch,patch.object(m,'stopped'),contextlib.redirect_stdout(io.StringIO()):
                m.backup(123);(root/'plugins/SimpleSignals/world.dat').write_bytes(b'late change')
                with patch.object(m,'atomic') as writer,self.assertRaisesRegex(RuntimeError,'source changed'):
                    m.install(123)
                writer.assert_not_called()

    def test_live_original_process_blocks_stop_guard(self):
        m=module()
        with patch.object(m,'status') as status,self.assertRaisesRegex(RuntimeError,'still exists'):
            m.stopped(os.getpid())
        status.assert_not_called()

if __name__=='__main__':unittest.main()
