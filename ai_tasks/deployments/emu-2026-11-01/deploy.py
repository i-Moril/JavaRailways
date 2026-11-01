from pathlib import Path
import os,sys,json,hashlib,zipfile,tempfile,datetime,stat,socket,subprocess
ROOT=Path('/opt/mcsmanager/daemon/data/InstanceData/836082a48c4f48fcb1fea4e83c57dc96')
STAGE=Path('/tmp/jr-deploy-20261101-emu')
CONTROL=STAGE/'mcs-control.cjs'
ALLOWED={'plugins/JavaRailways.jar':'JavaRailways.jar','plugins/JavaRailways/trains/s100/behavior.js':'model/behavior.js'}
DIRS=('JavaRailways','SimpleSignals','TunnelBridgeBuilder')
def require(ok,msg):
 if not ok:raise RuntimeError(msg)
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def now():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def load(p):return json.loads(p.read_text())
def record(p,d,on_create=None):
 with p.open('x') as f:
  if on_create:on_create(os.fstat(f.fileno()))
  os.fchmod(f.fileno(),0o600);json.dump(d,f,indent=2);f.write('\n');f.flush();os.fsync(f.fileno())
def status():
 env=os.environ.copy();env['JR_ACTION']='status'
 r=subprocess.run(['/opt/mcsmanager/node-v20.12.2-linux-x64/bin/node',str(CONTROL)],env=env,capture_output=True,text=True,check=True)
 return json.loads(r.stdout)
def stopped(pid):
 require(type(pid) is int and pid>0,'missing target PID')
 require(not Path('/proc',str(pid)).exists(),'original target JVM still exists')
 s=status();require(s['status']==0 and not s.get('processInfo',{}).get('pid'),'MCS instance not stopped')
 for port in (8765,29002):
  with socket.socket() as client:
   client.settimeout(1);require(client.connect_ex(('127.0.0.1',port))!=0,'target listener still active')
def files():
 out={}
 for d in DIRS:
  base=ROOT/'plugins'/d
  require(base.is_dir() and not base.is_symlink(),'invalid plugin data directory')
  for p in sorted(base.rglob('*')):
   require(not p.is_symlink(),'symlink in recovery boundary')
   if p.is_file():out[str(p.relative_to(ROOT))]=sha(p)
 for p in sorted((ROOT/'plugins').glob('*.jar')):
  require(p.is_file() and not p.is_symlink(),'invalid plugin jar');out[str(p.relative_to(ROOT))]=sha(p)
 return out
def check_plan(m):
 require(set(m['old'])==set(ALLOWED)==set(m['new']),'unexpected replacement set')
 for rel,name in ALLOWED.items():
  p=ROOT/rel;require(p.is_file() and not p.is_symlink(),'invalid replacement target')
  require(sha(p)==m['old'][rel],'installed baseline mismatch: '+rel)
  require(sha(STAGE/name)==m['new'][rel],'candidate mismatch: '+rel)
 require(sha(ROOT/'plugins/SimpleSignals.jar')==m['retainSimpleSignalsSha256'],'signal artifact changed')
 require('train-model-update-policy: follow-package' in (ROOT/'plugins/JavaRailways/config.yml').read_text(),'model policy changed')
def atomic(path,data,mode,uid,gid):
 fd,temp=tempfile.mkstemp(prefix=path.name+'.deploy-',dir=path.parent)
 try:
  with os.fdopen(fd,'wb') as f:
   f.write(data);os.fchmod(f.fileno(),mode);os.fchown(f.fileno(),uid,gid);f.flush();os.fsync(f.fileno())
  os.replace(temp,path)
  d=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY)
  try:os.fsync(d)
  finally:os.close(d)
 finally:
  if os.path.exists(temp):os.unlink(temp)
def backup(pid):
 stopped(pid);m=load(STAGE/'manifest.json');check_plan(m);before=files()
 base=Path('/opt/mcsmanager/backups/javarailways');base.mkdir(parents=True,exist_ok=True)
 dest=Path(tempfile.mkdtemp(prefix='emu-20261101-',dir=base));os.chmod(dest,0o700)
 archive=dest/'recovery.zip'
 with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_DEFLATED) as z:
  os.chmod(archive,0o600)
  for rel in before:z.write(ROOT/rel,rel)
 with zipfile.ZipFile(archive) as z:
  require(set(z.namelist())==set(before),'recovery file-set mismatch')
  for rel,digest in before.items():require(hashlib.sha256(z.read(rel)).hexdigest()==digest,'recovery content mismatch')
 require(files()==before,'source changed during recovery creation');stopped(pid)
 result={'createdAt':now(),'archive':str(archive),'archiveSha256':sha(archive),'files':before,'metadata':{rel:dict(uid=(ROOT/rel).stat().st_uid,gid=(ROOT/rel).stat().st_gid,mode=stat.S_IMODE((ROOT/rel).stat().st_mode)) for rel in before},'targetPid':pid,'deploymentManifestSha256':sha(STAGE/'manifest.json')}
 record(dest/'backup-manifest.json',result)
 record(STAGE/'recovery.json',{'directory':str(dest),'manifestSha256':sha(dest/'backup-manifest.json')})
 print(json.dumps({'recoveryDirectory':str(dest),'files':len(before),'archiveSha256':result['archiveSha256']}))

def equipment_args():
 p=ROOT/'plugins/JavaRailways'
 return ['python3',str(STAGE/'migrations/migrate_equipment_snapshots.py'),str(p/'train-package-snapshots'),str(p/'model-snapshots'),str(p/'worlds'),'--source-jar',str(STAGE/'source.jar'),'--candidate-jar',str(STAGE/'JavaRailways.jar'),'--java-dir','/opt/mcsmanager/daemon/data/JavaData/temurin_25/bin','--source-bridge-classes',str(STAGE/'source-helper'),'--candidate-bridge-classes',str(STAGE/'candidate-helper'),'--s100-source-behavior',str(STAGE/'s100-source-live.js'),'--s100-source-behavior',str(STAGE/'s100-source-snapshot.js'),'--s100-candidate-behavior',str(STAGE/'s100-candidate.js'),'--plan',str(STAGE/'stopped-equipment-plan.json')]

def migration_expected(m, original):
 plan_path=STAGE/'stopped-equipment-plan.json'
 require(sha(plan_path)==m['equipmentPlanSha256'],'migration plan changed')
 plan=load(plan_path)
 require(plan['sourceCodec']['jarSha256']==m['old']['plugins/JavaRailways.jar'],'wrong source codec')
 require(plan['candidateCodec']['jarSha256']==m['new']['plugins/JavaRailways.jar'],'wrong candidate codec')
 roots={k:ROOT/'plugins/JavaRailways'/d for k,d in {'packages':'train-package-snapshots','models':'model-snapshots','worlds':'worlds'}.items()}
 require(plan['roots']=={k:str(p) for k,p in roots.items()},'migration roots differ')
 result=dict(original)
 for item in plan['files']:
  name=Path(item['path']);require(name.name==str(name) and str(name) not in ('.','..'),'unsafe migration filename')
  rel=str((roots[item['storage']]/name).relative_to(ROOT))
  require(result.get(rel)==item['beforeSha256'],'migration source not from recovery')
  if item['afterSha256'] is None:result.pop(rel,None)
  else:result[rel]=item['afterSha256']
 return result

def install(pid):
 require(not (STAGE/'install.json').exists() and not (STAGE/'install.json').is_symlink(),'installation receipt already exists')
 stopped(pid);m=load(STAGE/'manifest.json');check_plan(m)
 ref=load(STAGE/'recovery.json');dest=Path(ref['directory'])
 require(dest.parent==Path('/opt/mcsmanager/backups/javarailways') and not dest.is_symlink(),'invalid recovery directory')
 require(sha(dest/'backup-manifest.json')==ref['manifestSha256'],'recovery manifest changed')
 saved=load(dest/'backup-manifest.json')
 require(saved['deploymentManifestSha256']==sha(STAGE/'manifest.json') and saved['targetPid']==pid,'wrong recovery plan')
 require(sha(saved['archive'])==saved['archiveSha256'],'recovery archive changed')
 require(files()==saved['files'],'source changed after backup')
 migrated=migration_expected(m,saved['files'])
 expected=dict(migrated);expected.update(m['new'])
 touched={rel for rel in set(expected)|set(saved['files']) if expected.get(rel)!=saved['files'].get(rel)}
 receipt_identity=None
 def receipt_created(info):
  nonlocal receipt_identity
  receipt_identity=(info.st_dev,info.st_ino)
 try:
  subprocess.run(equipment_args()+['--apply','--backup',str(dest/'equipment'),'--instance-root',str(ROOT)],check=True)
  require(files()==migrated,'migration changed unplanned files')
  for rel,name in ALLOWED.items():
   require(sha(ROOT/rel)==m['old'][rel] and sha(STAGE/name)==m['new'][rel],'cutover hash mismatch')
   old=saved['metadata'][rel]
   atomic(ROOT/rel,(STAGE/name).read_bytes(),old['mode'],old['uid'],old['gid'])
  require(files()==expected,'unexpected installation changes')
  result={'installedAt':now(),'sourceCommit':m['sourceCommit'],'replacements':m['new'],'migrationChangedFiles':sum(a.get('action')!='unchanged' for a in load(STAGE/'stopped-equipment-plan.json')['files']),'preservedFiles':sum(expected.get(k)==v for k,v in saved['files'].items()),'recoveryDirectory':str(dest),'archiveSha256':saved['archiveSha256']}
  record(STAGE/'install.json',result,receipt_created)
 except BaseException as error:
  stopped(pid)
  receipt_collision=False
  if receipt_identity is not None:
   receipt=STAGE/'install.json'
   info=receipt.lstat() if receipt.exists() or receipt.is_symlink() else None
   if info is not None and (info.st_dev,info.st_ino)==receipt_identity:
    descriptor,failed_receipt=tempfile.mkstemp(prefix='failed-install-receipt-',suffix='.json',dir=dest)
    os.close(descriptor);os.replace(receipt,failed_receipt)
   else:receipt_collision=True
  with zipfile.ZipFile(saved['archive']) as z:
   # The world is stopped: restore every changed or missing original, including an
   # unexpected migration write, before reporting any unowned new-file collision.
   for rel in sorted(saved['files']):
    path=ROOT/rel;old=saved['metadata'][rel]
    info=path.lstat() if path.exists() or path.is_symlink() else None
    if info is None or not stat.S_ISREG(info.st_mode) or sha(path)!=saved['files'][rel] or (stat.S_IMODE(info.st_mode),info.st_uid,info.st_gid)!=(old['mode'],old['uid'],old['gid']):
     atomic(path,z.read(rel),old['mode'],old['uid'],old['gid'])
   for rel in sorted(touched-set(saved['files'])):
    path=ROOT/rel
    if path.exists() or path.is_symlink():
     require(path.is_file() and not path.is_symlink() and sha(path)==expected[rel],'new file no longer owned by this installation')
     path.unlink()
     descriptor=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY)
     try:os.fsync(descriptor)
     finally:os.close(descriptor)
  require(files()==saved['files'],'rollback differs from recovery; unknown new files retained')
  if receipt_collision:raise RuntimeError('foreign installation receipt retained after rollback') from error
  raise
 print(json.dumps(result))

if __name__=='__main__':
 require(len(sys.argv)==3 and sys.argv[1] in ('backup','install'),'usage: backup|install PID')
 (backup if sys.argv[1]=='backup' else install)(int(sys.argv[2]))
