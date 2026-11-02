// @vitest-environment jsdom
import {act,useEffect,useState} from 'react';
import {createRoot,type Root} from 'react-dom/client';
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import App from './App';
import {api} from './api';

const saved=vi.hoisted(()=>vi.fn());
// Child persistence is tested in its own suite; this probe detects App unmounting
// either an editor's draft or its pending debounced write during navigation.
function EditorProbe({name}:{name:string}){
  const [draft,setDraft]=useState('');
  useEffect(()=>{
    if(!draft)return;
    const timer=setTimeout(()=>saved(name,draft),1000);
    return()=>clearTimeout(timer);
  },[draft,name]);
  return <input aria-label={`${name} draft`} value={draft} onInput={event=>setDraft(event.currentTarget.value)}/>;
}
vi.mock('./DutyEditor',()=>({DutyEditor:()=> <EditorProbe name="timetable"/>}));
vi.mock('./StationProgramLibrary',()=>({StationProgramLibrary:()=> <EditorProbe name="program"/>}));
vi.mock('./LinesPage',()=>({LinesPage:()=> <EditorProbe name="line"/>}));
vi.mock('./RailMap',()=>({RailMap:()=>null}));
vi.mock('./LogsPage',()=>({LogsPage:()=>null}));
vi.mock('./IssuesPage',()=>({IssuesPage:()=>null}));
vi.mock('./api',()=>({api:{session:vi.fn(),worlds:vi.fn(),metadata:vi.fn(),lines:vi.fn(),renewSession:vi.fn()}}));
vi.mock('./sessionActivity',()=>({trackSessionActivity:()=>()=>undefined}));
vi.mock('./frontendDev',()=>({frontendDemoEnabled:false}));
vi.mock('./i18n',()=>({useI18n:()=>({locale:'en-US',setLocale:vi.fn(),t:(key:string)=>key})}));
vi.mock('./theme',()=>({useTheme:()=>({theme:'light',setTheme:vi.fn()})}));
vi.mock('./studioShell',()=>({antDesignLocale:()=>undefined,antDesignTheme:()=>undefined}));
vi.mock('antd',()=>{
  const Wrapper=({children}:any)=><div>{children}</div>;
  return {App:Wrapper,ConfigProvider:Wrapper,Flex:Wrapper,Layout:{Header:Wrapper},Typography:{Text:Wrapper},Tag:Wrapper,Tooltip:Wrapper,
    Button:({children,onClick,disabled}:any)=><button disabled={disabled} onClick={onClick}>{children}</button>,Select:()=>null,Switch:()=>null,
    Tabs:({items,onChange}:any)=><nav>{items.map((item:any)=><button key={item.key} onClick={()=>onChange(item.key)}>{item.key}</button>)}</nav>};
});
let root:Root,container:HTMLDivElement;
const click=async(name:string)=>act(async()=>{[...container.querySelectorAll('nav button')].find(button=>button.textContent===name)!.dispatchEvent(new MouseEvent('click',{bubbles:true}));});
beforeEach(()=>{
  vi.useFakeTimers();vi.clearAllMocks();vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT',true);
  vi.mocked(api.session).mockResolvedValue({authenticated:true,playerName:'Player',worldId:'world'});
  vi.mocked(api.worlds).mockResolvedValue([{id:'world',name:'World'}]);
  vi.mocked(api.metadata).mockResolvedValue({});
  vi.mocked(api.lines).mockResolvedValue({revision:1,lines:[]});
  container=document.createElement('div');document.body.append(container);root=createRoot(container);
});
afterEach(async()=>{await act(async()=>root.unmount());container.remove();vi.useRealTimers();vi.unstubAllGlobals();});

describe('App editor lifetime during autosave',()=>{
  it.each([['lines','line'],['programs','program'],['graph','timetable']])('preserves %s edits and pending writes when leaving before the debounce',async(page,name)=>{
    await act(async()=>root.render(<App/>));await click(page);
    const input=container.querySelector<HTMLInputElement>(`[aria-label="${name} draft"]`)!;
    await act(async()=>{input.value='unsaved edit';input.dispatchEvent(new Event('input',{bubbles:true}));});
    await act(async()=>{await vi.advanceTimersByTimeAsync(250);});await click('map');
    expect(input.closest('section')?.hidden).toBe(true);
    await act(async()=>{await vi.advanceTimersByTimeAsync(750);});
    expect(saved).toHaveBeenCalledExactlyOnceWith(name,'unsaved edit');
    await click(page);
    expect(container.querySelector(`[aria-label="${name} draft"]`)).toBe(input);
    expect(input.value).toBe('unsaved edit');
  });

  it('does not mount privileged editors for guests',async()=>{
    vi.mocked(api.session).mockResolvedValue({authenticated:false});
    await act(async()=>root.render(<App/>));
    expect(container.querySelector('[aria-label$=" draft"]')).toBeNull();
    expect([...container.querySelectorAll('nav button')].map(button=>button.textContent)).toEqual(['map']);
  });

  it('unmounts all owner-scoped editors when authentication is revoked',async()=>{
    await act(async()=>root.render(<App/>));
    expect(container.querySelectorAll('[aria-label$=" draft"]')).toHaveLength(3);
    await act(async()=>window.dispatchEvent(new Event('javarailways:unauthorized')));
    expect(container.querySelector('[aria-label$=" draft"]')).toBeNull();
  });
});
