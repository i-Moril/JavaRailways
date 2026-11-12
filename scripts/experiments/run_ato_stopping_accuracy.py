#!/usr/bin/env python3
"""Run opt-in offline measurements; creates CSV/JSON/logs, never contacts a server."""
import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
HARNESS = ROOT / 'BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/http/AtoStoppingAccuracyExperiment.kt'
RUNS = {
    'matrix': {'FULL': '1'},
    'long': {'LONG': '1'},
    '200ms': {'FULL': '1', 'STEP_MS': '200'},
    'candidate-200ms': {'FULL': '1', 'STEP_MS': '200', 'ADVANCE': '0.05', 'ONLY_STOP': '1'},
    'candidate-jitter': {'FULL': '1', 'JITTER': '1', 'ADVANCE': '0.05', 'ONLY_STOP': '1'},
    'candidate-long': {'LONG': '1', 'ADVANCE': '0.05', 'ONLY_STOP': '1'},
    'highspeed-default-berth': {'DISTANCE': '4000', 'CAP': '33.3', 'STEP_MS': '200', 'BERTH_TOLERANCE': '1.0'},
    'highspeed-candidate': {'DISTANCE': '4000', 'CAP': '33.3', 'STEP_MS': '200', 'ADVANCE': '0.05', 'ONLY_STOP': '1'},
}


def summarize(folder):
    groups = {}
    total = 0
    expected_counts = dict(zip(RUNS, [288, 144, 288, 96, 96, 48, 6, 2]))
    for name, options in RUNS.items():
        with (folder / f'{name}.csv').open() as stream:
            rows = list(csv.DictReader(stream))
        assert len(rows) == expected_counts[name], (name, len(rows))
        total += len(rows)
        modes = {}
        for mode in sorted({row['mode'] for row in rows}):
            selected = [row for row in rows if row['mode'] == mode]
            errors = [float(row['final_error_m']) for row in selected]
            modes[mode] = {
                'cases': len(selected),
                'stopped': sum(row['status'] == 'STOPPED' for row in selected),
                'reportedArrival': sum(row['arrival'] == 'true' for row in selected),
                'finalErrorRangeMeters': [min(errors), max(errors)],
                'maximumForwardExcursionMetersRelativeToNominalTarget': max(float(row['max_error_m']) for row in selected),
                'maximumPostStopDriftMeters': max(abs(float(row['drift_m'])) for row in selected),
                'maximumMeasuredSpeedMetersPerSecond': max(float(row['peak_mps']) for row in selected),
            }
        groups[name] = {'options': options, 'modes': modes}
    inputs = [HARNESS, ROOT / 'examples/models/s100/control.yml', ROOT / 'examples/models/s100/manifest.yml']
    inputs += sorted((ROOT / 'examples/models/s100/cars').rglob('*.yml'))
    result = {
        'sourceCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        'totalCases': total,
        'runOptions': groups,
        'findings': {
            'baselineStopMaximumOvershootMeters': max(v['modes']['STOP']['maximumForwardExcursionMetersRelativeToNominalTarget'] for k, v in groups.items() if 'candidate' not in k),
            'candidateAdvanceMeters': 0.05,
            'candidateFinalErrorRangeMeters': [
                min(v['modes']['STOP']['finalErrorRangeMeters'][0] for k, v in groups.items() if 'candidate' in k),
                max(v['modes']['STOP']['finalErrorRangeMeters'][1] for k, v in groups.items() if 'candidate' in k)],
            'stationTightToleranceFinding': 'At 0.1m berth tolerance the existing 2*min(berthTolerance,0.1) margin targets ~0.2m early; all240 station cases stop but none emits BERTHED.',
            'stationDefaultToleranceFinding': 'At default1.0m tolerance the two4km/200ms station cases emit BERTHED and hold0.478276m early. This is not ordinary point stopping accuracy.',
            'recommendation': '0.05m is a measured candidate for ordinary target advance in these S100 scenarios. Station control/tolerance coupling and authority semantics must be corrected and retested before using it as the production station fix. No universal safety bound inferred.',
        },
        'inputSha256': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs},
        'method': {
            'execution': 'Real SimpleATO + HandleTrainActuator + TrainDynamics + Core PhysicsCoordinator. Controlled, pre-granted movement authority; no dispatcher/resource admission or signal plugin invoked.',
            'geometry': 'Straight constant-grade rail, fixture cars with package lengths and 0.3m connectors; fixture bogies at car ends rather than rendered model offsets.',
            'signedError': 'positive = beyond nominal target in direction of travel; negative = before target',
            'stationToleranceMeters': '0.1 except highspeed-default-berth uses production default 1.0',
            'arrivalColumn': 'STATION: real BERTHED notification; STOP/AUTHORITY: geometric abs(error)<=0.1, not dispatcher completion',
            'authorityTarget': 'AUTHORITY uses a mission target 200m farther and authority at nominal target; current braking target is 5m before nominal target',
            'candidate': 'STOP only, existing mission offsetMetersBefore=0.05; no runtime implementation changed',
            'stopCriterion': 'exactly zero published speed for at least 2 simulated seconds; then 40 additional steps measure drift',
            'jitter': 'deterministic repeated [25,200,50,100,25,50] ms, not random statistical samples',
            'motion': 'starts from rest; cap is configured maximum, peak column is actual attained speed; 4km cases attain 33.3m/s',
            'limits': 'S100 profiles only; repeated five-car composition for ten cars retains motor/trailer ratio. No curvature, grade transitions, changing adhesion, sudden authority contraction, live server scheduling or game acceptance measured. Finite observed maxima are not a universal safety bound.',
        },
    }
    (folder / 'summary.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({'cases': total, 'summary': str(folder / 'summary.json')}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--summarize-only', action='store_true')
    args = parser.parse_args()
    folder = args.output.resolve()
    folder.mkdir(parents=True, exist_ok=True)
    if not args.summarize_only:
        for name, options in RUNS.items():
            env = {k: v for k, v in os.environ.items() if not k.startswith('JR_ATO_ACCURACY_')}
            env.update({'JR_ATO_ACCURACY_' + k: v for k, v in options.items()})
            env['JR_ATO_ACCURACY_OUTPUT'] = str(folder / f'{name}.csv')
            with (folder / f'{name}.log').open('w') as log:
                subprocess.run(['./gradlew', ':BukkitPlugin:test', '--rerun', '--tests', '*AtoStoppingAccuracyExperiment', '--offline', '--no-daemon'],
                               cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
            print('Completed ' + name, flush=True)
    summarize(folder)


if __name__ == '__main__':
    main()
