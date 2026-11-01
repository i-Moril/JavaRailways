from pathlib import Path
import datetime,hashlib,json,io,re,urllib.request,urllib.error,zipfile
ROOT=Path('/opt/mcsmanager/daemon/data/InstanceData/836082a48c4f48fcb1fea4e83c57dc96');STAGE=Path('/tmp/jr-deploy-20261101-graph-colors')
load=lambda p:json.loads(p.read_text());sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
m=load(STAGE/'manifest.json');install=load(STAGE/'install.json');before=load(STAGE/'prestart.json');recovery=load(Path(install['recoveryDirectory'])/'backup-manifest.json')
for rel,digest in m['new'].items():assert sha(ROOT/rel)==digest,rel
with zipfile.ZipFile(ROOT/'plugins/JavaRailways.jar') as z:embedded=z.read('webui/JavaRailways-WebUI.jar')
assert hashlib.sha256(embedded).hexdigest()==m['webuiSha256']
assert sha(ROOT/'plugins/JavaRailways/webui/JavaRailways-WebUI.jar')==m['webuiSha256']
def get(url):
 with urllib.request.urlopen(url,timeout=15) as r:return r.status,r.read()
assert json.loads(get('http://127.0.0.1:8765/health')[1])['ok']
public=re.search(r'(?m)^\s*public-url:\s*(\S+)',(ROOT/'plugins/JavaRailways/config.yml').read_text())[1].strip('\'"').rstrip('/')
assert '?' not in public and '#' not in public
assets=[]
with zipfile.ZipFile(io.BytesIO(embedded)) as z:
 for name in z.namelist():
  if name=='public/index.html' or name.startswith(('public/assets/','public/blockly-media/')) and not name.endswith('/'):
   raw=z.read(name);path='/' if name=='public/index.html' else '/'+name.removeprefix('public/')
   assert get('http://127.0.0.1:29002'+path)[1]==raw,path
   assert get(public+path)[1]==raw,'public '+path
   assets.append(path)
 # Assert UI contract actually in installed bundle, not simply a new homepage.
 script=next(n for n in z.namelist() if n.startswith('public/assets/index-') and n.endswith('.js'))
 code=z.read(script).decode();assert 'executionTimeoutMillis' in code and 'Simulation timeout (real seconds)' in code
 assert 'estimatedDurationTicks' in code
 assert all(token in code for token in ('Run color','Reset to automatic','运行线颜色','恢复自动配色'))
 assert all(token in code for token in ('Original text','Start index (zero-based, inclusive)','End index (exclusive)','setShadow'))
 assert all(token in code for token in ('SUBSTRING','TO_STRING','TO_NUMBER','MULTIPLY','rail_operation_','rail_object'))
 assert '模拟执行超时' in code or '模拟超时' in code
preserved={rel:d for rel,d in recovery['files'].items() if rel.startswith(('plugins/JavaRailways/webui/drafts/','plugins/SimpleSignals/worlds/')) or rel.startswith('plugins/') and '/' not in rel[len('plugins/'):] and rel.endswith('.jar') and rel!='plugins/JavaRailways.jar'}
assert all(sha(ROOT/rel)==d for rel,d in preserved.items())
_,raw=get('http://127.0.0.1:29002/api/v1/worlds/world/rail-map');markers=json.loads(raw)['markers'];assert len(markers)>0
req=urllib.request.Request('http://127.0.0.1:29002/api/v1/worlds/world/programs/simulate',data=b'{}',headers={'Content-Type':'application/json'},method='POST')
try:
 urllib.request.urlopen(req,timeout=5);raise AssertionError('guest allowed')
except urllib.error.HTTPError as e:
 assert e.code==401;error=json.loads(e.read());assert error['code']=='AUTH_REQUIRED' and error.get('requestId')
logs={};installed=datetime.datetime.fromisoformat(install['installedAt'])
for rel,info in before['errorLogs'].items():
 p=ROOT/rel;data=p.read_bytes() if p.exists() else b'';tail=data[info['size']:] if len(data)>=info['size'] else data
 text=tail.decode(errors='replace');entries=[]
 for match in re.finditer(r'(?m)^\[([^\]\n]+)\] (SEVERE|ERROR|WARNING|WARN)\b',text):
  t=datetime.datetime.fromisoformat(match[1].replace('Z','+00:00'));entries.append((t,match[2]))
 logs[rel]={'preInstallShutdownErrors':sum(t<installed and l in ('ERROR','SEVERE') for t,l in entries),'postInstallErrors':sum(t>=installed and l in ('ERROR','SEVERE') for t,l in entries),'newWarnings':sum(l in ('WARNING','WARN') for _,l in entries)}
 assert not logs[rel]['postInstallErrors'],rel
text=(ROOT/'logs/latest.log').read_text(errors='replace')
assert 'ATO world: RUNNING' in text
assert 'installed, definitionRevision=' in text
runtime=[]
for line in text.splitlines():
 if 'ATO world: RUNNING' in line or 'installed, definitionRevision=' in line or 'Done (' in line:runtime.append(line)
req=urllib.request.Request('http://127.0.0.1:29002/api/v1/worlds/world/trains/stream',headers={'Accept':'text/event-stream'})
# The exact stream route is discovered from existing API source; filled separately if necessary.
result={'sourceCommit':m['sourceCommit'],'mainJarSha256':sha(ROOT/'plugins/JavaRailways.jar'),'webuiSha256':m['webuiSha256'],'replacementCount':1,'preservedInstallFiles':install['preservedFiles'],'checkedLocalAndPublicAssets':len(assets),'assets':assets,'publicUrl':public,'uiTimeoutContractInDeployedBundle':True,'preservedDraftSignalAndOtherJarFiles':len(preserved),'mapMarkers':len(markers),'guestSimulationStatus':401,'guestErrorStructured':True,'runtimeMarkers':runtime,'incrementalErrors':logs,'knownTerraTheUnsafeStartupErrorCount':text.count('Field theUnsafe not found'),'recoveryDirectory':install['recoveryDirectory'],'archiveSha256':recovery['archiveSha256']}
(STAGE/'runtime-verification.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print(json.dumps(result,ensure_ascii=False,indent=2))
