"""Three sequential, compilation-receipt-verified JVM forks of the formal production actuator."""
import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
import platform
import statistics
import subprocess

root=Path(__file__).resolve().parent
repo=root.parents[2]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output',type=Path,required=True)
parser.add_argument('--java',required=True)
parser.add_argument('--warmup',type=int,default=2400)
parser.add_argument('--frames',type=int,default=4800)
args=parser.parse_args()
from compile_provenance import RECEIPT, sha, verify_receipt
classpath=Path('/tmp/jr-production-emu-classpath').read_text()
receipt_bytes=RECEIPT.read_bytes()
receipt=json.loads(receipt_bytes)
verify_receipt(repo,receipt,classpath)
args.output.mkdir(parents=True,exist_ok=False)
(args.output/'compilation-receipt.json').write_bytes(receipt_bytes)
frozen=receipt['source_sha256'] | {name:value for classes in receipt['tasks'].values() for name,value in classes.items()}
def verify():
    if RECEIPT.read_bytes()!=receipt_bytes:
        raise ValueError('Compilation receipt changed during benchmark')
    verify_receipt(repo,receipt,Path('/tmp/jr-production-emu-classpath').read_text())
rows=[];commands=[]
for seed in [11,29,47]:
    verify()
    command=[args.java,'-Xms512m','-Xmx512m','-XX:+UseG1GC','-cp',classpath,
             'experiment.productionemu.ProductionEmuPerformance',str(seed),str(args.warmup),str(args.frames)]
    commands.append(command[:5]+['<test-runtime-classpath>']+command[6:])
    result=subprocess.run(command,capture_output=True,text=True)
    (args.output/f'fork-{seed}.stdout').write_text(result.stdout)
    (args.output/f'fork-{seed}.stderr').write_text(result.stderr)
    result.check_returncode()
    csv_text='\n'.join(line.removeprefix('row,') for line in result.stdout.splitlines() if line.startswith('row,'))+'\n'
    (args.output/f'fork-{seed}.csv').write_text(csv_text)
    current=list(csv.DictReader(io.StringIO(csv_text)))
    assert len(current)==6,current
    rows.extend(current);verify()
    print('completed independent JVM',seed,flush=True)
summary=[]
for cars in [4,8,16]:
    for mode in ['actuation-only','envelope-and-actuate']:
        selected=[r for r in rows if int(r['cars'])==cars and r['mode']==mode]
        summary.append(dict(cars=cars,mode=mode,forks=3,median_mean_microseconds=statistics.median(float(r['mean_ns']) for r in selected)/1000,
            median_p95_microseconds=statistics.median(float(r['p95_ns']) for r in selected)/1000,
            median_p99_microseconds=statistics.median(float(r['p99_ns']) for r in selected)/1000,
            median_bytes_per_frame=statistics.median(float(r['bytes_per_frame']) for r in selected),
            minimum_moving_frames=min(int(r['moving_frames']) for r in selected),maximum_speed=max(float(r['max_speed']) for r in selected)))
(args.output/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
metadata=dict(head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip(),platform=platform.platform(),
    java=subprocess.run([args.java,'-version'],capture_output=True,text=True,check=True).stderr,
    cpu=next((line.split(':',1)[1].strip() for line in Path('/proc/cpuinfo').read_text().splitlines() if line.startswith('model name')),'unknown'),
    commands=commands,warmup_frames_per_case=args.warmup,measured_frames_per_case=args.frames,sha256=frozen,compilation_receipt_sha256=sha(RECEIPT),
    scope='50ms formal actuator frames; optional braking envelope construction/query plus spatial candidate admission; fixed Core geometry; excludes full world tick, route scanning, display/JS and network',
    raw_sha256={p.name:sha(p) for p in args.output.glob('fork-*')})
(args.output/'environment.json').write_text(json.dumps(metadata,indent=2)+'\n')
