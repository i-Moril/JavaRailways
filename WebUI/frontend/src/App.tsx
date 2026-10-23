import type { AuthoringCommand,AuthoringCommandState } from './authoringCommands';
import { useEffect, useState, type ReactNode } from 'react';
import { App as AntApp, Button, ConfigProvider, Flex, Layout, Select, Switch, Tabs, Tag, Tooltip, Typography } from 'antd';
import { CodeOutlined, GlobalOutlined, MoonOutlined, NodeIndexOutlined, PlayCircleOutlined, PlusOutlined, RedoOutlined, SaveOutlined, SunOutlined, CopyOutlined, UndoOutlined } from '@ant-design/icons';
import { api } from './api';
import { frontendDemoEnabled } from './frontendDev';
import { DutyEditor } from './DutyEditor';
import { LinesPage } from './LinesPage';
import { LogsPage } from './LogsPage';
import { IssuesPage } from './IssuesPage';
import { RailMap } from './RailMap';
import { useI18n } from './i18n';
import { useTheme } from './theme';
import { antDesignLocale, antDesignTheme } from './studioShell';
import type { LineCollection,WorldMetadata,WorldSummary } from './types';
import './styles.css';
import './studioPages.css';

export type View = 'graph' | 'map' | 'lines' | 'duties' | 'validation' | 'issues' | 'logs';
export const DEFAULT_VIEW:View='map';
export const navigationForAuthentication=(authenticated:boolean|null):View[]=>authenticated
  ?['graph','map','lines','duties','validation','issues','logs']
  :['map'];

type WorldResources={world:string;metadata:WorldMetadata;lineCollection:LineCollection};
const emptyWorldResources:WorldResources={world:'',metadata:{},lineCollection:{revision:0,lines:[]}};

export { LinesPage } from './LinesPage';

const authoringCommand=(command:AuthoringCommand)=>window.dispatchEvent(new CustomEvent('javarailways:authoring-command',{detail:command}));
function RibbonCommand({icon,label,command,disabled=false,reason=''}:{icon:ReactNode;label:string;command?:AuthoringCommand;disabled?:boolean;reason?:string}) {
  return <Tooltip title={reason||undefined}><span><Button type="text" className="ribbon-command" icon={icon} disabled={disabled||!!reason} onClick={()=>command&&authoringCommand(command)}>{label}</Button></span></Tooltip>;
}
function RibbonGroup({label,children}:{label:string;children:ReactNode}) {return <div className="command-group"><div className="command-items">{children}</div><span className="group-label">{label}</span></div>;}

export default function App() {
  const { locale, setLocale, t } = useI18n();
  const { theme, setTheme } = useTheme();
  const [worlds, setWorlds] = useState<WorldSummary[]>([]);
  const [world, setWorld] = useState('');
  const [worldResources,setWorldResources]=useState<WorldResources>(emptyWorldResources);
  const [view, setView] = useState<View>(DEFAULT_VIEW);
  const [authenticated,setAuthenticated]=useState<boolean|null>(null);
  const [authenticatedPlayer,setAuthenticatedPlayer]=useState('');
  const [error, setError] = useState('');
  const [authoringState,setAuthoringState]=useState<AuthoringCommandState>();
  const [busy, setBusy] = useState(false);
  // Hide the previous world's resources during the first render after a switch.
  const {metadata,lineCollection}=authenticated&&worldResources.world===world?worldResources:emptyWorldResources;
  const setLineCollection=(next:LineCollection)=>setWorldResources((current)=>current.world===world?{...current,lineCollection:next}:current);


  const run = async <T,>(operation: () => Promise<T>) => {
    setBusy(true);
    setError('');
    try { return await operation(); }
    catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
      return undefined;
    } finally { setBusy(false); }
  };

  useEffect(() => {
    let cancelled=false;
    void (async()=>{
      setBusy(true);setError('');
      try{
        const loginToken=new URLSearchParams(window.location.hash.slice(1)).get('token');
        let loginError='';
        if(loginToken){
          window.history.replaceState(null,'',`${window.location.pathname}${window.location.search}`);
          const response=await fetch('/auth/exchange',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({token:loginToken})});
          if(!response.ok){const body=await response.json().catch(()=>({})) as {error?:string};loginError=body.error||`Login failed (${response.status})`;}
        }
        const session=await api.session();
        if(cancelled)return;
        setAuthenticated(session.authenticated);setAuthenticatedPlayer(session.playerName||'');setView(session.authenticated?'graph':'map');
        const values=await api.worlds();
        if(cancelled)return;
        setWorlds(values);
        setWorld(values.find((candidate)=>candidate.id===session.worldId)?.id||values[0]?.id||'');
        if(loginError)setError(loginError);
      }catch(failure){if(!cancelled){setAuthenticated((current)=>current??false);setError(failure instanceof Error?failure.message:String(failure));}}
      finally{if(!cancelled)setBusy(false);}
    })();
    return()=>{cancelled=true;};
  }, []);

  useEffect(()=>{
    const revoke=()=>{setAuthenticated(false);setAuthenticatedPlayer('');setView('map');setWorldResources(emptyWorldResources);};
    window.addEventListener('javarailways:unauthorized',revoke);
    return()=>window.removeEventListener('javarailways:unauthorized',revoke);
  },[]);

  useEffect(()=>{
    if(!authenticated)return;
    const timer=window.setInterval(()=>{void api.session().then((session)=>{if(!session.authenticated){window.dispatchEvent(new Event('javarailways:unauthorized'));}}).catch(()=>undefined);},60_000);
    return()=>window.clearInterval(timer);
  },[authenticated]);

  useEffect(() => {
    if (!world||!authenticated) return;
    let cancelled=false;
    setBusy(true);setError('');
    void (async()=>{
      try{
        const [metadata,lineCollection]=await Promise.all([
          api.metadata(world),api.lines(world),
        ]);
        if(!cancelled)setWorldResources({world,metadata,lineCollection});
      }catch(failure){if(!cancelled)setError(failure instanceof Error?failure.message:String(failure));}
      finally{if(!cancelled)setBusy(false);}
    })();
    return()=>{cancelled=true;};
  }, [world,authenticated]);

  const navigation=navigationForAuthentication(authenticated);
  const workspaceNotice=!authenticated?t('auth.mapOnly'):
    ['graph','duties','validation'].includes(view)?t('duties.workspace'):
    view==='lines'?t('lines.description'):
    view==='issues'?t('issues.description'):view==='logs'?t('logs.description'):t('map.public');
  return <ConfigProvider
    locale={antDesignLocale(locale)}
    theme={antDesignTheme(theme)}
  ><AntApp><main className="app-shell">
    <Layout.Header className="studio-topbar">
      <Flex align="center" justify="space-between" gap={16}>
        <Flex align="center" gap={10} className="studio-brand">
          <span className="studio-brand-mark"><NodeIndexOutlined /></span>
          <div><Typography.Text strong>{t('app.title')}</Typography.Text><Typography.Text type="secondary">{t('app.subtitle')}</Typography.Text></div>
        </Flex>
        <Flex align="center" gap={8} className="studio-controls">
          {frontendDemoEnabled&&<Tag color="blue">{t('dev.frontendDemo')}</Tag>}
          <Tag color={authenticated?'green':'default'}>{authenticated?t('auth.authenticated')+(authenticatedPlayer?` · ${authenticatedPlayer}`:''):t('auth.guest')}</Tag>
          <Select disabled={view==='logs'} aria-label={t('common.world')} value={world||undefined} placeholder={t('common.world')} onChange={setWorld}
            options={worlds.map((item)=>({value:item.id,label:item.name||item.id}))}/>
          <Switch aria-label={t('common.theme')} checked={theme==='dark'} onChange={(checked)=>setTheme(checked?'dark':'light')}
            checkedChildren={<MoonOutlined />} unCheckedChildren={<SunOutlined />}/>
          <Select aria-label={t('common.language')} value={locale} onChange={(value)=>setLocale(value as 'zh-CN'|'en-US')} suffixIcon={<GlobalOutlined />}
            options={[{value:'zh-CN',label:'简体中文'},{value:'en-US',label:'English'}]}/>
        </Flex>
      </Flex>
    </Layout.Header>

    <section className={`studio-ribbon ${['graph','duties','validation'].includes(view)?'has-commands':''}`} aria-label={t('nav.primary')}>
      <Tabs activeKey={view} onChange={(key)=>setView(key as View)} items={navigation.map((item)=>({key:item,label:t(`nav.${item}`)}))}/>
      {['graph','duties','validation'].includes(view)?<div className="ribbon-commands">
        <RibbonGroup label={t('ribbon.create')}><RibbonCommand icon={<PlusOutlined/>} label={t('ribbon.newRun')} command="new-run" reason={authoringState?.['new-run']??t('ui.working')}/></RibbonGroup>
        <RibbonGroup label={t('ribbon.history')}><RibbonCommand icon={<UndoOutlined/>} label={t('common.undo')} command="undo" reason={authoringState?.['undo']??t('ui.working')}/><RibbonCommand icon={<RedoOutlined/>} label={t('common.redo')} command="redo" reason={authoringState?.['redo']??t('ui.working')}/></RibbonGroup>
        <RibbonGroup label={t('ribbon.tools')}><RibbonCommand icon={<CopyOutlined/>} label={t('ui.copyRun')} command="copy-run" reason={authoringState?.['copy-run']??t('ui.working')}/></RibbonGroup>
        <RibbonGroup label={t('ribbon.tools')}><RibbonCommand icon={<CodeOutlined/>} label={t('duties.compile')} command="compile" reason={authoringState?.['compile']??t('ui.working')}/><RibbonCommand icon={<PlayCircleOutlined/>} label={t('duties.simulate')} command="simulate" reason={authoringState?.['simulate']??t('ui.working')}/></RibbonGroup>
        <RibbonGroup label={t('ribbon.document')}><RibbonCommand icon={<SaveOutlined/>} label={t('common.save')} command="save" reason={authoringState?.['save']??t('ui.working')}/><RibbonCommand icon={<CodeOutlined/>} label={t('duties.saveScript')} command="save-formal" reason={authoringState?.['save-formal']??t('ui.working')}/><RibbonCommand icon={<NodeIndexOutlined/>} label={t('common.publish')} command="publish" reason={authoringState?.['publish']??t('ui.working')}/></RibbonGroup>
      </div>:<div className="studio-contextbar"><Tag variant="filled">{t(`nav.${view}`)}</Tag><Typography.Text type="secondary">{workspaceNotice}</Typography.Text></div>}
    </section>

    <section className="notice-strip">
      {error && <div className="error" role="alert">{error}</div>}
    </section>

    {authenticated&&<section className="persistent-graph-view" hidden={!['graph','duties','validation'].includes(view)}><DutyEditor onCommandState={setAuthoringState} page={view==='duties'?'duties':view==='validation'?'validation':'graph'} onPageChange={setView} world={world} recoveryOwner={authenticatedPlayer} lines={lineCollection.lines} metadata={metadata} onError={setError}/></section>}
    {authenticated&&view === 'logs' && <LogsPage />}
    {authenticated&&view === 'issues' && <IssuesPage key={world} world={world} />}
    {view === 'map' && <RailMap key={world} world={world} onError={setError} />}
    {view === 'lines' && <LinesPage world={world} metadata={metadata} collection={lineCollection}
      onCollection={setLineCollection} run={run} />}


  </main></AntApp></ConfigProvider>;
}
