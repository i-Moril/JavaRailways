// @vitest-environment jsdom
import {act} from 'react';
import {createRoot,type Root} from 'react-dom/client';
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import {TimedOperationFields} from './TimedOperationFields';
import {I18nProvider} from './i18n';
import type {BoundStationProgram,StationProgramRecord,TimedOperation} from './types';

const library:StationProgramRecord[]=[{formatVersion:1,id:'1',name:'Program',revision:2,contentIdentity:'new',estimatedDurationTicks:80,definition:{parameters:[],steps:[]}}];
const bindings:BoundStationProgram[]=[{programId:'1',revision:1,contentIdentity:'old',definition:{parameters:[],steps:[]}}];
const operation:TimedOperation={id:'a',tick:100,program:{programId:'1',arguments:{}},duration:{type:'PROGRAM'}};
let container:HTMLDivElement,root:Root;
beforeEach(()=>{
  Object.assign(globalThis,{IS_REACT_ACT_ENVIRONMENT:true});
  vi.stubGlobal('ResizeObserver',class {observe(){} unobserve(){} disconnect(){}});
  container=document.createElement('div');document.body.append(container);root=createRoot(container);
});
afterEach(async()=>{await act(async()=>root.unmount());container.remove();vi.unstubAllGlobals();});

describe('timed operation adoption action',()=>{
  it('adopts the latest library revision for the bound program',async()=>{
    const adopt=vi.fn();
    await act(async()=>root.render(<I18nProvider><TimedOperationFields operation={operation} onChange={()=>{}} fieldScope="qa" stationIds={['a']} stationNames={new Map()} library={library} bindings={bindings} onAdoptProgram={adopt}/></I18nProvider>));
    const button=[...container.querySelectorAll('button')].find((candidate)=>candidate.textContent?.includes('Adopt new revision r2'));
    expect(button).toBeDefined();
    await act(async()=>button!.dispatchEvent(new MouseEvent('click',{bubbles:true})));
    expect(adopt).toHaveBeenCalledWith('1');
  });
});
