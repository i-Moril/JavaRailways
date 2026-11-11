#!/usr/bin/env python3
"""One-shot old +X to new +Z model coordinates, on an extracted stopped backup."""
import argparse
import copy
import hashlib
import json
import math
from pathlib import Path
from math3d import C, CI, mul, euler, angles, vector


def xyz(d):
    v=tuple(float(d.get(k,0)) for k in 'xyz')
    if not all(math.isfinite(x) for x in v): raise ValueError('Non-finite vector')
    return v


def mapping(v):
    return dict(zip('xyz',v))


def rail_element(d, flat=False):
    if flat:
        for prefix in ('offset','lightProbeOffset'):
            raw=tuple(float(d.get(prefix+k,0)) for k in 'XYZ')
            if not all(math.isfinite(x) for x in raw): raise ValueError('Non-finite offset')
            v=vector(raw)
            d.update(zip((prefix+k for k in 'XYZ'),v))
        q=euler(tuple(float(d.get('rotation'+k,0)) for k in 'XYZ'))
        d.update(zip(('rotation'+k for k in 'XYZ'),angles(mul(C,q))))
    else:
        for key in ('offset','light_probe_offset'):
            if key in d: d[key]=mapping(vector(xyz(d[key])))
        d['rotation']=mapping(angles(mul(C,euler(xyz(d.get('rotation',{}))))))


def pose(d, ground=False):
    if not ground: d.update(mapping(vector(xyz(d))))
    keys=['rotation'+k for k in 'XYZW']
    q=tuple(float(d.get(k,1 if k.endswith('W') else 0)) for k in keys)
    n=sum(x*x for x in q)
    if not math.isfinite(n) or abs(n-1)>1e-9: raise ValueError('Stored quaternion must be unit length')
    q=mul(q,CI) if ground else mul(mul(C,q),CI)
    norm=math.sqrt(sum(x*x for x in q)); q=tuple(x/norm for x in q)
    d.update(zip(keys,q))


def transform_wand_state(raw):
    d=json.loads(raw)
    if not isinstance(d,dict): raise ValueError('Wand state must be an object')
    if d.get('railPreset') is None: return None
    pose(d['railPreset'])
    return json.dumps(d,ensure_ascii=False,separators=(',',':'),allow_nan=False)


def placement(d):
    if d['kind']=='ground': pose(d['pose'],True)
    elif d['kind']=='rail':
        if 'adjustment' not in d:
            d['adjustment']=dict(x=0,y=0,z=0,rotationX=0,rotationY=0,rotationZ=0,rotationW=1)
        pose(d['adjustment'])
    else: raise ValueError('Unknown placement kind')


def definitions(d):
    if d.get('schemaVersion') != 1: raise ValueError('Unsupported definitions schema')
    for group in ('fixedBlockSignals','protectionSignals','routeIndicators','attachedSignals'):
        for entry in d.get(group,[]):
            p=entry.get('placement') if group in ('fixedBlockSignals','protectionSignals') else entry.get('model',{}).get('placement')
            if p is not None: placement(p)


def model(d, signalpack=False):
    def has_import(value):
        if isinstance(value,dict): return 'import' in value or 'imports' in value or any(has_import(x) for x in value.values())
        return isinstance(value,list) and any(has_import(x) for x in value)
    if not isinstance(d.get('parts'),list): raise ValueError('Model parts must be a list')
    if d.get('imports') or (signalpack and has_import(d['parts'])): raise ValueError('Complete model imports need explicit migration review')
    d['parts']=[{'type':'dummy','rotation':{'y':-90.0},'parts':d['parts']}]
    for box in d.get('selection-boxes',[]):
        lo,hi=box['min'],box['max']
        box['min']=[-hi[2],lo[1],lo[0]]; box['max']=[-lo[2],hi[1],hi[0]]
    for preset in d.get('rail-placement',{}).get('presets',{}).values():
        if 'offset' in preset: preset['offset']=mapping(vector(xyz(preset['offset'])))
        if 'rotation' in preset:
            preset['rotation']=mapping(angles(mul(mul(C,euler(xyz(preset['rotation']))),CI)))


def render(d):
    if d.get('version') != 2: raise ValueError('Unsupported render metadata version')
    ids={}
    for t in d['templates']:
        old=t['templateId']
        if old in ids: raise ValueError('Duplicate template identity')
        for el in t['elements']: rail_element(el,True)
        content={k:v for k,v in t.items() if k!='templateId'}
        t['templateId']=hashlib.sha256(json.dumps(content,sort_keys=True,separators=(',',':'),allow_nan=False).encode()).hexdigest()
        ids[old]=t['templateId']
    for rail in d['rails']: rail['templateId']=ids[rail['templateId']]


def digest(data): return hashlib.sha256(data).hexdigest()


def plan(source):
    import yaml
    from nbt import rewrite_dat, rewrite_region
    files=sorted(source.rglob('*'))
    if any(p.is_symlink() for p in files): raise ValueError('Symlink inputs are not supported')
    inventory={p.relative_to(source).as_posix():digest(p.read_bytes()) for p in files if p.is_file()}
    changes={}; visited=[]; changed_wands=0
    def convert(path,fn,yml=False):
        if not path.is_file(): return
        raw=path.read_bytes(); d=yaml.safe_load(raw) if yml else json.loads(raw)
        before=copy.deepcopy(d); fn(d)
        if before!=d:
            changes[path.relative_to(source).as_posix()]=(yaml.safe_dump(d,allow_unicode=True,sort_keys=False) if yml else json.dumps(d,ensure_ascii=False,indent=2,allow_nan=False)+'\n').encode()
    jr=source/'plugins/JavaRailways'; ss=source/'plugins/SimpleSignals'
    rail_paths=set()
    if (jr/'rail_model.yml').is_file(): rail_paths.add(jr/'rail_model.yml')
    for manifest in (jr/'rails').glob('*/manifest.yml'):
        info=yaml.safe_load(manifest.read_bytes()); name=info.get('rail-config')
        if name:
            p=manifest.parent/name
            if not p.resolve().is_relative_to(manifest.parent.resolve()): raise ValueError('Unsafe rail-config path')
            if not p.is_file(): raise ValueError('Missing rail config')
            rail_paths.add(p)
    for path in sorted(rail_paths): convert(path,lambda d:[rail_element(el) for el in d['elements']],True)
    for pattern in ('*-rail-render.json','*-rail-render.json.bak'):
        for path in (jr/'worlds').glob(pattern): convert(path,render)
    roots=set((ss/'signal-packs').glob('*/model/**/*.yml'))
    for manifest in (ss/'models').glob('*/manifest.yml'):
        info=yaml.safe_load(manifest.read_bytes())
        for name in info['models']:
            p=manifest.parent/'models'/f'{name}.yml'
            if not p.resolve().is_relative_to(manifest.parent.resolve()) or not p.is_file(): raise ValueError('Unsafe/missing model path')
            roots.add(p)
    for path in sorted(roots): convert(path,lambda d:model(d, path.is_relative_to(ss/'signal-packs')),True)
    for path in (ss/'worlds').glob('*'):
        if path.is_file() and (path.name.endswith('-definitions.json') or path.name.endswith('-definitions.json.bak')): convert(path,definitions)
    def callback(name,value):
        return transform_wand_state(value) if name=='simplesignals:signal_wand_state' else None
    for world in source.iterdir():
        if not world.is_dir() or not (world/'level.dat').is_file(): continue
        for path in sorted(world.rglob('*')):
            if not path.is_file() or path.name=='uid.dat': continue
            if path.suffix not in ('.dat','.dat_old','.mca'): continue
            visited.append(path.relative_to(source).as_posix()); raw=path.read_bytes()
            try:
                result=(rewrite_region if path.suffix=='.mca' else rewrite_dat)(raw,callback)
            except (ValueError,UnicodeError) as failure:
                raise ValueError(f'{path.relative_to(source)}: {failure}') from failure
            # NBT helpers return bytes; source is retained if no targeted state changed.
            if not isinstance(result,tuple) or len(result)!=2 or not isinstance(result[0],bytes) or not isinstance(result[1],int): raise ValueError('Unexpected NBT rewrite result')
            result,count=result; changed_wands+=count
            if result != raw: changes[path.relative_to(source).as_posix()]=result
    for name,sha in inventory.items():
        if digest((source/name).read_bytes())!=sha: raise ValueError('Source changed during planning')
    if set(inventory)!={p.relative_to(source).as_posix() for p in source.rglob('*') if p.is_file()}: raise ValueError('Source file set changed')
    report={'migration':'model-local-x-forward-to-z-forward','source':str(source),'sourceSha256':inventory,
            'changedFiles':{name:{'before':inventory[name],'after':digest(data)} for name,data in changes.items()},
            'visitedStorageFiles':visited,'changedWandPresets':changed_wands,'modifiedStorageFiles':sum(name in changes for name in visited)}
    return changes,report


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source',type=Path,required=True,help='Extracted full backup of stopped server')
    parser.add_argument('--output',type=Path,help='NEW directory for changed-only patch; omitted means dry-run')
    args=parser.parse_args(); source=args.source.absolute()
    if source.is_symlink() or not source.is_dir(): raise ValueError('Source must be a real directory')
    if args.output:
        output=args.output.absolute()
        if output.exists() or output.is_symlink() or output.resolve().is_relative_to(source.resolve()): raise ValueError('Output must be a new directory outside source')
    changes,report=plan(source)
    if args.output:
        output.mkdir(parents=True,exist_ok=False)
        for name,data in changes.items():
            target=output/name; target.parent.mkdir(parents=True,exist_ok=True); target.write_bytes(data)
        (output/'migration-report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({'dryRun':not bool(args.output),'changedFiles':len(changes),'visitedStorageFiles':len(report['visitedStorageFiles']),'modifiedStorageFiles':report['modifiedStorageFiles'],'changedWandPresets':report['changedWandPresets']},indent=2))

if __name__=='__main__': main()
