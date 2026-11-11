// @vitest-environment jsdom
import {act,type ReactNode} from 'react';
import {createRoot,type Root} from 'react-dom/client';
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import {DutyEditor} from './DutyEditor';
import type {RunRouteEditor} from './RunRouteEditor';
import {api} from './api';
import {ApiError} from './apiErrors';
import {emptyAuthoringDocument,type TimetableAuthoringDocumentV1} from './authoring';
import {loadAuthoringRecovery,saveAuthoringRecovery} from './authoringRecovery';
import type {AuthoringCommandState} from './authoringCommands';
import {I18nProvider} from './i18n';
import type {LineSimulationResult,LineV1,WorldMetadata} from './types';

// Keep the actual document owner, history, generation, Reset and response guards. Only network
// and presentation surfaces are controlled; these tests do not claim browser/physics execution.
vi.mock('./api',()=>({api:{editorDocuments:vi.fn(),simulateLine:vi.fn()}}));
vi.mock('./frontendDev',()=>({frontendDemoEnabled:false}));
vi.mock('./theme',()=>({useTheme:()=>({theme:'light'})}));
vi.mock('./ProgramBlockEditor',()=>({getProgramBlockDraftValidity:()=>true}));
vi.mock('./Graph',()=>({Graph:()=>null}));
vi.mock('./DutyManagement',()=>({DutyManagement:()=>null}));
vi.mock('./AuthoringValidation',()=>({AuthoringValidation:()=>null}));
vi.mock('./TimetableEventsEditor',()=>({TimetableEventsEditor:()=>null}));
vi.mock('./TimedOperationFields',()=>({TimedOperationFields:()=>null}));
vi.mock('./TimetableWorkspace',()=>({TimetableWorkspace:({inspector}:{inspector:ReactNode})=><>{inspector}</>}));
vi.mock('./RunRouteEditor',()=>({RunRouteEditor:(props:Parameters<typeof RunRouteEditor>[0])=>{
  route=props;return <>{props.tools}<output data-testid="calls">{JSON.stringify(props.run.calls)}</output></>;
}}));
vi.mock('./SimulationSetupDialog',()=>({SimulationSetupDialog:({onConfirm}:{onConfirm:(value:number)=>void})=>
  <button onClick={()=>onConfirm(60000)}>Confirm measurement</button>}));
vi.mock('./CommittedNumberInput',()=>({CommittedNumberInput:({value}:{value:number})=><span>{value}</span>}));
vi.mock('antd',()=>{
  const Box=({children}:{children?:ReactNode})=><div>{children}</div>;
  return {Flex:Box,Space:Box,Tag:Box,Tooltip:Box,Popover:Box,Dropdown:Box,Segmented:()=>null,
    Typography:{Text:Box,Title:Box},Modal:()=>null,Input:()=>null,InputNumber:()=>null,
    Button:({children,onClick,disabled}:{children?:ReactNode;onClick?:()=>void;disabled?:boolean})=><button onClick={onClick} disabled={disabled}>{children}</button>,
    Select:({value,onChange,options}:{value?:string;onChange:(value:string)=>void;options:{value:string;label:ReactNode}[]})=>
      <select value={value??''} onChange={event=>onChange(event.target.value)}><option value=""/>{options.map(option=><option key={option.value} value={option.value}>{option.label}</option>)}</select>,
  };
});

const lines:LineV1[]=[{id:'line',displayName:'Line',maximumSpeedMetersPerSecond:20,upStationIds:['a','b','c'],downStationIds:['c','b','a']}];
const metadata:WorldMetadata={simulationProfiles:[{type:'TRAIN_TEMPLATE',id:'emu',displayName:'EMU',carCount:1}]};
const fixture=():TimetableAuthoringDocumentV1=>({...emptyAuthoringDocument(),id:'timetable',minimumDwellTicks:40,
  diagramLines:[{id:'line',upStationIds:['a','b','c'],downStationIds:['c','b','a']}],runs:[{
    id:'run',type:'PASSENGER',lineId:'line',diagramLineId:'line',lineDirection:'UP',calls:[
      {id:'a',stationId:'a',targetId:'a@1',status:'ORIGIN',departureTick:100},
      {id:'b',stationId:'b',targetId:'b@1',status:'STOP',arrivalTick:250,departureTick:450},
      {id:'c',stationId:'c',targetId:'c@1',status:'TERMINAL',arrivalTick:600},
    ],
  }]});
const complete:LineSimulationResult={lineRevision:1,totalTravelSeconds:10,calls:[
  {callId:'a',kind:'ORIGIN',departureTick:100},
  {callId:'b',kind:'STOP',arrivalTick:200,departureTick:400},
  {callId:'c',kind:'TERMINAL',arrivalTick:500},
],legs:[
  {fromStationId:'a',toStationId:'b',lengthMeters:100,travelSeconds:5,peakSpeedKmh:30,stoppingErrorMeters:0},
  {fromStationId:'b',toStationId:'c',lengthMeters:100,travelSeconds:5,peakSpeedKmh:30,stoppingErrorMeters:0},
]};
const deferred=()=>{
  let resolve!:(value:LineSimulationResult)=>void,reject!:(error:Error)=>void;
  const promise=new Promise<LineSimulationResult>((done,fail)=>{resolve=done;reject=fail;});
  return {promise,resolve,reject};
};
let container:HTMLDivElement,root:Root,route:Parameters<typeof RunRouteEditor>[0],commands:AuthoringCommandState;
const onError=vi.fn();
const click=async(text:string)=>{
  const button=[...container.querySelectorAll('button')].find(candidate=>candidate.textContent===text);
  expect(button,`missing button: ${text}`).toBeDefined();
  expect(button!.disabled).toBe(false);
  await act(async()=>button!.click());
};
const currentDocument=()=>{
  window.dispatchEvent(new Event('beforeunload'));
  return loadAuthoringRecovery('tester','world')!.document;
};
const start=async()=>{
  await act(async()=>root.render(<I18nProvider><DutyEditor world="world" recoveryOwner="tester" lines={lines} metadata={metadata}
    onError={onError} onCommandState={value=>{commands=value;}}/></I18nProvider>));
  const profile=[...container.querySelectorAll('select')].find(select=>[...select.options].some(option=>option.value==='TRAIN_TEMPLATE:emu'))!;
  expect(profile).toBeDefined();
  await act(async()=>{profile.value='TRAIN_TEMPLATE:emu';profile.dispatchEvent(new Event('change',{bubbles:true}));});
  onError.mockClear();
  const reset=[...container.querySelectorAll('button')].find(button=>button.textContent?.includes('Reset'))!;
  expect(reset).toBeDefined();expect(reset.disabled).toBe(false);
  await act(async()=>reset.click());
  await click('Confirm measurement');
  expect(api.simulateLine).toHaveBeenCalledExactlyOnceWith('world','line',expect.objectContaining({firstDepartureTick:100,fixedDwellTicks:200,minimumDwellTicks:40}),60000);
};
beforeEach(()=>{
  localStorage.clear();localStorage.setItem('javarailways.locale','en-US');vi.clearAllMocks();
  vi.mocked(api.editorDocuments).mockResolvedValue([]);
  saveAuthoringRecovery({owner:'tester',world:'world',documentId:'',formalScriptName:'test.js',persistedFormalScriptName:'',
    document:fixture(),savedDocumentJson:'{}',selectedRun:'run',selectedDuty:'',sidebarTab:'runs',graphLineId:'line',profileAssignments:{},delayThresholdTicks:1200});
  Object.assign(globalThis,{IS_REACT_ACT_ENVIRONMENT:true});
  container=document.createElement('div');document.body.append(container);root=createRoot(container);
});
afterEach(async()=>{await act(async()=>root.unmount());container.remove();});

describe('mounted DutyEditor line Reset ownership',()=>{
  it('discards a pending result after edit then Undo returns to identical document JSON',async()=>{
    const pending=deferred();vi.mocked(api.simulateLine).mockReturnValue(pending.promise);
    await start();const before=JSON.stringify(currentDocument());
    // Exercise actual controlled-child callbacks, not a copy of the owner/history implementation.
    // Ordinary busy controls are disabled; this intentionally injects an intervening owner edit.
    await act(async()=>route.onChange({...route.run,color:'#123456'}));
    expect(JSON.stringify(currentDocument())).not.toBe(before);
    await act(async()=>route.onUndo?.());
    expect(JSON.stringify(currentDocument())).toBe(before);
    await act(async()=>pending.resolve(complete));
    expect(JSON.stringify(currentDocument())).toBe(before);
    expect(commands.undo).not.toBe('');expect(commands.redo).toBe('');
    expect(onError.mock.calls.filter(([message])=>message)).toEqual([]);
  });

  it.each([new Error('measurement failed'),new ApiError(504,'SIMULATION_TIMEOUT','line simulation timed out')])(
    'does not modify the document or history when measurement rejects: %s',async error=>{
      const pending=deferred();vi.mocked(api.simulateLine).mockReturnValue(pending.promise);
      await start();const before=JSON.stringify(currentDocument());
      await act(async()=>pending.reject(error));
      expect(JSON.stringify(currentDocument())).toBe(before);
      expect(commands.undo).not.toBe('');expect(commands.redo).not.toBe('');
      expect(onError).toHaveBeenLastCalledWith(error.message);
    });

  it('applies a current complete result once and one Undo restores the original document',async()=>{
    const pending=deferred();vi.mocked(api.simulateLine).mockReturnValue(pending.promise);
    await start();const before=JSON.stringify(currentDocument());
    await act(async()=>pending.resolve(complete));
    expect(route.run.calls.map(call=>[call.arrivalTick,call.departureTick])).toEqual([[null,100],[200,400],[500,null]]);
    expect(commands.undo).toBe('');expect(commands.redo).not.toBe('');
    await act(async()=>route.onUndo?.());
    expect(JSON.stringify(currentDocument())).toBe(before);
    expect(commands.undo).not.toBe('');expect(commands.redo).toBe('');
  });
});


it('keeps invalid run JSON in the document owner and blocks saving, compiling and publishing until repaired',async()=>{
 await act(async()=>root.render(<I18nProvider><DutyEditor world="world" recoveryOwner="tester" lines={lines} metadata={metadata} onError={onError} onCommandState={value=>{commands=value;}}/></I18nProvider>));
 const before=JSON.stringify(currentDocument());
 await act(async()=>route.onMetadataDraftChange?.('{"notice":'));
 expect(route.metadataDraft).toBe('{"notice":');expect(commands.save).not.toBe('');expect(commands.compile).not.toBe('');expect(commands.publish).not.toBe('');
 expect(JSON.stringify(currentDocument())).toBe(before);
 await act(async()=>route.onChange({...route.run,color:'#123456'}));
 expect(route.metadataDraft).toBe('{"notice":');expect(commands.save).not.toBe('');
 await act(async()=>route.onMetadataDraftChange?.('{"notice":"Express","transfers":["branch"]}'));
 expect(route.run.metadata).toEqual({notice:'Express',transfers:['branch']});expect(commands.save).toBe('');
 await act(async()=>route.onUndo?.());
 expect(route.run.metadata).toBeUndefined();expect(JSON.parse(route.metadataDraft!)).toEqual({});
});
