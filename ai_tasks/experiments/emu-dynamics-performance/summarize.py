"""Summarize paired array-ablation forks; no significance or production capacity claim."""
import argparse
import csv
import json
import statistics as stats
from pathlib import Path

root = Path(__file__).resolve().parent
parser = argparse.ArgumentParser()
parser.add_argument('--input', type=Path, default=root / 'array-optimization-run-2026-10-31')
args = parser.parse_args()
folder = args.input.resolve()
forks = {p.stem: list(csv.DictReader(line for line in p.open() if not line.startswith('#')))
         for p in sorted(folder.glob('fork-*.csv'))}
models = ('baseline', 'prototype', 'fresh-arrays', 'reused-arrays')
assert len(forks) == 3
assert all(len(rows) == 540 for rows in forks.values())
results = []
for cars in (4, 8, 16):
    for work in ('step', 'selection', 'recovery256'):
        scenarios = ('accelerate',) if work == 'recovery256' else ('accelerate', 'cruise', 'brake', 'release')
        for scenario in scenarios:
            cell = {'cars': cars, 'workload': work, 'scenario': scenario}
            medians = {}
            for model in models:
                samples = {}
                allocation = []
                steps = []
                for name, rows in forks.items():
                    values = [r for r in rows if int(r['cars']) == cars and r['workload'] == work
                              and r['scenario'] == scenario and r['model'] == model]
                    assert len(values) == 5, (cars, work, scenario, model, name)
                    samples[name] = stats.median(float(r['ns_per_op']) for r in values)
                    allocation.append(stats.median(float(r['bytes_per_op']) for r in values))
                    steps += [int(r['recovery_steps']) for r in values]
                medians[model] = samples
                cell[model] = {'median_us': stats.median(samples.values()) / 1000,
                               'fork_median_range_us': [min(samples.values()) / 1000, max(samples.values()) / 1000],
                               'bytes_per_op': stats.median(allocation), 'recovery_steps': sorted(set(steps))}
            for m in models[1:]:
                assert cell[m]['recovery_steps'] == cell['prototype']['recovery_steps'], cell
            cell['ratio_reused_to_prototype'] = cell['reused-arrays']['median_us'] / cell['prototype']['median_us']
            cell['ratio_reused_to_baseline'] = cell['reused-arrays']['median_us'] / cell['baseline']['median_us']
            cell['paired_fork_ratios_reused_to_prototype'] = {
                name: medians['reused-arrays'][name] / medians['prototype'][name] for name in forks}
            results.append(cell)
(folder / 'summary.json').write_text(json.dumps(results, indent=2) + '\n')
for c in results:
    if c['cars'] == 8 or c['scenario'] == 'accelerate':
        print(c['cars'], c['workload'], c['scenario'],
              ' / '.join(f"{m}={c[m]['median_us']:.3f}us,{c[m]['bytes_per_op']:.0f}B" for m in models),
              f"reused/prototype={c['ratio_reused_to_prototype']:.2f}")
