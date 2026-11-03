"""Install the JAR after the one-time program migration, without requiring the
pre-migration inventory to remain unchanged. Rollback first removes the new
timetable-program directory and then delegates to deploy.py's pre-migration
recovery restore."""
from pathlib import Path
import json
import shutil
import sys
import deploy

REL = 'plugins/JavaRailways.jar'


def install(pid):
    deploy.stopped(pid)
    manifest = deploy.load(deploy.STAGE / 'manifest.json')
    deploy.check_plan(manifest)
    reference = deploy.load(deploy.STAGE / 'recovery.json')
    recovery = Path(reference['directory'])
    saved = deploy.load(recovery / 'backup-manifest.json')
    metadata = saved['metadata'][REL]
    before = deploy.inventory()
    deploy.atomic(deploy.ROOT / REL, (deploy.STAGE / 'JavaRailways.jar').read_bytes(),
                  metadata['mode'], metadata['uid'], metadata['gid'])
    expected = dict(before)
    expected[REL] = manifest['new'][REL]
    deploy.require(deploy.inventory() == expected, 'unexpected installation changes')
    deploy.record(deploy.STAGE / 'install.json', {
        'installedAt': deploy.now(),
        'sourceCommit': manifest['sourceCommit'],
        'branch': manifest['branch'],
        'replacements': manifest['new'],
        'preservedFiles': len(before) - 1,
        'recoveryDirectory': str(recovery),
        'archiveSha256': saved['archiveSha256'],
        'migrationAppliedBeforeInstall': True,
    })
    print(json.dumps(deploy.load(deploy.STAGE / 'install.json'), indent=2))


def rollback(pid, recovery):
    deploy.stopped(pid)
    program_dir = deploy.ROOT / 'plugins/JavaRailways/webui/drafts/timetable-programs'
    if program_dir.exists():
        shutil.rmtree(program_dir)
    deploy.rollback(pid, recovery)


if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == 'install':
        install(int(sys.argv[2]))
    elif len(sys.argv) == 4 and sys.argv[1] == 'rollback':
        rollback(int(sys.argv[2]), sys.argv[3])
    else:
        raise SystemExit('usage: install PID | rollback PID RECOVERY_DIRECTORY')
