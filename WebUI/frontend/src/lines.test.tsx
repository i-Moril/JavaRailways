// @vitest-environment jsdom
import {act} from 'react';
import {createRoot,type Root} from 'react-dom/client';
import {renderToStaticMarkup} from 'react-dom/server';
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import {LinesPage} from './LinesPage';
import {api} from './api';
import {I18nProvider} from './i18n';
import type {LineCollection,WorldMetadata} from './types';

vi.mock('./api',()=>({api:{stations:vi.fn(),saveStationMetadata:vi.fn(),saveLine:vi.fn(),deleteLine:vi.fn()}}));
vi.mock('./frontendDev',()=>({frontendDemoEnabled:false}));
vi.mock('./CommittedNumberInput',()=>({CommittedNumberInput:()=>null}));
vi.mock('./MetadataJsonEditor',()=>({MetadataJsonEditor:({draft,onChange,label='Metadata JSON',scope,fieldKey,disabled}:any)=><textarea aria-label={label} data-scope={scope} data-field-key={fieldKey} disabled={disabled} value={draft} onChange={()=>undefined} onInput={event=>onChange(event.currentTarget.value)}/>}));
vi.mock('antd',()=>{
  const Box=({children,role,title}:any)=><div role={role} title={title}>{children}</div>;
  return {Flex:Box,Space:Box,Typography:{Text:Box,Paragraph:Box,Title:Box},Spin:()=>null,Empty:({description}:any)=><p>{description}</p>,
    Alert:({title,description}:any)=><div role="alert">{title}{description}</div>,
    Button:({children,onClick,disabled,loading,'aria-label':label}:any)=><button disabled={disabled||loading} aria-label={label} onClick={onClick}>{children}</button>,
    Input:({value,onChange,disabled,type}:any)=><input value={value} disabled={disabled} type={type} onChange={onChange}/>,Select:()=>null,
    Modal:({children,title,footer,open,onCancel}:any)=><section role="dialog" hidden={!open}><h1>{title}</h1><button aria-label="Cancel" onClick={onCancel}>×</button>{children}{footer}</section>};
});
const run=async<T,>(operation:()=>Promise<T>):Promise<T|undefined>=>operation();
const metadata:WorldMetadata={stations:[
  {stationId:'central',stationName:'Central',berthId:'1',destinationId:'central@1'},
  {stationId:'central',stationName:'Central',berthId:'2',destinationId:'central@2'},
  {stationId:'harbor',stationName:'Harbor',berthId:'1',destinationId:'harbor@1'},
]};
const collection:LineCollection={revision:4,lines:[{
  id:'line-1',metadata:{name:'Line 1',custom:{keep:true}},color:'#1677ff',upStationIds:['central','harbor'],downStationIds:['harbor','central'],
},{id:'line-2',metadata:{name:'Line 2'},upStationIds:['central','harbor'],downStationIds:['harbor','central']}]};
let container:HTMLDivElement,root:Root;
const onCollection=vi.fn(),onStationSaved=vi.fn();
const page=(world='world')=><I18nProvider><LinesPage world={world} metadata={metadata} collection={collection} onCollection={onCollection} run={run} onStationSaved={onStationSaved}/></I18nProvider>;
const lineButton=()=>container.querySelector<HTMLButtonElement>('.detail-heading button')!;
const dialog=()=>container.querySelector<HTMLElement>('[role="dialog"]:not([hidden])')!;
const click=async(button:HTMLElement)=>act(async()=>button.click());
const edit=async(value:string)=>act(async()=>{const textarea=dialog().querySelector('textarea')!;textarea.value=value;textarea.dispatchEvent(new Event('input',{bubbles:true}));});
const close=()=>click([...dialog().querySelectorAll('button')].find(button=>button.textContent==='Close')!);
beforeEach(()=>{
  vi.clearAllMocks();vi.useFakeTimers();vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT',true);localStorage.setItem('javarailways.locale','en-US');
  vi.mocked(api.stations).mockResolvedValue({stations:[{id:'central',metadata:{name:'Server Central',custom:true}},{id:'harbor',metadata:{name:'Server Harbor'}}]});
  vi.mocked(api.saveStationMetadata).mockImplementation(async(_world,id,value)=>({id,metadata:value}));
  vi.mocked(api.saveLine).mockImplementation(async(_world,line,revision)=>({line,revision:revision+1}));
  container=document.createElement('div');document.body.append(container);root=createRoot(container);
});
afterEach(async()=>{await act(async()=>root.unmount());container.remove();vi.useRealTimers();vi.unstubAllGlobals();});

describe('line editor',()=>{
  it('offers metadata buttons in both station sequences without route validation',()=>{
    const html=renderToStaticMarkup(page());
    expect(html).toContain('Central');
    expect(html.match(/aria-label="Edit metadata: Central \(central\)"/g)).toHaveLength(2);
    expect(html.match(/aria-label="Edit metadata: Harbor \(harbor\)"/g)).toHaveLength(2);
    expect(html).not.toContain('Validate and preview route');
    expect(html).not.toContain('Line route is valid');
    expect(html).not.toContain('Line route is invalid');
  });

  it('does not expose a station-only route preview request',()=>{expect('previewLine' in api).toBe(false);});

  it('opens line metadata from its heading and retains invalid JSON after closing',async()=>{
    await act(async()=>root.render(page()));
    expect(container.querySelector('[role="dialog"]:not([hidden])')).toBeNull();
    expect(container.querySelector('.line-detail > textarea')).toBeNull();
    await click(lineButton());
    expect(dialog().querySelector('textarea')?.dataset.scope).toBe('line');
    expect(JSON.parse(dialog().querySelector('textarea')!.value)).toEqual(collection.lines[0].metadata);
    await edit('{ incomplete');await close();
    expect(container.querySelector('.line-detail > [role="alert"]')?.textContent).toBe('Invalid JSON syntax.');
    await act(async()=>vi.advanceTimersByTimeAsync(1500));expect(api.saveLine).not.toHaveBeenCalled();
    await click(container.querySelectorAll<HTMLButtonElement>('.entity-card')[1]);
    expect(container.querySelector('.entity-card.active')?.textContent).toContain('Line 1');
    await click(lineButton());expect(dialog().querySelector('textarea')?.value).toBe('{ incomplete');
    await edit('{"name":"Updated","unknown":[true,{"keep":"value"}]}');await close();
    await act(async()=>vi.advanceTimersByTimeAsync(1000));
    expect(api.saveLine).toHaveBeenCalledExactlyOnceWith('world',expect.objectContaining({id:'line-1',metadata:{name:'Updated',unknown:[true,{keep:'value'}]}}),4);
    expect(onCollection).toHaveBeenCalledWith(expect.objectContaining({revision:5}));
  });

  it('opens the same retained station draft from either direction using authoritative station data',async()=>{
    await act(async()=>root.render(page()));
    const buttons=[...container.querySelectorAll<HTMLButtonElement>('[aria-label="Edit metadata: Central (central)"]')];
    await click(buttons[0]);
    expect(JSON.parse(dialog().querySelector('textarea')!.value)).toEqual({name:'Server Central',custom:true});
    await edit('{"name":"Station draft","custom":true}');await close();
    await click(buttons[1]);expect(dialog().querySelector('textarea')?.value).toBe('{"name":"Station draft","custom":true}');
    await click([...dialog().querySelectorAll('button')].find(button=>button.textContent==='Save metadata')!);
    expect(api.saveStationMetadata).toHaveBeenCalledExactlyOnceWith('world','central',{name:'Station draft',custom:true},{name:'Server Central',custom:true});
    expect(onStationSaved).toHaveBeenCalledExactlyOnceWith({id:'central',metadata:{name:'Station draft',custom:true}});
    expect(api.saveLine).not.toHaveBeenCalled();
  });

  it('resets a line metadata draft and modal when the world changes',async()=>{
    await act(async()=>root.render(page()));await click(lineButton());await edit('{ old world');
    await act(async()=>root.render(page('other')));
    expect(container.querySelector('[role="dialog"]:not([hidden])')).toBeNull();
    await click(lineButton());
    expect(JSON.parse(dialog().querySelector('textarea')!.value)).toEqual(collection.lines[0].metadata);
    expect(dialog().querySelector('textarea')?.dataset.fieldKey).toBe('["other",0]');
  });
});
