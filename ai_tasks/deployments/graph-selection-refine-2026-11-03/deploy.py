"""Deploy the timed-operation copy / unfocused-graph WebUI build to JavaRailwaysTest.

Modes (run as root on the test server, with the server normally stopped):
  preflight PID     verify stopped state, installed/candidate hashes and WebUI resources
  backup PID        create the external plugin-data/JAR recovery point
  install PID       atomically replace plugins/JavaRailways.jar
  rollback PID DIR  restore the exact pre-deployment plugin data/JAR set

The recovery boundary is plugins/JavaRailways, plugins/SimpleSignals,
plugins/TunnelBridgeBuilder and plugins/*.jar. It is a plugin-data backup, not a
Minecraft region-file backup.
"""
from pathlib import Path
import datetime
import hashlib
import io
import json
import os
import socket
import stat
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path('/opt/mcsmanager/daemon/data/InstanceData/836082a48c4f48fcb1fea4e83c57dc96')
STAGE = Path('/tmp/jr-deploy-20261103-graph-refine')
BASE = Path('/opt/mcsmanager/backups/javarailways')
CONTROL = STAGE / 'mcs-control.cjs'
NODE = '/opt/mcsmanager/node-v20.12.2-linux-x64/bin/node'
ALLOWED = {'plugins/JavaRailways.jar': 'JavaRailways.jar'}
DIRS = ('JavaRailways', 'SimpleSignals', 'TunnelBridgeBuilder')


def require(ok, msg):
    if not ok:
        raise RuntimeError(msg)


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def sha_bytes(data):
    return hashlib.sha256(data).hexdigest()


def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def load(path):
    return json.loads(Path(path).read_text())


def record(path, data):
    with Path(path).open('x') as stream:
        os.fchmod(stream.fileno(), 0o600)
        json.dump(data, stream, indent=2)
        stream.write('\n')
        stream.flush()
        os.fsync(stream.fileno())


def control(action):
    env = os.environ.copy()
    env['JR_ACTION'] = action
    result = subprocess.run([NODE, str(CONTROL)], env=env, capture_output=True,
                            text=True, check=True)
    return json.loads(result.stdout)


def stopped(pid):
    require(type(pid) is int and pid > 0, 'missing target PID')
    require(not Path('/proc', str(pid)).exists(), 'original target JVM still exists')
    status = control('status')
    require(status['status'] == 0 and not status.get('processInfo', {}).get('pid'),
            'MCS instance not stopped')
    for port in (8765, 29002):
        with socket.socket() as client:
            client.settimeout(1)
            require(client.connect_ex(('127.0.0.1', port)) != 0,
                    'target listener still active: ' + str(port))


def inventory():
    result = {}
    for directory in DIRS:
        base = ROOT / 'plugins' / directory
        require(base.is_dir() and not base.is_symlink(), 'invalid plugin data directory')
        for path in sorted(base.rglob('*')):
            require(not path.is_symlink(), 'symlink in recovery boundary')
            if path.is_file():
                result[str(path.relative_to(ROOT))] = sha(path)
    for path in sorted((ROOT / 'plugins').glob('*.jar')):
        require(path.is_file() and not path.is_symlink(), 'invalid plugin jar')
        result[str(path.relative_to(ROOT))] = sha(path)
    return result


def check_plan(manifest):
    require(set(manifest['old']) == set(ALLOWED) == set(manifest['new']),
            'unexpected replacement set')
    for rel, name in ALLOWED.items():
        target = ROOT / rel
        require(target.is_file() and not target.is_symlink(),
                'invalid replacement target: ' + rel)
        require(sha(target) == manifest['old'][rel], 'installed baseline mismatch: ' + rel)
        require(sha(STAGE / name) == manifest['new'][rel], 'candidate mismatch: ' + rel)
    require(sha(ROOT / 'plugins/SimpleSignals.jar') == manifest['retainSimpleSignalsSha256'],
            'SimpleSignals artifact changed')
    require(sha(ROOT / 'plugins/TunnelBridgeBuilder.jar') == manifest['retainTunnelBridgeBuilderSha256'],
            'TunnelBridgeBuilder artifact changed')
    require('train-model-update-policy: follow-package'
            in (ROOT / 'plugins/JavaRailways/config.yml').read_text(), 'model policy changed')
    with zipfile.ZipFile(STAGE / ALLOWED['plugins/JavaRailways.jar']) as jar:
        require('webui/JavaRailways-WebUI.jar' in jar.namelist(),
                'candidate is missing the embedded WebUI')
        webui = jar.read('webui/JavaRailways-WebUI.jar')
    require(sha_bytes(webui) == manifest['webuiSha256'], 'embedded WebUI hash mismatch')
    with zipfile.ZipFile(io.BytesIO(webui)) as bundle:
        entry = manifest['mainScriptEntry']
        require(entry in bundle.namelist(), 'main WebUI script missing')
        require(sha_bytes(bundle.read(entry)) == manifest['mainScriptSha256'],
                'main WebUI script hash mismatch')


def atomic(path, data, mode, uid, gid):
    descriptor, temporary = tempfile.mkstemp(prefix=path.name + '.deploy-', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(data)
            os.fchmod(stream.fileno(), mode)
            os.fchown(stream.fileno(), uid, gid)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def preflight(pid):
    stopped(pid)
    manifest = load(STAGE / 'manifest.json')
    check_plan(manifest)
    before = inventory()
    print(json.dumps({'phase': 'preflight', 'files': len(before),
                      'installed': manifest['old'], 'candidate': manifest['new'],
                      'webuiSha256': manifest['webuiSha256'],
                      'mainScript': manifest['mainScript'],
                      'mainScriptEntry': manifest['mainScriptEntry']}, indent=2))


def backup(pid):
    stopped(pid)
    manifest = load(STAGE / 'manifest.json')
    check_plan(manifest)
    before = inventory()
    BASE.mkdir(parents=True, exist_ok=True)
    destination = Path(tempfile.mkdtemp(prefix='timed-ops-20261103-', dir=BASE))
    os.chmod(destination, 0o700)
    archive = destination / 'recovery.zip'
    with zipfile.ZipFile(archive, 'x', compression=zipfile.ZIP_DEFLATED) as bundle:
        os.chmod(archive, 0o600)
        for rel in before:
            bundle.write(ROOT / rel, rel)
    with zipfile.ZipFile(archive) as bundle:
        require(set(bundle.namelist()) == set(before), 'recovery file-set mismatch')
        for rel, digest in before.items():
            require(sha_bytes(bundle.read(rel)) == digest, 'recovery content mismatch: ' + rel)
    require(inventory() == before, 'source changed during recovery creation')
    stopped(pid)
    result = {
        'createdAt': now(),
        'archive': str(archive),
        'archiveSha256': sha(archive),
        'files': before,
        'metadata': {rel: {
            'uid': (ROOT / rel).stat().st_uid,
            'gid': (ROOT / rel).stat().st_gid,
            'mode': stat.S_IMODE((ROOT / rel).stat().st_mode),
        } for rel in before},
        'targetPid': pid,
        'deploymentManifestSha256': sha(STAGE / 'manifest.json'),
    }
    record(destination / 'backup-manifest.json', result)
    record(STAGE / 'recovery.json', {'directory': str(destination),
                                     'manifestSha256': sha(destination / 'backup-manifest.json')})
    print(json.dumps({'recoveryDirectory': str(destination), 'files': len(before),
                      'archiveSha256': result['archiveSha256']}, indent=2))


def restore(saved, expected):
    with zipfile.ZipFile(saved['archive']) as bundle:
        for rel in sorted(saved['files']):
            path = ROOT / rel
            metadata = saved['metadata'][rel]
            info = path.lstat() if path.exists() or path.is_symlink() else None
            if (info is None or not stat.S_ISREG(info.st_mode) or sha(path) != saved['files'][rel]
                    or (stat.S_IMODE(info.st_mode), info.st_uid, info.st_gid)
                    != (metadata['mode'], metadata['uid'], metadata['gid'])):
                atomic(path, bundle.read(rel), metadata['mode'], metadata['uid'], metadata['gid'])
    for rel in sorted(set(expected) - set(saved['files'])):
        path = ROOT / rel
        if path.exists() or path.is_symlink():
            require(path.is_file() and not path.is_symlink() and sha(path) == expected[rel],
                    'new file no longer owned by this installation: ' + rel)
            path.unlink()
    require(inventory() == saved['files'], 'rollback differs from recovery')


def install(pid):
    receipt = STAGE / 'install.json'
    require(not receipt.exists() and not receipt.is_symlink(), 'installation receipt already exists')
    stopped(pid)
    manifest = load(STAGE / 'manifest.json')
    check_plan(manifest)
    reference = load(STAGE / 'recovery.json')
    recovery = Path(reference['directory'])
    require(recovery.parent == BASE and not recovery.is_symlink(), 'invalid recovery directory')
    require(sha(recovery / 'backup-manifest.json') == reference['manifestSha256'],
            'recovery manifest changed')
    saved = load(recovery / 'backup-manifest.json')
    require(saved['deploymentManifestSha256'] == sha(STAGE / 'manifest.json')
            and saved['targetPid'] == pid, 'wrong recovery plan')
    require(sha(saved['archive']) == saved['archiveSha256'], 'recovery archive changed')
    require(inventory() == saved['files'], 'source changed after backup')
    expected = dict(saved['files'])
    try:
        for rel, name in ALLOWED.items():
            require(sha(ROOT / rel) == manifest['old'][rel], 'cutover hash mismatch: ' + rel)
            require(sha(STAGE / name) == manifest['new'][rel], 'candidate hash mismatch: ' + rel)
            metadata = saved['metadata'][rel]
            atomic(ROOT / rel, (STAGE / name).read_bytes(),
                   metadata['mode'], metadata['uid'], metadata['gid'])
            expected[rel] = manifest['new'][rel]
        require(inventory() == expected, 'unexpected installation changes')
        result = {
            'installedAt': now(),
            'sourceCommit': manifest['sourceCommit'],
            'branch': manifest['branch'],
            'replacements': manifest['new'],
            'preservedFiles': sum(expected.get(rel) == digest
                                  for rel, digest in saved['files'].items()),
            'recoveryDirectory': str(recovery),
            'archiveSha256': saved['archiveSha256'],
        }
        record(receipt, result)
        print(json.dumps(result, indent=2))
    except BaseException:
        stopped(pid)
        restore(saved, expected)
        raise


def rollback(pid, directory):
    stopped(pid)
    recovery = Path(directory)
    require(recovery.parent == BASE and not recovery.is_symlink(), 'invalid recovery directory')
    saved = load(recovery / 'backup-manifest.json')
    require(sha(saved['archive']) == saved['archiveSha256'], 'recovery archive changed')
    restore(saved, {})
    print(json.dumps({'rolledBack': str(recovery), 'files': len(saved['files']),
                      'archiveSha256': saved['archiveSha256']}, indent=2))


if __name__ == '__main__':
    require(len(sys.argv) >= 3 and sys.argv[1] in ('preflight', 'backup', 'install', 'rollback'),
            'usage: preflight|backup|install PID [recovery-directory]')
    if sys.argv[1] == 'rollback':
        require(len(sys.argv) == 4, 'rollback requires PID and recovery directory')
        rollback(int(sys.argv[2]), sys.argv[3])
    else:
        require(len(sys.argv) == 3, 'unexpected arguments')
        {'preflight': preflight, 'backup': backup, 'install': install}[sys.argv[1]](int(sys.argv[2]))
