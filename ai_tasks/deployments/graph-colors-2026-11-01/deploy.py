from pathlib import Path
import os,sys,json,hashlib,zipfile,tempfile,datetime,stat,socket,subprocess
ROOT=Path('/opt/mcsmanager/daemon/data/InstanceData/836082a48c4f48fcb1fea4e83c57dc96')
STAGE=Path('/tmp/jr-deploy-20261101-graph-colors')
CONTROL=STAGE/'mcs-control.cjs'
ALLOWED={'plugins/JavaRailways.jar':'JavaRailways.jar'}
DIRS=('JavaRailways','SimpleSignals','TunnelBridgeBuilder')
def require(ok,msg):
 if not ok:raise RuntimeError(msg)
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def now():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def load(p):return json.loads(p.read_text())
def record(p,d):
 with p.open('x') as f:
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
 dest=Path(tempfile.mkdtemp(prefix='graph-colors-20261101-',dir=base));os.chmod(dest,0o700)
 archive=dest/'recovery.zip'
 with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_DEFLATED) as z:
  os.chmod(archive,0o600)
  for rel in before:z.write(ROOT/rel,rel)
 with zipfile.ZipFile(archive) as z:
  require(set(z.namelist())==set(before),'recovery file-set mismatch')
  for rel,digest in before.items():require(hashlib.sha256(z.read(rel)).hexdigest()==digest,'recovery content mismatch')
 require(files()==before,'source changed during recovery creation');stopped(pid)
 result={'createdAt':now(),'archive':str(archive),'archiveSha256':sha(archive),'files':before,'targetPid':pid,'deploymentManifestSha256':sha(STAGE/'manifest.json')}
 record(dest/'backup-manifest.json',result)
 record(STAGE/'recovery.json',{'directory':str(dest),'manifestSha256':sha(dest/'backup-manifest.json')})
 print(json.dumps({'recoveryDirectory':str(dest),'files':len(before),'archiveSha256':result['archiveSha256']}))
def install(pid):
 stopped(pid);m=load(STAGE/'manifest.json');check_plan(m);ref=load(STAGE/'recovery.json');dest=Path(ref['directory'])
 require(dest.parent==Path('/opt/mcsmanager/backups/javarailways') and not dest.is_symlink(),'invalid recovery directory')
 require(sha(dest/'backup-manifest.json')==ref['manifestSha256'],'recovery manifest changed');saved=load(dest/'backup-manifest.json')
 require(saved['deploymentManifestSha256']==sha(STAGE/'manifest.json') and saved['targetPid']==pid,'recovery tied to wrong plan')
 require(sha(saved['archive'])==saved['archiveSha256'],'recovery archive changed')
 require(files()==saved['files'],'source changed after backup');check_plan(m)
 prepared={rel:(ROOT/rel).stat() for rel in ALLOWED}
 changed=[]
 try:
  for rel,name in ALLOWED.items():
   require(sha(ROOT/rel)==m['old'][rel] and sha(STAGE/name)==m['new'][rel],'cutover hash mismatch')
   old=prepared[rel];changed.append(rel)
   atomic(ROOT/rel,(STAGE/name).read_bytes(),stat.S_IMODE(old.st_mode),old.st_uid,old.st_gid)
  actual=files();expect=dict(saved['files']);expect.update(m['new']);require(actual==expect,'unexpected installation changes')
 except BaseException:
  with zipfile.ZipFile(saved['archive']) as z:
   for rel in reversed(changed):
    old=prepared[rel];atomic(ROOT/rel,z.read(rel),stat.S_IMODE(old.st_mode),old.st_uid,old.st_gid)
  require(files()==saved['files'],'rollback mismatch');raise
 result={'installedAt':now(),'sourceCommit':m['sourceCommit'],'replacements':m['new'],'preservedFiles':len(saved['files'])-len(ALLOWED),'recoveryDirectory':str(dest)}
 record(STAGE/'install.json',result);print(json.dumps(result))
if __name__=='__main__':
 require(len(sys.argv)==3 and sys.argv[1] in ('backup','install'),'usage: backup|install PID')
 (backup if sys.argv[1]=='backup' else install)(int(sys.argv[2]))
