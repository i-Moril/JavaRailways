"""Rebuild the comparison from the four saved, unchanged-harness raw reports."""
from pathlib import Path
import json
import statistics

ROOT = Path(__file__).resolve().parent
reports = {}
for arm in ('before', 'after'):
    for shape in ('straight', 'curved'):
        report = json.loads((ROOT / f'{arm}-{shape}' / 'physics-capacity.json').read_text())
        reports[arm, shape] = report
        assert report['config']['trackShape'] == shape
for shape in ('straight', 'curved'):
    assert reports['before', shape]['config'] == reports['after', shape]['config']
    assert {k: v for k, v in reports['before', shape]['environment'].items() if k != 'gitRevision'} == {
        k: v for k, v in reports['after', shape]['environment'].items() if k != 'gitRevision'}

metrics = ['wallP50Millis', 'wallP95Millis', 'wallP99Millis', 'wallMaxMillis',
           'workerMaxP95Millis', 'workerTotalP95Millis', 'coordinatorP95Millis']
rows = []
for shape in ('straight', 'curved'):
    for scenario in ('isolated', 'mixed', 'shared'):
        for count in (100, 500):
            row = {'shape': shape, 'scenario': scenario, 'trains': count}
            for arm in ('before', 'after'):
                matches = [r for r in reports[arm, shape]['results'] if r['scenario'] == scenario and r['trainCount'] == count]
                assert len(matches) == 3
                row[arm] = {m: {'median': statistics.median(r[m] for r in matches),
                               'min': min(r[m] for r in matches), 'max': max(r[m] for r in matches)} for m in metrics}
                row[arm]['allP95Under50ms'] = all(r['passed'] for r in matches)
            before = row['before']['wallP95Millis']['median']
            after = row['after']['wallP95Millis']['median']
            row.update(p95DeltaMillis=after-before, p95ChangePercent=(after/before-1)*100, p95SpeedRatio=before/after)
            rows.append(row)
(ROOT / 'comparison.json').write_text(json.dumps({'aggregation': 'Median of three per-run percentiles from repeated scenario/world runs in one JVM per shape/arm; NOT pooled percentile', 'rows': rows}, indent=2))
lines = ['# Coordinate constraint performance comparison', '',
         '2026-10-11 · same machine, JVM 17.0.20, 512 MiB test heap, 4 workers, 15 cars/train, 10 m/s, 50 ms logical tick.',
         'Each cell aggregates three repeated scenario/world runs in one JVM per shape/arm (not independent JVM forks), each with 200 warmup + 1000 measured epochs. Percentile cells are medians of per-run percentiles, not percentiles of pooled samples.', '',
         'The before arm uses arc-length bogie spacing (known incorrect on curves); after enforces 3D connector spacing. This is a correctness repair comparison, not an equal-accuracy algorithm contest.', '',
         'Independent tracks are translated by index × 100 m in both arms. The initial baseline-straight precheck and aborted overlapping-track curved setup are excluded.', '',
         '## Wall p95', '',
         '| Track | Scenario | Trains | Before p95 median [range], ms | After p95 median [range], ms | Change | Before/after |',
         '| --- | --- | ---: | ---: | ---: | ---: | ---: |']
for r in rows:
    b, a = r['before']['wallP95Millis'], r['after']['wallP95Millis']
    lines.append(f"| {r['shape']} | {r['scenario']} | {r['trains']} | {b['median']:.3f} [{b['min']:.3f}, {b['max']:.3f}] | {a['median']:.3f} [{a['min']:.3f}, {a['max']:.3f}] | {r['p95ChangePercent']:+.1f}% | {r['p95SpeedRatio']:.2f}× |")
lines += ['', '## Other wall and execution metrics', '',
          'Values are medians across the three repetitions; max is the median of per-run maxima.', '',
          '| Track/scenario/trains | Arm | p50 ms | p99 ms | max ms | worker max p95 ms | worker total p95 ms | coordinator p95 ms |',
          '| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |']
for r in rows:
    for arm in ('before', 'after'):
        lines.append('| ' + '/'.join(str(r[k]) for k in ('shape','scenario','trains')) + f' | {arm} | ' + ' | '.join(f"{r[arm][m]['median']:.3f}" for m in metrics if m != 'wallP95Millis') + ' |')
lines += ['', '## Interpretation of observed wall time', '',
          '- Initial matrix, 500 trains: straight wall p95 medians decreased 5.2–14.8%; smooth-curve isolated/mixed increased 8.3%/5.0%, shared decreased 8.1%. These are measured differences, not a blanket speedup claim.',
          '- Run-to-run variability and grouped run order limit causal attribution. For example straight shared500 after p95 spans 20.595–24.508 ms, wider than its 5.2% median decrease. Curved shared100 wall p95 rises 23.7% while worker-total p95 changes by only -0.3% and coordinator p95 rises about 20%.',
          '- Worker and coordinator percentiles are measured separately and must not be summed. Whole-epoch differences cannot be attributed entirely to coordinate solving.', '']
confirmation = []
for shape in ('straight', 'curved'):
    paths = [ROOT / f'confirmation-{arm}-{shape}' / 'physics-capacity.json' for arm in ('before', 'after')]
    if not all(p.exists() for p in paths):
        continue
    b, a = (json.loads(p.read_text()) for p in paths)
    assert b['config'] == a['config']
    assert b['environment'] == a['environment']
    assert all(len(r['results']) == 3 and all(x['passed'] for x in r['results']) for r in (b, a))
    br = [r['wallP95Millis'] for r in b['results']]
    ar = [r['wallP95Millis'] for r in a['results']]
    confirmation.append({'shape': shape, 'scenario': 'shared', 'trains': 500, 'beforeP95': br, 'afterP95': ar, 'changePercent': (statistics.median(ar)/statistics.median(br)-1)*100})
if confirmation:
    (ROOT / 'confirmation.json').write_text(json.dumps(confirmation, indent=2)+'\n')
    lines += ['## Targeted order-reversal confirmation', '',
              'After the initial fixed matrix, original sources were rebuilt and shared500 was repeated (straight then curved), followed by fixed sources (straight then curved). Each has the same 200/1000 epochs and three repeats. This is a small run-order check, not a randomized controlled experiment.', '']
    for r in confirmation:
        lines.append(f"- {r['shape']}, shared500: before p95 {r['beforeP95']} ms; after {r['afterP95']} ms; median change {r['changePercent']:+.1f}%.")
    lines += ['', 'All original samples remain in the initial matrix; confirmation is reported separately, not selectively pooled.', 'Straight shared500 confirms the direction of lower wall p95, with variable magnitude (initial -5.2%, confirmation -19.2%). Curved shared500 changes from an initial -8.1% to +0.8% in confirmation: no stable improvement is established; treat this case as approximately unchanged at the observed noise level.', '']
lines += ['', '## Absolute worst measured epoch', '']
for arm in ('before', 'after'):
    runs = [r for shape in ('straight', 'curved') for r in reports[arm, shape]['results']]
    for shape in ('straight', 'curved'):
        path = ROOT / f'confirmation-{arm}-{shape}' / 'physics-capacity.json'
        if path.exists():
            runs.extend(json.loads(path.read_text())['results'])
    worst = max(r['wallMaxMillis'] for r in runs)
    lines.append(f'- {arm}: {worst:.3f} ms (maximum across all {len(runs)} successful runs including confirmation, not median).')
lines += ['', '## Correctness and interpretation', '',
          '- Baseline geometry regression: R30/6m axle error 9.9953 mm, S curve 6.2349 mm, banked slope 20.5298 mm; all exceed 5 mm. Straight restored centre also drifted 0.722656 mm against the new 5 µm consistency assertion. Evidence: baseline-red.xml.',
          '- Corrected geometry regression: all four cases pass; full gate has 1093 tests, zero failures/errors and six existing skips (1087 executed). No Paper/Folia runtime validation was performed.',
          '- Curved stress tracks are the existing smooth sinusoidal fixture (20 m amplitude, 500 m wavelength), not the R30 correctness fixture. Performance numbers do not establish the worst-case cost of R30, S bends or banked slopes.',
          '- Raw stress reports retain per-run p50/p95/p99/max and worker/coordinator figures, plus environment/config. Harness checks epoch/controller counts, no derailment/collision, and no mailbox capacity/stale drop.',
          '- 50 ms is a headless p95 budget, not a zero-overrun guarantee. Maximum values must be read separately. No capacity ceiling was searched.',
          '- This harness uses a constant-speed controller, not real ATO/dispatcher; Paper/Folia rendering/network are excluded. No server/client runtime claim follows from these numbers.',
          '- User excluded extremely tight near-halfcircle curves. The local bracket solver does not guarantee discovery of arbitrary nonmonotonic roots.',
          '- Existing movement failure follows derail/publication handling without whole-consist rollback; this task does not add transactionality.',
          '- Run order: corrected before-straight → before-curved → fixed regression → after-straight → after-curved. Source and harness hashes are in provenance JSON; repeat confirmation results, if any, are recorded separately.', '',
          '## Raw reports', '']
for arm in ('before','after'):
    for shape in ('straight','curved'):
        lines.append(f'- [{arm}-{shape}]({arm}-{shape}/physics-capacity.json)')
        if (ROOT / f'confirmation-{arm}-{shape}' / 'physics-capacity.json').exists():
            lines.append(f'- [confirmation-{arm}-{shape}](confirmation-{arm}-{shape}/physics-capacity.json)')
(ROOT / 'comparison.md').write_text('\n'.join(lines)+'\n')
print(json.dumps([{'shape':r['shape'],'scenario':r['scenario'],'trains':r['trains'],'beforeP95':r['before']['wallP95Millis']['median'],'afterP95':r['after']['wallP95Millis']['median'],'changePercent':r['p95ChangePercent']} for r in rows], indent=2))
