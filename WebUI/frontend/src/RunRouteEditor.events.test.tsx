// @vitest-environment jsdom
import {act} from 'react';
import {createRoot,type Root} from 'react-dom/client';
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import {RunRouteEditor} from './RunRouteEditor';
import {I18nProvider} from './i18n';
import {generateTimetableSource,type AuthoringRunV1,type AuthoringRunCallV1,type TimetableAuthoringDocumentV1} from './authoring';

vi.mock('./StationStopProgramEditor',()=>({StationStopProgramEditor:()=>null}));
const line={id:'L',metadata:{name:'Line'},upStationIds:['a','b','c'],downStationIds:['c','b','a'],maximumSpeedMetersPerSecond:20};
let container:HTMLDivElement,root:Root,run:AuthoringRunV1;
const changed=vi.fn();
async function render(){await act(async()=>root.render(<I18nProvider><RunRouteEditor automaticColor="#56c5ff" run={run} focusedCallId="b" lines={[line]} metadata={{stations:[{stationId:'b',berthId:'1',destinationId:'b@1'},{stationId:'b',berthId:'2',destinationId:'b@2'}]}} busy={false} onError={vi.fn()} onDelete={vi.fn()} onChange={(next,clearOverrides)=>{changed(next,clearOverrides);run=next;}}/></I18nProvider>));}
const toggle=()=>container.querySelector<HTMLButtonElement>('[role="switch"][aria-label="Override door side"]')!;
const select=()=>container.querySelector<HTMLInputElement>('[role="combobox"][aria-label="Overridden door side"]')!;
async function click(element:Element){await act(async()=>element.dispatchEvent(new MouseEvent('click',{bubbles:true})));}
function source(){const doc:TimetableAuthoringDocumentV1={formatVersion:1,id:'t',periodDays:1,runs:[run],duties:[{id:'d',selector:{type:'TRAIN_NAME',name:'train'},elements:[{id:'e',type:'RUN',runId:'r'}]}],initialPositions:[{trainName:'train',wayPointId:'a'}],diagramLines:[line],arrivalOverrides:[]};const generated=generateTimetableSource(doc,[line]);expect(generated.issues).toEqual([]);return generated.source;}
beforeEach(()=>{
  vi.stubGlobal('ResizeObserver',class {observe(){} unobserve(){} disconnect(){}});
  changed.mockClear();Object.assign(globalThis,{IS_REACT_ACT_ENVIRONMENT:true});
  Object.defineProperty(window,'matchMedia',{configurable:true,value:()=>({matches:false,addListener:()=>{},removeListener:()=>{},addEventListener:()=>{},removeEventListener:()=>{}})});
  container=document.createElement('div');document.body.append(container);root=createRoot(container);
  run={id:'r',type:'PASSENGER',lineId:'L',diagramLineId:'L',lineDirection:'UP',calls:[{id:'a',stationId:'a',targetId:'a',status:'ORIGIN',departureTick:100},{id:'b',stationId:'b',targetId:'b',status:'TERMINAL',arrivalTick:200}]};
});
afterEach(async()=>{await act(async()=>root.unmount());container.remove();});
describe('station call door side override',()=>{
  it('inherits until enabled, preserves explicit NONE and LEFT, then restores inheritance with null',async()=>{
    await render();expect(toggle().getAttribute('aria-checked')).toBe('false');expect(select().disabled).toBe(true);expect(container.textContent).toContain('Use stopping point definition');expect(source()).not.toContain('doorSide');
    await click(toggle());await render();expect(run.calls[1].doorSide).toBe('NONE');expect(toggle().getAttribute('aria-checked')).toBe('true');expect(select().disabled).toBe(false);expect(source()).toContain('\\"doorSide\\":\\"NONE\\"');
    await act(async()=>select().dispatchEvent(new MouseEvent('mousedown',{bubbles:true})));
    const left=[...document.querySelectorAll('.ant-select-item-option')].find(item=>item.textContent==='Left')!;expect(left).toBeDefined();await click(left);await render();expect(run.calls[1].doorSide).toBe('LEFT');expect(source()).toContain('\\"doorSide\\":\\"LEFT\\"');
    await click(toggle());await render();expect(run.calls[1].doorSide).toBeNull();expect(toggle().getAttribute('aria-checked')).toBe('false');expect(select().disabled).toBe(true);expect(source()).not.toContain('doorSide');expect(changed).toHaveBeenCalledTimes(3);
  });
  it.each(['NONE','LEFT','RIGHT','BOTH'] as const)('loads explicit %s as enabled',async side=>{
    run.calls[1].doorSide=side;await render();expect(toggle().getAttribute('aria-checked')).toBe('true');expect(select().disabled).toBe(false);expect(changed).not.toHaveBeenCalled();
  });
  it.each(['PASS','ORIGIN'] as const)('keeps override controls non-editable for %s',async status=>{
    run.calls[1]={...run.calls[1],status,doorSide:'NONE'} as AuthoringRunCallV1;await render();expect(toggle().disabled).toBe(true);expect(select().disabled).toBe(true);await click(toggle());expect(changed).not.toHaveBeenCalled();
  });
});

async function choose(label:string,text:string){
  const input=container.querySelector<HTMLInputElement>(`[role="combobox"][aria-label="${label}"]`)!;
  await act(async()=>input.dispatchEvent(new MouseEvent('mousedown',{bubbles:true})));
  const option=[...document.querySelectorAll('.ant-select-item-option')].find(item=>item.textContent===text)!;
  expect(option).toBeDefined();await click(option);await render();
}
async function typeNumber(input:HTMLInputElement,value:string){
  await act(async()=>{Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')!.set!.call(input,value);input.dispatchEvent(new Event('input',{bubbles:true}));});
}
const tickInput=(kind:'Arrival'|'Departure')=>container.querySelector<HTMLInputElement>(`input[aria-label="b · ${kind}"]`)!;
function withIntermediateStop(){
  run.calls[1]={...run.calls[1],status:'STOP',berthId:'1',targetId:'b@1',departureTick:250};
  run.calls.push({id:'terminal',stationId:'c',targetId:'c',status:'TERMINAL',arrivalTick:400});
}
describe('station call detail editing',()=>{
  it('shows effective input ticks and commits Enter plus blur once, then commits departure on blur',async()=>{
    withIntermediateStop();run.calls[1].arrivalTick=220;await render();
    expect(tickInput('Arrival').value).toBe('220');expect(tickInput('Departure').value).toBe('250');
    await typeNumber(tickInput('Arrival'),'225');expect(changed).not.toHaveBeenCalled();
    await act(async()=>tickInput('Arrival').dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true})));
    await render();await act(async()=>tickInput('Arrival').dispatchEvent(new FocusEvent('focusout',{bubbles:true})));
    expect(run.calls[1].arrivalTick).toBe(225);expect(changed).toHaveBeenCalledTimes(1);expect(changed.mock.calls[0][1]).toBe(true);
    await typeNumber(tickInput('Departure'),'270');expect(changed).toHaveBeenCalledTimes(1);
    await act(async()=>tickInput('Departure').dispatchEvent(new FocusEvent('focusout',{bubbles:true})));await render();
    expect(run.calls[1].departureTick).toBe(270);expect(changed).toHaveBeenCalledTimes(2);
    expect(container.textContent).not.toContain('Minimum dwell');
  });
  it('changes canonical berth destination and switches between stop and pass source semantics',async()=>{
    withIntermediateStop();await render();await choose('Berth','2');
    expect(run.calls[1]).toMatchObject({berthId:'2',targetId:'b@2'});expect(source()).toContain('.destination("b@2"');
    await choose('Call','Pass');expect(run.calls[1]).toMatchObject({status:'PASS',departureTick:null});expect(tickInput('Departure').disabled).toBe(true);expect(tickInput('Arrival').disabled).toBe(false);
    expect(source()).toContain('.pass("b@2")');expect(source()).not.toContain('.destination("b@2"');
    await choose('Call','Stop');expect(run.calls[1].status).toBe('STOP');expect(tickInput('Departure').disabled).toBe(false);
    await typeNumber(tickInput('Departure'),'250');await act(async()=>tickInput('Departure').dispatchEvent(new FocusEvent('focusout',{bubbles:true})));await render();
    expect(source()).toContain('.destination("b@2"');
  });
  it('keeps endpoint call methods fixed and origin arrival non-editable',async()=>{
    await render();expect(container.querySelector('[role="combobox"][aria-label="Call"]')).toBeNull();
    run.calls[1]={...run.calls[1],status:'ORIGIN'};await render();expect(tickInput('Arrival').disabled).toBe(true);expect(tickInput('Departure').disabled).toBe(false);expect(container.querySelector('[role="combobox"][aria-label="Call"]')).toBeNull();
  });
});
