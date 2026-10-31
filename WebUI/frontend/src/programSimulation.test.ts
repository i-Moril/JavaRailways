import {describe,expect,it} from 'vitest';
import {buildProgramSimulationRequest,completedProgramDuration,ProgramSimulationRequestOwner} from './programSimulation';
import type {ProgramSimulationRequest,ProgramSimulationResult,StationProgramLiteral,WorldMetadata} from './types';
const metadata:WorldMetadata={wayPoints:['undirected'],programSimulationWayPoints:[{id:'point',railId:'rail',direction:'BA',directionSource:'WAYPOINT'}],simulationProfiles:[{type:'WORLD_TRAIN',id:'train',displayName:'Train',carCount:2}]};
const request=():ProgramSimulationRequest=>({programId:'1',definition:{parameters:[{id:'duration',type:'DURATION'}],steps:[{id:'wait',type:'WAIT_DURATION',durationTicks:{parameterId:'duration'}}]},arguments:{duration:100},startTick:1200,profile:{type:'WORLD_TRAIN',id:'train'},initialPosition:{wayPointId:'point',reverseTrain:false},executionTimeoutMillis:60000,maximumDurationTicks:2000});
describe('standalone program simulation contract',()=>{
 it('sends the actual accepted definition and arguments, detached without library lookup or live-state cloning',()=>{
  const input=request(),built=buildProgramSimulationRequest(input,metadata);expect(built).toEqual(input);expect(built.definition).not.toBe(input.definition);input.arguments.duration=999;expect(built.arguments.duration).toBe(100);expect(built).not.toHaveProperty('trainState');expect(built).not.toHaveProperty('plannedContext');
 });
 it('only accepts directed metadata points and explicit keep or in-place reversal choices',()=>{
  const input=request();for(const reverseTrain of [false,true])expect(buildProgramSimulationRequest({...input,initialPosition:{wayPointId:'point',reverseTrain}},metadata).initialPosition.reverseTrain).toBe(reverseTrain);
  expect(()=>buildProgramSimulationRequest({...input,initialPosition:{wayPointId:'undirected',reverseTrain:false}},metadata)).toThrow('explicit direction');
  for(const reverseTrain of [undefined,'true','false',0,1])expect(()=>buildProgramSimulationRequest({...input,initialPosition:{wayPointId:'point',reverseTrain:reverseTrain as never}},metadata)).toThrow('explicitly');
  expect(()=>buildProgramSimulationRequest(input,{...metadata,programSimulationWayPoints:[]})).toThrow('explicit direction');
 });
 it('validates exact vehicle catalog identity, time and bounded positive limit',()=>{
  for(const patch of [{profile:{type:'TRAIN_TEMPLATE' as const,id:'train'}},{startTick:Number.MAX_SAFE_INTEGER},{startTick:-1},{maximumDurationTicks:0},{maximumDurationTicks:Number.MAX_SAFE_INTEGER}])expect(()=>buildProgramSimulationRequest({...request(),...patch},metadata)).toThrow();
 });
 it('does not clip standalone functions or absolute document anchors at a timetable day',()=>{
  const input:ProgramSimulationRequest={...request(),startTick:23900,maximumDurationTicks:48000};
  const built=buildProgramSimulationRequest(input,metadata);expect(built.maximumDurationTicks).toBe(48000);expect(built).not.toHaveProperty('periodDays');
  input.definition={parameters:[],steps:[{id:'event',type:'WAIT_EVENT',eventId:'later',offsetTicks:0}]};input.arguments={};
  expect(buildProgramSimulationRequest(input,metadata,[{id:'later',tick:120000}]).definition.steps[0]).toMatchObject({type:'WAIT_UNTIL',tick:{value:120000}});
 });
 it('requires an integer wall-clock budget separately from the tick horizon',()=>{
  for(const executionTimeoutMillis of [undefined,0,3600001,1.5,'1000'])expect(()=>buildProgramSimulationRequest({...request(),executionTimeoutMillis:executionTimeoutMillis as number},metadata)).toThrow('execution timeout');
  for(const executionTimeoutMillis of [1,1001,3600000])expect(buildProgramSimulationRequest({...request(),executionTimeoutMillis},metadata).executionTimeoutMillis).toBe(executionTimeoutMillis);
 });
 it('rejects missing, mistyped and unknown arguments before requesting execution',()=>{
  for(const args of [{},{duration:'wrong'},{duration:100,other:1}] as Record<string,StationProgramLiteral>[])expect(()=>buildProgramSimulationRequest({...request(),arguments:args},metadata)).toThrow('argument');
 });
 it('resolves document event waits recursively from the exact anchors and period',()=>{
  const input=request();input.definition={parameters:[],steps:[{id:'branch',type:'BRANCH',typeId:'javarailways:plan_duty_id',version:1,parameters:{value:{values:['duty']}},thenSteps:[{id:'event',type:'WAIT_EVENT',eventId:'anchor',offsetTicks:-20}],elseSteps:[]}]};input.arguments={};input.plannedContext={dutyId:'duty'};
  const built=buildProgramSimulationRequest(input,metadata,[{id:'anchor',tick:2000}]);expect(built.definition.steps[0]).toMatchObject({thenSteps:[{id:'event',type:'WAIT_UNTIL',tick:{value:1980}}]});expect(built.plannedContext).toEqual({dutyId:'duty'});expect(input.definition.steps[0]).toMatchObject({thenSteps:[{type:'WAIT_EVENT'}]});
  expect(()=>buildProgramSimulationRequest(input,metadata)).toThrow('Unknown event');expect(()=>buildProgramSimulationRequest(input,metadata,[{id:'anchor',tick:10}])).toThrow('outside timetable period');
 });
 it('only accepts a coherent successful task measurement, including a real zero',()=>{
  const result:ProgramSimulationResult={status:'COMPLETED',simulatedTicks:1350,startTick:1200,endTick:1300,durationTicks:100,initialPosition:{wayPointId:'point',direction:'BA',directionSource:'WAYPOINT',reverseTrain:true}};
  expect(completedProgramDuration(result)).toBe(100);expect(completedProgramDuration({...result,endTick:1200,durationTicks:0})).toBe(0);
  for(const patch of [{status:'FAILED' as const},{status:'LIMIT_REACHED' as const},{endTick:undefined},{durationTicks:99},{endTick:1100,durationTicks:-100},{startTick:1200.5},{startTick:-1,endTick:99}])expect(completedProgramDuration({...result,...patch})).toBeUndefined();
 });
});
describe('simulation request ownership',()=>{
 it('rejects pending successes and failures after A to B to A, raw edits, another request and disposal',()=>{
  const owner=new ProgramSimulationRequestOwner();owner.update('A');const a=owner.begin();expect(owner.current(a)).toBe(true);owner.update('B');owner.update('A');expect(owner.current(a)).toBe(false);
  const second=owner.begin();owner.invalidate();expect(owner.current(second)).toBe(false);const third=owner.begin(),fourth=owner.begin();expect(owner.current(third)).toBe(false);expect(owner.current(fourth)).toBe(true);owner.dispose();expect(owner.current(fourth)).toBe(false);
 });
 it('supports React strict effect cleanup and setup without reviving old requests',()=>{
  const owner=new ProgramSimulationRequestOwner(),old=owner.begin();owner.dispose();owner.activate();expect(owner.current(old)).toBe(false);expect(owner.current(owner.begin())).toBe(true);
 });
});
