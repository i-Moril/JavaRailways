// @vitest-environment jsdom
import {act} from 'react';
import {createRoot,type Root} from 'react-dom/client';
import {renderToStaticMarkup} from 'react-dom/server';
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import {I18nProvider} from './i18n';
import {StationMetadataEditor,StationMetadataModal} from './StationsPage';
import {api} from './api';
import type {StationCollection,StationDescriptor} from './types';

const demo=vi.hoisted(()=>({enabled:false}));
vi.mock('./api',()=>({api:{stations:vi.fn(),saveStationMetadata:vi.fn()}}));
vi.mock('./frontendDev',()=>({get frontendDemoEnabled(){return demo.enabled;}}));
// Shared editor controls have their own tests; this suite checks station draft/API ownership.
vi.mock('./MetadataJsonEditor',()=>({MetadataJsonEditor:({draft,onChange,label,scope,fieldKey}:any)=><textarea aria-label={label} data-scope={scope} data-field-key={fieldKey} value={draft} onChange={()=>undefined} onInput={event=>onChange(event.currentTarget.value)}/>}));
vi.mock('antd',()=>{
  const Box=({children}:any)=><div>{children}</div>;
  return {Flex:Box,Typography:{Text:Box,Paragraph:Box},Spin:()=>null,Empty:({description}:any)=><p>{description}</p>,
    Alert:({title,description}:any)=><div role="alert">{title}{description}</div>,
    Button:({children,onClick,disabled,loading}:any)=><button disabled={disabled||loading} onClick={onClick}>{children}</button>,
    Modal:({children,title,footer,open,onCancel}:any)=><section role="dialog" hidden={!open}><h1>{title}</h1><button aria-label="Cancel" onClick={onCancel}>×</button>{children}{footer}</section>};
});

let root:Root,container:HTMLDivElement;
const central:StationDescriptor={id:'central',metadata:{name:'Central',custom:{notice:'Keep this data'}}};
const harbor:StationDescriptor={id:'harbor',metadata:{name:'Harbor'}};
const onSaved=vi.fn();
const show=async(stationId?:string,world='world')=>act(async()=>root.render(<I18nProvider><StationMetadataModal world={world} stationId={stationId} lines={[]} onClose={()=>undefined} onSaved={onSaved}/></I18nProvider>));
const dialog=()=>container.querySelector<HTMLElement>('[role="dialog"]:not([hidden])')!;
const draft=()=>dialog().querySelector('textarea')!;
const edit=async(value:string)=>act(async()=>{draft().value=value;draft().dispatchEvent(new Event('input',{bubbles:true}));});
const click=async(text:string)=>act(async()=>{[...dialog().querySelectorAll('button')].find(button=>button.textContent===text)!.click();});
const deferred=<T,>()=>{let resolve!:(value:T)=>void;const promise=new Promise<T>(accept=>{resolve=accept;});return {promise,resolve};};
beforeEach(()=>{
  vi.clearAllMocks();demo.enabled=false;vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT',true);localStorage.setItem('javarailways.locale','en-US');
  vi.mocked(api.stations).mockResolvedValue({stations:[central,harbor]});
  vi.mocked(api.saveStationMetadata).mockImplementation(async(_world,id,metadata)=>({id,metadata}));
  container=document.createElement('div');document.body.append(container);root=createRoot(container);
});
afterEach(async()=>{await act(async()=>root.unmount());container.remove();vi.unstubAllGlobals();});

describe('station metadata modal',()=>{
  it.each(['en-US','zh-CN'])('labels JSON controls and renders arbitrary data as text in %s',(locale)=>{
    localStorage.setItem('javarailways.locale',locale);
    const html=renderToStaticMarkup(<I18nProvider><StationMetadataEditor world="world" station={{id:'station',metadata:{notice:'<script>alert(1)</script>',transfers:[{name:'Line 2'}]}}}/></I18nProvider>);
    expect(html).toContain(locale==='zh-CN'?'metadata JSON':'Metadata JSON');
    expect(html).toContain(locale==='zh-CN'?'保存 metadata':'Save metadata');
    expect(html).toContain('&lt;script&gt;alert(1)&lt;/script&gt;');
    expect(html).not.toContain('<script>alert(1)</script>');
    expect(html).toContain('Line 2');
    expect(html).toContain('data-scope="station"');
  });

  it('loads full station metadata and retains invalid drafts across closing and station switches',async()=>{
    await show('central');
    expect(api.stations).toHaveBeenCalledExactlyOnceWith('world');
    expect(JSON.parse(draft().value)).toEqual(central.metadata);
    await edit('{ unfinished');await show(undefined);await show('harbor');
    expect(JSON.parse(draft().value)).toEqual(harbor.metadata);
    await show('central');
    expect(draft().value).toBe('{ unfinished');
    expect([...dialog().querySelectorAll('button')].find(button=>button.textContent==='Save metadata')?.disabled).toBe(true);
    expect(api.saveStationMetadata).not.toHaveBeenCalled();
  });

  it('refreshes incoming data without advancing a retained draft or its save baseline',async()=>{
    await show('central');
    const edited={name:'Edited',custom:central.metadata.custom};await edit(JSON.stringify(edited));
    vi.mocked(api.stations).mockResolvedValue({stations:[{id:'central',metadata:{name:'Remote'}}]});
    await click('Refresh stations (keep draft)');
    expect(draft().value).toBe(JSON.stringify(edited));
    expect(dialog().textContent).toContain('Server metadata changed.');
    await show(undefined);await show('central');
    await click('Save metadata');
    expect(api.saveStationMetadata).toHaveBeenCalledExactlyOnceWith('world','central',edited,central.metadata);
    expect(onSaved).toHaveBeenCalledExactlyOnceWith({id:'central',metadata:edited});
  });

  it('preserves failed edits and only adopts a refreshed save baseline after explicit review',async()=>{
    await show('central');await edit('{"name":"Draft"}');
    vi.mocked(api.saveStationMetadata).mockRejectedValueOnce(new Error('metadata changed'));
    await click('Save metadata');
    expect(dialog().textContent).toContain('metadata changed');expect(draft().value).toBe('{"name":"Draft"}');
    const incoming={name:'Remote',custom:true};
    vi.mocked(api.stations).mockResolvedValue({stations:[{id:'central',metadata:incoming}]});
    await click('Refresh stations (keep draft)');
    await click('Use this baseline; keep draft (allow next overwrite)');await click('Save metadata');
    expect(api.saveStationMetadata).toHaveBeenLastCalledWith('world','central',{name:'Draft'},incoming);
  });

  it('isolates delayed list results across selected stations and worlds',async()=>{
    const first=deferred<StationCollection>();vi.mocked(api.stations).mockReturnValueOnce(first.promise);
    await show('central');expect(dialog().textContent).toContain('Loading stations');
    await show('harbor');
    await act(async()=>first.resolve({stations:[{id:'central',metadata:{name:'Late'}}]}));
    expect(JSON.parse(draft().value)).toEqual(harbor.metadata);
    const oldWorld=deferred<StationCollection>();vi.mocked(api.stations).mockReturnValueOnce(oldWorld.promise);
    await show('central');
    vi.mocked(api.stations).mockResolvedValue({stations:[{id:'central',metadata:{name:'New world'}}]});
    await show('central','other');
    await act(async()=>oldWorld.resolve({stations:[central]}));
    expect(JSON.parse(draft().value)).toEqual({name:'New world'});
  });

  it('keeps a late save attached to its station and ignores it after the world changes',async()=>{
    await show('central');await edit('{"name":"Saved central"}');
    const first=deferred<StationDescriptor>();vi.mocked(api.saveStationMetadata).mockReturnValueOnce(first.promise);
    await click('Save metadata');await show('harbor');
    await act(async()=>first.resolve({id:'central',metadata:{name:'Saved central'}}));
    expect(JSON.parse(draft().value)).toEqual(harbor.metadata);
    expect(onSaved).toHaveBeenCalledExactlyOnceWith({id:'central',metadata:{name:'Saved central'}});
    const second=deferred<StationDescriptor>();vi.mocked(api.saveStationMetadata).mockReturnValueOnce(second.promise);
    await edit('{"name":"Old world harbor"}');await click('Save metadata');await show('harbor','other');
    await act(async()=>second.resolve({id:'harbor',metadata:{name:'Old world harbor'}}));
    expect(JSON.parse(draft().value)).toEqual(harbor.metadata);expect(onSaved).toHaveBeenCalledTimes(1);
  });

  it('shows the frontend demo limitation without reading or simulating a successful save',async()=>{
    demo.enabled=true;await show('central');
    expect(dialog().textContent).toContain('The frontend demo cannot edit server stations.');
    expect(dialog().querySelector('textarea')).toBeNull();
    expect(api.stations).not.toHaveBeenCalled();expect(api.saveStationMetadata).not.toHaveBeenCalled();
  });

  it('shows errors and missing stations without constructing metadata from a display label',async()=>{
    vi.mocked(api.stations).mockRejectedValueOnce(new Error('Cannot read stations'));
    await show('central');expect(dialog().textContent).toContain('Cannot read stations');expect(dialog().querySelector('textarea')).toBeNull();
    vi.mocked(api.stations).mockResolvedValue({stations:[]});
    await click('Refresh stations (keep draft)');
    expect(dialog().textContent).toContain('This station does not exist.');expect(dialog().querySelector('textarea')).toBeNull();
    expect(api.saveStationMetadata).not.toHaveBeenCalled();
  });
});
