// @vitest-environment jsdom
import {act,createElement as h} from 'react';
import {createRoot,type Root} from 'react-dom/client';
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import App,{DEFAULT_VIEW,navigationForAuthentication} from './App';
import {api} from './api';
import type {StationDescriptor,WorldMetadata} from './types';

const callbacks=vi.hoisted(()=>({saved:new Map<string,(station:StationDescriptor)=>void>()}));
vi.mock('./LinesPage',()=>({LinesPage:({world,metadata,onStationSaved}:any)=>{
  callbacks.saved.set(world,onStationSaved);
  return h('output',{'data-world-metadata':'lines'},JSON.stringify(metadata));
}}));
vi.mock('./DutyEditor',()=>({DutyEditor:({metadata}:any)=>h('output',{'data-world-metadata':'timetable'},JSON.stringify(metadata))}));
vi.mock('./StationProgramLibrary',()=>({StationProgramLibrary:()=>null}));
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
  const Box=({children}:any)=>h('div',null,children);
  return {App:Box,ConfigProvider:Box,Flex:Box,Layout:{Header:Box},Typography:{Text:Box},Tag:Box,Tooltip:Box,
    Button:({children,onClick,disabled}:any)=>h('button',{onClick,disabled},children),Switch:()=>null,
    Select:({'aria-label':label,value,onChange,options}:any)=>h('select',{'aria-label':label,value,onChange:(event:any)=>onChange(event.currentTarget.value)},options.map((option:any)=>h('option',{key:option.value,value:option.value},option.label))),
    Tabs:({items,onChange}:any)=>h('nav',null,items.map((item:any)=>h('button',{key:item.key,onClick:()=>onChange(item.key)},item.key)))};
});

let root:Root,container:HTMLDivElement;
const initialMetadata:WorldMetadata={stations:[
  {stationId:'central',stationName:'Central',berthId:'1',destinationId:'central@1'},
  {stationId:'central',stationName:'Central',berthId:'2',destinationId:'central@2'},
  {stationId:'harbor',stationName:'Harbor',berthId:'1',destinationId:'harbor@1'},
]};
beforeEach(()=>{
  vi.clearAllMocks();callbacks.saved.clear();vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT',true);
  vi.mocked(api.session).mockResolvedValue({authenticated:true,playerName:'Player',worldId:'world'});
  vi.mocked(api.worlds).mockResolvedValue([{id:'world',name:'World'},{id:'other',name:'Other'}]);
  vi.mocked(api.metadata).mockResolvedValue(initialMetadata);
  vi.mocked(api.lines).mockResolvedValue({revision:0,lines:[]});
  container=document.createElement('div');document.body.append(container);root=createRoot(container);
});
afterEach(async()=>{await act(async()=>root.unmount());container.remove();vi.unstubAllGlobals();});
const metadataFor=(page:string):WorldMetadata=>JSON.parse(container.querySelector(`[data-world-metadata="${page}"]`)!.textContent!);

describe('WebUI guest navigation',()=>{
  it('opens the rail map by default',()=>{expect(DEFAULT_VIEW).toBe('map');});
  it('exposes only the rail map without a verified session',()=>{
    expect(navigationForAuthentication(null)).toEqual(['map']);
    expect(navigationForAuthentication(false)).toEqual(['map']);
  });
  it('exposes protected pages after token authentication without a station tab',()=>{
    expect(navigationForAuthentication(true)).toEqual(['graph','map','lines','duties','positions','validation','issues','programs','logs']);
  });
});

describe('station metadata saved from LinesPage',()=>{
  it('updates all matching berth labels in App resources and propagates them to both editors',async()=>{
    await act(async()=>root.render(h(App)));
    const name={zh:'新中央',en:'New Central'};
    await act(async()=>callbacks.saved.get('world')!({id:'central',metadata:{name,custom:true}}));
    for(const page of ['lines','timetable']){
      const stations=metadataFor(page).stations!;
      expect(stations.filter(station=>station.stationId==='central').map(station=>station.stationName)).toEqual([name,name]);
      expect(stations.find(station=>station.stationId==='harbor')?.stationName).toBe('Harbor');
    }
  });

  it('ignores a save callback captured before switching worlds',async()=>{
    await act(async()=>root.render(h(App)));
    const savedInPreviousWorld=callbacks.saved.get('world')!;
    const otherMetadata:WorldMetadata={stations:[{stationId:'central',stationName:'Other Central',berthId:'1',destinationId:'central@1'}]};
    vi.mocked(api.metadata).mockResolvedValue(otherMetadata);
    await act(async()=>{const select=container.querySelector<HTMLSelectElement>('[aria-label="common.world"]')!;select.value='other';select.dispatchEvent(new Event('change',{bubbles:true}));});
    await act(async()=>savedInPreviousWorld({id:'central',metadata:{name:'Late old world save'}}));
    expect(metadataFor('lines')).toEqual(otherMetadata);
    expect(metadataFor('timetable')).toEqual(otherMetadata);
  });
});
