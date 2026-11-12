// @vitest-environment jsdom
import {act} from 'react';
import {createRoot,type Root} from 'react-dom/client';
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import {Graph} from './Graph';
import {TimetableWorkspace} from './TimetableWorkspace';
import {I18nProvider} from './i18n';
import {ThemeProvider} from './theme';
import {graphServiceColor,graphServiceColors} from './graph';
import type {SavedTimetableV1} from './types';
import type {AuthoringRunV1} from './authoring';

const ids=['first','second','third'];
const run:AuthoringRunV1={id:'second',type:'PASSENGER',lineId:'infrastructure',diagramLineId:'diagram',lineDirection:'UP',calls:[
  {id:'a',stationId:'a',targetId:'a@1',status:'ORIGIN',departureTick:100},
  {id:'b',stationId:'b',targetId:'b@1',status:'TERMINAL',arrivalTick:200},
]};
const value:SavedTimetableV1={id:'t',periodDays:1,diagramLines:[{id:'diagram',upStationIds:['a','b'],downStationIds:['b','a']}],services:[{
  id:'second',diagramLineId:'diagram',selectorType:'TRAIN_ID',activation:{type:'SCHEDULED'},steps:[
    {type:'CHECK',wayPointId:'a@1'},
    {type:'AT',tick:100,action:{type:'STATION_DESTINATION_OPTIONS',stationId:'b',berthId:'1',arrivalTick:200,wayPointIds:[],requiredTags:[],requiredCapabilities:[],stablingPointIds:[],thenSteps:[],elseSteps:[]}},
    {type:'AT',tick:100,action:{type:'DESTINATION',wayPointIds:['b@1'],requiredTags:[],requiredCapabilities:[],stablingPointIds:[],thenSteps:[],elseSteps:[]}},
  ],
}]};
const metadata={stations:[{stationId:'a',berthId:'1',destinationId:'a@1'},{stationId:'b',berthId:'1',destinationId:'b@1'}]};
let container:HTMLDivElement,root:Root;
beforeEach(()=>{
  Object.assign(globalThis,{IS_REACT_ACT_ENVIRONMENT:true});
  vi.stubGlobal('ResizeObserver',class {observe(){} unobserve(){} disconnect(){}});
  Object.defineProperty(window,'matchMedia',{configurable:true,value:()=>({matches:false,addListener:()=>{},removeListener:()=>{},addEventListener:()=>{},removeEventListener:()=>{}})});
  localStorage.clear();container=document.createElement('div');document.body.append(container);root=createRoot(container);
});
afterEach(async()=>{await act(async()=>root.unmount());container.remove();vi.unstubAllGlobals();});

describe('graph station point interactions',()=>{
  it('keeps a generous invisible hit target, previews only on hover/focus and preserves click and keyboard selection',async()=>{
    const select=vi.fn(),selectPoint=vi.fn();
    await act(async()=>root.render(<I18nProvider><ThemeProvider><Graph value={value} metadata={metadata} graphLineId="diagram" serviceColorIds={ids} highlightedServiceIds={new Set(['second'])} onSelect={select} onSelectPoint={selectPoint}/></ThemeProvider></I18nProvider>));
    const target=container.querySelector<SVGCircleElement>('[data-graph-point]')!;
    expect(target).not.toBeNull();expect(target.getAttribute('fill')).toBe('transparent');expect(target.getAttribute('stroke')).toBe('none');expect(Number(target.getAttribute('r'))).toBeGreaterThanOrEqual(12);
    expect(target.getAttribute('role')).toBe('button');expect(target.getAttribute('tabindex')).toBe('0');expect(target.querySelector('title')?.textContent).toContain('Departure');
    expect(container.querySelector('[data-graph-point-preview]')).toBeNull();
    await act(async()=>target.dispatchEvent(new MouseEvent('mouseover',{bubbles:true})));
    expect(container.querySelectorAll('[data-graph-point-preview]')).toHaveLength(1);
    await act(async()=>target.dispatchEvent(new MouseEvent('mouseout',{bubbles:true})));
    expect(container.querySelector('[data-graph-point-preview]')).toBeNull();
    await act(async()=>target.dispatchEvent(new MouseEvent('click',{bubbles:true})));
    expect(select).toHaveBeenCalledWith(0,2);expect(selectPoint).toHaveBeenCalledWith(expect.objectContaining({kind:'DEPARTURE',tick:100}),expect.any(Object));
    vi.spyOn(target,'matches').mockReturnValue(true);
    await act(async()=>target.dispatchEvent(new FocusEvent('focusin',{bubbles:true})));
    expect(container.querySelectorAll('[data-graph-point-preview]')).toHaveLength(1);
    await act(async()=>target.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true})));
    await act(async()=>target.dispatchEvent(new KeyboardEvent('keydown',{key:' ',bubbles:true})));
    expect(select).toHaveBeenCalledTimes(3);expect(selectPoint).toHaveBeenCalledTimes(3);
    await act(async()=>target.dispatchEvent(new FocusEvent('focusout',{bubbles:true})));
    expect(container.querySelector('[data-graph-point-preview]')).toBeNull();
  });
});

describe('pointer focus on graph points',()=>{
  it('removes preview after pointer focus and mouse leave, but allows subsequent keyboard activation',async()=>{
    const selectPoint=vi.fn();
    await act(async()=>root.render(<I18nProvider><ThemeProvider><Graph value={value} metadata={metadata} graphLineId="diagram" onSelectPoint={selectPoint}/></ThemeProvider></I18nProvider>));
    const target=container.querySelector('[data-graph-point]')!;
    // Real browsers give clicked SVG buttons focus without :focus-visible.
    vi.spyOn(target,'matches').mockReturnValue(false);
    await act(async()=>target.dispatchEvent(new MouseEvent('mouseover',{bubbles:true})));
    expect(container.querySelector('[data-graph-point-preview]')).not.toBeNull();
    await act(async()=>target.dispatchEvent(new FocusEvent('focusin',{bubbles:true})));
    await act(async()=>target.dispatchEvent(new MouseEvent('click',{bubbles:true})));
    await act(async()=>target.dispatchEvent(new MouseEvent('mouseout',{bubbles:true})));
    expect(selectPoint).toHaveBeenCalledOnce();
    expect(container.querySelector('[data-graph-point-preview]')).toBeNull();
    expect(container.querySelector('.graph-point-tooltip')).toBeNull();
    await act(async()=>target.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true})));
    expect(container.querySelector('[data-graph-point-preview]')).not.toBeNull();
    expect(selectPoint).toHaveBeenCalledTimes(2);
  });
});

describe('graph preview identity',()=>{
  it.each(['hover','focus'] as const)('clears %s preview when filtered services reuse an index and location',async interaction=>{
    const render=async(timetable:SavedTimetableV1)=>act(async()=>root.render(<I18nProvider><ThemeProvider><Graph value={timetable} metadata={metadata} graphLineId="diagram" serviceColorIds={ids} onSelectPoint={vi.fn()}/></ThemeProvider></I18nProvider>));
    await render(value);
    const target=container.querySelector('[data-graph-point]')!;
    vi.spyOn(target,'matches').mockReturnValue(true);
    await act(async()=>target.dispatchEvent(interaction==='hover'?new MouseEvent('mouseover',{bubbles:true}):new FocusEvent('focusin',{bubbles:true})));
    expect(container.querySelectorAll('[data-graph-point-preview]')).toHaveLength(1);
    // Change only service identity: source index, step address, station and times all collide.
    await render({...value,services:[{...value.services[0],id:'third'}]});
    expect(container.querySelectorAll('[data-graph-point]')).toHaveLength(2);
    expect(container.querySelector('[data-graph-point-preview]')).toBeNull();
    expect(container.querySelector('.graph-point-tooltip')).toBeNull();
    await render(value);
    expect(container.querySelector('[data-graph-point-preview]')).toBeNull();
  });
});

describe('graph blank-canvas focus',()=>{
  it('clears the selected run on a blank click without clearing it on a pan drag',async()=>{
    const clear=vi.fn();
    await act(async()=>root.render(<I18nProvider><ThemeProvider><Graph value={value} metadata={metadata} graphLineId="diagram" serviceColorIds={ids} highlightedServiceIds={new Set(['second'])} onClearSelection={clear}/></ThemeProvider></I18nProvider>));
    const wrap=container.querySelector<HTMLElement>('.graph-wrap')!;
    Object.defineProperties(wrap,{setPointerCapture:{configurable:true,value:vi.fn()},releasePointerCapture:{configurable:true,value:vi.fn()}});
    const pointer=(type:string,x:number,y:number)=>new MouseEvent(type,{bubbles:true,button:0,clientX:x,clientY:y});
    await act(async()=>wrap.dispatchEvent(pointer('pointerdown',10,10)));
    await act(async()=>wrap.dispatchEvent(pointer('pointerup',10,10)));
    expect(clear).toHaveBeenCalledTimes(1);
    await act(async()=>wrap.dispatchEvent(pointer('pointerdown',10,10)));
    await act(async()=>wrap.dispatchEvent(pointer('pointermove',30,30)));
    await act(async()=>wrap.dispatchEvent(pointer('pointerup',30,30)));
    expect(clear).toHaveBeenCalledTimes(1);
  });
});

describe('run library and graph colors',()=>{
  it.each(['dark','light'] as const)('uses the same stable document palette color in %s despite filtering and different diagram/infrastructure IDs',async theme=>{
    localStorage.setItem('javarailways.theme',theme);
    await act(async()=>root.render(<I18nProvider><ThemeProvider><TimetableWorkspace lines={[{id:'infrastructure',metadata:{name:'Line'},color:'#123456',upStationIds:['a','b'],downStationIds:['b','a']}]} activeLineId="diagram" runs={[run]} serviceColorIds={ids} selectedRunId="second" search="second" direction="UP" onLineChange={vi.fn()} onSelectRun={vi.fn()} onAddRun={vi.fn()} onSearchChange={vi.fn()} onDirectionChange={vi.fn()} assignmentFor={()=>undefined} inspector={null} graphPreviewActive={false} saved graph={<Graph value={value} metadata={metadata} graphLineId="diagram" serviceColorIds={ids}/>}/></ThemeProvider></I18nProvider>));
    const color=graphServiceColors(theme)[1];
    const stripe=container.querySelector<HTMLElement>('.line-stripe')!;
    const comparison=document.createElement('span');comparison.style.background=color;
    expect(stripe.style.background).toBe(comparison.style.background);
    expect(container.querySelector('polyline')?.getAttribute('stroke')).toBe(color);
  });
  it('preserves explicit line colors and the existing theme palette order',()=>{
    expect(graphServiceColor('dark','second',ids,'#123456')).toBe('#123456');
    expect(graphServiceColor('light','third',ids)).toBe(graphServiceColors('light')[2]);
  });
});
