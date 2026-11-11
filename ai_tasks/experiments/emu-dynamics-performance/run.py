"""Run four-way array ablation in sequential independent JVMs after run.gradle compilation."""
import argparse
from pathlib import Path
import hashlib
import json
import platform
import subprocess

root = Path(__file__).resolve().parent
parser = argparse.ArgumentParser()
parser.add_argument('--output', type=Path, default=root / 'array-optimization-run-2026-10-31')
args = parser.parse_args()
output = args.output.resolve()
repo = root.parents[2]
baseline_source = 'Basic/src/main/java/work/art1st/javarailways/basic/control/TrainDynamics.kt'
expected = json.loads((root / 'original-run-2026-10-31/environment.json').read_text())['sha256'][baseline_source]
if hashlib.sha256((repo / baseline_source).read_bytes()).hexdigest() != expected:
    raise SystemExit('Production dynamics differs from this experiment baseline; reproduce in a separate checkout at 0dbab292, using the archived experiment sources.')
output.mkdir(parents=True, exist_ok=False)
cp = Path('/tmp/jr-emu-classpath').read_text()
tracked_sources = [root / 'EmuPerformance.kt',
    repo / 'Basic/src/main/java/work/art1st/javarailways/basic/control/TrainDynamics.kt',
    repo / 'Basic/src/main/java/work/art1st/javarailways/basic/control/HandleTrainActuator.kt']
source_hashes = {str(p.relative_to(repo)): hashlib.sha256(p.read_bytes()).hexdigest() for p in tracked_sources}
compiled_classes = sorted((repo / 'Basic/build/classes/kotlin/test/experiment').glob('EmuPerformance*.class'))
assert compiled_classes, 'Experiment classes missing; compile first'
class_hashes = {str(p.relative_to(repo)): hashlib.sha256(p.read_bytes()).hexdigest() for p in compiled_classes}
def verify_frozen_inputs():
    for p in tracked_sources:
        assert hashlib.sha256(p.read_bytes()).hexdigest() == source_hashes[str(p.relative_to(repo))], p
    for p in compiled_classes:
        assert hashlib.sha256(p.read_bytes()).hexdigest() == class_hashes[str(p.relative_to(repo))], p
commands = []
for seed in (11, 29, 47):
    verify_frozen_inputs()
    command = ['java', '-Xms512m', '-Xmx512m', '-XX:+UseG1GC',
               f'-Xlog:gc:file={output / f"gc-{seed}.log"}', '-cp', cp,
               'experiment.EmuPerformance', str(seed)]
    commands.append(command[:5] + ['-cp', '<test-runtime-classpath>', 'experiment.EmuPerformance', str(seed)])
    with (output / f'fork-{seed}.csv').open('w') as out:
        subprocess.run(command, stdout=out, check=True)
    verify_frozen_inputs()
    print('completed JVM', seed, flush=True)
for p in tracked_sources:
    assert hashlib.sha256(p.read_bytes()).hexdigest() == source_hashes[str(p.relative_to(repo))], p
metadata = {
    'date_utc': '2026-10-31', 'platform': platform.platform(),
    'cpu': next((line.split(':', 1)[1].strip() for line in Path('/proc/cpuinfo').read_text().splitlines()
                 if line.startswith('model name')), 'unknown'),
    'java': subprocess.run(['java', '-version'], capture_output=True, text=True, check=True).stderr,
    'head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip(),
    'working_tree_note': 'Production dynamics unchanged; four-way array ablation is isolated to experiment source.',
    'commands': commands, 'sha256': source_hashes, 'compiled_class_sha256': class_hashes,
    'raw_csv_sha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in output.glob('fork-*.csv')}
}
(output / 'environment.json').write_text(json.dumps(metadata, indent=2) + '\n')
