"""Run sequential independent JVMs after compiling with run.gradle."""
from pathlib import Path
import hashlib
import json
import platform
import subprocess

root = Path(__file__).resolve().parent
cp = Path('/tmp/jr-emu-classpath').read_text()
for seed in (11, 29, 47):
    with (root / f'fork-{seed}.csv').open('w') as out:
        subprocess.run(['java', '-Xms512m', '-Xmx512m', '-XX:+UseG1GC', '-cp', cp,
                        'experiment.EmuPerformance', str(seed)], stdout=out, check=True)
    print('completed JVM', seed, flush=True)
repo = root.parents[2]
metadata = {
    'date_utc': '2026-10-31', 'platform': platform.platform(),
    'cpu': next((line.split(':',1)[1].strip() for line in Path('/proc/cpuinfo').read_text().splitlines()
                 if line.startswith('model name')), 'unknown'),
    'java': subprocess.run(['java', '-version'], capture_output=True, text=True, check=True).stderr,
    'head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip(),
    'working_tree_note': 'Existing uncommitted door changes preserved; benchmark baseline uses current production TrainDynamics.',
    'sha256': {str(p.relative_to(repo)): hashlib.sha256(p.read_bytes()).hexdigest() for p in [
        root / 'EmuPerformance.kt', repo / 'Basic/src/main/java/work/art1st/javarailways/basic/control/TrainDynamics.kt',
        repo / 'Basic/src/main/java/work/art1st/javarailways/basic/control/HandleTrainActuator.kt']}
}
(root / 'environment.json').write_text(json.dumps(metadata, indent=2) + '\n')
