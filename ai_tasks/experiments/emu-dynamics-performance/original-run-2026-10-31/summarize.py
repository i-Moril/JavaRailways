"""Summarize independent JVM fork medians; no statistical significance claim."""
import csv
import json
import statistics as stats
from pathlib import Path

root = Path(__file__).resolve().parent
forks = {p.stem: list(csv.DictReader(line for line in p.open() if not line.startswith('#')))
         for p in sorted(root.glob('fork-*.csv'))}
assert len(forks) == 3
assert all(len(rows) == 270 for rows in forks.values())
results = []
for cars in (4, 8, 16):
    for work in ('step', 'selection', 'recovery256'):
        scenarios = ('accelerate',) if work == 'recovery256' else ('accelerate', 'cruise', 'brake', 'release')
        for scenario in scenarios:
            cell = {'cars': cars, 'workload': work, 'scenario': scenario}
            for model in ('baseline', 'prototype'):
                samples = []
                allocation = []
                steps = []
                for rows in forks.values():
                    values = [r for r in rows if int(r['cars']) == cars and r['workload'] == work
                              and r['scenario'] == scenario and r['model'] == model]
                    assert len(values) == 5
                    samples.append(stats.median(float(r['ns_per_op']) for r in values))
                    allocation.append(stats.median(float(r['bytes_per_op']) for r in values))
                    steps += [int(r['recovery_steps']) for r in values]
                cell[model] = {'median_us': stats.median(samples) / 1000,
                               'fork_median_range_us': [min(samples)/1000, max(samples)/1000],
                               'bytes_per_op': stats.median(allocation), 'recovery_steps': sorted(set(steps))}
            cell['ratio'] = cell['prototype']['median_us'] / cell['baseline']['median_us']
            results.append(cell)
(root / 'summary.json').write_text(json.dumps(results, indent=2) + '\n')
for c in results:
    if c['cars'] == 8 or c['scenario'] == 'accelerate':
        print(c['cars'], c['workload'], c['scenario'],
              f"{c['baseline']['median_us']:.3f} -> {c['prototype']['median_us']:.3f} us",
              f"x{c['ratio']:.2f}",
              f"{c['baseline']['bytes_per_op']:.0f} -> {c['prototype']['bytes_per_op']:.0f} B")
