"""Install only reviewed sidecar candidates after the existing stopped-server JAR installer."""
from pathlib import Path
import datetime
import hashlib
import json
import os
import re
import stat
import sys
import tempfile

ROOT = Path('/opt/mcsmanager/daemon/data/InstanceData/836082a48c4f48fcb1fea4e83c57dc96')
BASE = Path('/opt/mcsmanager/backups/javarailways')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def inventory(excluded=()):
    result = {}
    for owner in ['JavaRailways', 'SimpleSignals', 'TunnelBridgeBuilder']:
        for path in sorted((ROOT / 'plugins' / owner).rglob('*')):
            if path in excluded:
                continue
            if path.is_symlink():
                result[str(path.relative_to(ROOT))] = 'symlink:' + os.readlink(path)
            elif path.is_file():
                result[str(path.relative_to(ROOT))] = sha(path)
    return result


recovery, stage = Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve()
require(recovery.parent == BASE and recovery.name.startswith('function-simulation-20261030-'),
        'Recovery point is outside the approved external backup directory')
require(stage.parent == Path('/tmp') and stage.name.startswith('jr-fixed-block-direction-'),
        'Stage is outside the approved temporary deployment directory')
plan = json.loads((stage / 'manifest.json').read_text())
record = json.loads((recovery / 'backup-manifest.json').read_text())
require(record['plan'] == plan, 'Deployment plan differs from the recovery manifest')
require(sha(recovery / 'plugins-and-data.tar.gz') == record['archiveSha256'], 'Recovery archive hash differs')
require(type(plan['oldPid']) is int and plan['oldPid'] > 0, 'Invalid old JVM process identity')


def require_stopped():
    require(not Path('/proc/' + str(plan['oldPid'])).exists(), 'Old server process still exists')
    for proc in Path('/proc').iterdir():
        if not proc.name.isdigit():
            continue
        try:
            cwd, exe = os.readlink(proc / 'cwd'), os.readlink(proc / 'exe')
        except (FileNotFoundError, PermissionError, ProcessLookupError):
            continue
        require(not (Path(exe).name == 'java' and cwd in (str(ROOT), str(ROOT / 'plugins/JavaRailways/webui'))),
                'Target JVM still running')


require_stopped()
require(inventory() == record['files'], 'Plugin data changed after the recovery point')
expected_jars = record['allPluginJars'] | {
    name: value['candidateSha256'] for name, value in plan['artifacts'].items()
}
require({p.name: sha(p) for p in (ROOT / 'plugins').glob('*.jar')} == expected_jars,
        'Installed plugin JAR hashes differ from the reviewed deployment')
names = sorted(plan['sidecars'])
require(len(names) == 2 and names[1] == names[0] + '.bak',
        'Expected exactly the reviewed primary and backup sidecars')
audit_path = recovery / 'migration-install-audit.json'
require(not audit_path.exists(), 'A completed sidecar install audit already exists')

expected_files, prepared = dict(record['files']), []
try:
    for name, item in plan['sidecars'].items():
        require(re.fullmatch(r'[0-9a-f-]{36}-definitions\.json(?:\.bak)?', name), 'Unexpected sidecar filename')
        relative = 'plugins/SimpleSignals/worlds/' + name
        target, source = ROOT / relative, stage / 'sidecars' / name
        require(target.is_file() and not target.is_symlink() and not source.is_symlink(), 'Sidecar path is not a regular file')
        require(sha(target) == item['sourceSha256'] == record['files'][relative], 'Live sidecar differs from recovery/source hash')
        require(sha(source) == item['candidateSha256'], 'Staged sidecar differs from candidate hash')
        old = target.stat()
        fd, temporary = tempfile.mkstemp(prefix='.' + name + '.direction-', dir=target.parent)
        prepared.append((Path(temporary), target))
        with os.fdopen(fd, 'wb') as output:
            output.write(source.read_bytes())
            output.flush()
            os.fchown(output.fileno(), old.st_uid, old.st_gid)
            os.fchmod(output.fileno(), stat.S_IMODE(old.st_mode))
            os.fsync(output.fileno())
        require(sha(Path(temporary)) == item['candidateSha256'], 'Prepared sidecar hash differs')
        expected_files[relative] = item['candidateSha256']
    require(inventory({temporary for temporary, _ in prepared}) == record['files'],
            'Plugin data changed while preparing replacements')
    require_stopped()
    require({p.name: sha(p) for p in (ROOT / 'plugins').glob('*.jar')} == expected_jars,
            'Plugin JARs changed while preparing replacements')
    for temporary, target in prepared:
        os.replace(temporary, target)
    directory = os.open(ROOT / 'plugins/SimpleSignals/worlds', os.O_RDONLY)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)
    require(inventory() == expected_files, 'Unexpected plugin data changes during installation')
    require({p.name: sha(p) for p in (ROOT / 'plugins').glob('*.jar')} == expected_jars,
            'Unexpected plugin JAR changes during installation')
finally:
    for temporary, _ in prepared:
        temporary.unlink(missing_ok=True)

audit = {
    'installedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'sourceCommit': plan['sourceCommit'],
    'sidecars': plan['sidecars'],
    'otherPluginDataUnchanged': True,
    'otherPluginJarsUnchanged': True,
}
with audit_path.open('x') as output:
    json.dump(audit, output, indent=2)
    output.write('\n')
os.chmod(audit_path, 0o600)
print(json.dumps(audit))
