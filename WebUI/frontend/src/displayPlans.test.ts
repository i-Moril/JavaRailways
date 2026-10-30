import {describe,expect,it} from 'vitest';
import {authoringDocumentForSave,generateTimetableSource,type TimetableAuthoringDocumentV1} from './authoring';
import {attachDisplayPlanRun,createDisplayPlan,displayPlanRuns,displayPlansForRun,moveDisplayPlanLeg,orphanDisplayPlans,removeDisplayPlan,removeDisplayPlanLeg,updateDisplayPlanDefaults,updateDisplayPlanLeg} from './displayPlans';
import {createDocumentHistory,redoDocumentHistory,undoDocumentHistory,updateDocumentHistory} from './documentHistory';
import {parseAuthoringRecovery} from './authoringRecovery';
import type {OperationalTripSegment} from './types';

const first:OperationalTripSegment={serviceId:'1',runId:'1',unitSlots:[1]},second:OperationalTripSegment={serviceId:'2',runId:'2',unitSlots:[1]};
function document():TimetableAuthoringDocumentV1{return {
  formatVersion:1,id:'weekday',periodDays:1,formalScriptName:'weekday.js',arrivalOverrides:[],diagramLines:[],
  runs:[1,2,3].map((id)=>({id:String(id),lineId:'main',type:'PASSENGER' as const,lineDirection:'UP' as const,calls:[
    {id:'a',stationId:'a',status:'ORIGIN' as const,targetId:'a@1',departureTick:id*100},
    {id:'b',stationId:'b',status:'TERMINAL' as const,targetId:'b@1',arrivalTick:id*100+50},
  ]})),duties:[
    {id:'1',selector:{type:'TRAIN_NAME',name:'train'},elements:[{id:'a',type:'RUN',runId:'1'}]},
    {id:'2',selector:{type:'TRAIN_NAME',name:'other'},elements:[{id:'b',type:'RUN',runId:'2'}]},
  ],events:[{id:'event',tick:500}],boundPrograms:[],stationPrograms:[],
};}
const lines=[{id:'main',displayName:'Main',upStationIds:['a','b'],downStationIds:['b','a'],maximumSpeedMetersPerSecond:20}];

describe('timeline-owned continuous display plans',()=>{
  it('views existing IDs defaults and leg order without rewriting any document field',()=>{
    const d=document();d.operationalTrips=[{id:'existing',defaults:{destination:'E',nested:{flag:true}},segments:[second,first]}];
    const serialized=JSON.stringify(d),source=generateTimetableSource(d,lines);
    expect(displayPlanRuns(d)).toEqual([{serviceId:'1',runId:'1',label:'1 / 1'},{serviceId:'2',runId:'2',label:'2 / 2'}]);
    expect(displayPlansForRun(d,'1','1')).toEqual(d.operationalTrips);expect(displayPlansForRun(d,'2','1')).toEqual([]);
    expect(orphanDisplayPlans(d)).toEqual([]);
    expect(JSON.stringify(d)).toBe(serialized);expect(generateTimetableSource(d,lines)).toEqual(source);
  });
  it('allocates numeric IDs only in display-plan namespace and isolates copied inputs',()=>{
    const d=document();d.operationalTrips=[{id:'old-name',defaults:{},segments:[]},{id:'0009',defaults:{},segments:[]}];
    const leg={...first,unitSlots:[2,1]},next=createDisplayPlan(d,leg);leg.unitSlots.push(3);
    expect(next.operationalTrips?.map((plan)=>plan.id)).toEqual(['old-name','0009','10']);
    expect(next.operationalTrips?.at(-1)?.segments).toEqual([{...first,unitSlots:[2,1]}]);
    expect(d.operationalTrips).toHaveLength(2);
    for(const key of ['runs','duties','events','boundPrograms','stationPrograms','diagramLines','arrivalOverrides'] as const)expect(next[key]).toBe(d[key]);
    const large={...d,operationalTrips:[{id:'9007199254740993',defaults:{},segments:[]}]};
    expect(createDisplayPlan(large,first).operationalTrips?.at(-1)?.id).toBe('9007199254740994');
  });
  it('adds across duties after formation without changing vehicle ownership or event references',()=>{
    const d=document();d.formationEvents=[{id:'split',tick:180,kind:'SPLIT',coordinatorDutyId:'1',partition:{from:'HEAD',firstUnitCount:1},outputs:[
      {id:'first',group:'FIRST',disposition:{type:'DUTY',dutyId:'1'}},{id:'rest',group:'REST',disposition:{type:'DUTY',dutyId:'2'}}]}];
    d.duties[0].elements.push({id:'split',type:'FORMATION',eventId:'split'});d.duties[1].selector={type:'TAKE_FROM_FORMATION_OUTPUT',eventId:'split',outputId:'rest'};
    const created=createDisplayPlan(d,first),attached=attachDisplayPlanRun(created,'1',second);
    expect(attached.operationalTrips?.[0].segments).toEqual([first,second]);
    expect(attached.formationEvents).toBe(d.formationEvents);expect(attached.duties).toBe(d.duties);
    const defaults={destination:'Final',nested:{one:1}},updated=updateDisplayPlanDefaults(attached,'1',defaults);defaults.nested.one=2;
    expect(updated.operationalTrips?.[0].defaults).toEqual({destination:'Final',nested:{one:1}});
    expect(attached.operationalTrips?.[0].defaults).toEqual({});
  });
  it('rejects unassigned or missing runs and wrong duty membership',()=>{
    const d=document();d.duties[0].elements.push({id:'missing',type:'RUN',runId:'missing'});
    expect(displayPlanRuns(d)).toHaveLength(2);
    for(const leg of [{...first,runId:'3'},{...first,runId:'missing'},{...first,serviceId:'2'},{...first,serviceId:'missing'}])expect(()=>createDisplayPlan(d,leg)).toThrow('existing duty RUN task');
  });
  it('rejects duplicates and global overlapping claims while allowing disjoint units',()=>{
    const created=createDisplayPlan(document(),first);
    expect(()=>attachDisplayPlanRun(created,'1',{...first,unitSlots:[2]})).toThrow('already belongs to this display plan');
    expect(()=>createDisplayPlan(created,{...first,unitSlots:[1,2]})).toThrow('unit slot already belongs');
    const disjoint=createDisplayPlan(created,{...first,unitSlots:[2]});
    expect(displayPlansForRun(disjoint,'1','1')).toHaveLength(2);
    expect(()=>updateDisplayPlanLeg(disjoint,'2',0,{...first,unitSlots:[1]})).toThrow('unit slot already belongs');
    expect(updateDisplayPlanLeg(disjoint,'2',0,{...first,unitSlots:[3]}).operationalTrips?.[1].segments[0].unitSlots).toEqual([3]);
    const attached=attachDisplayPlanRun(disjoint,'1',second);
    expect(()=>updateDisplayPlanLeg(attached,'1',1,{...first,unitSlots:[3]})).toThrow('already belongs to this display plan');
  });
  it('validates edited slots and defaults using existing limits',()=>{
    for(const slots of [[],[0],[1,1],[2147483648],Array.from({length:129},(_,i)=>i+1)])expect(()=>createDisplayPlan(document(),{...first,unitSlots:slots})).toThrow('distinct positive integers');
    const d=createDisplayPlan(document(),first);
    expect(()=>updateDisplayPlanDefaults(d,'1',{' ':1})).toThrow('nonblank');
    expect(()=>updateDisplayPlanDefaults(d,'1',{huge:'a'.repeat(65537)})).toThrow('8192');
  });
  it('preserves unrelated broken legs and exposes fully unattached plans for deletion or repair',()=>{
    const d=document(),missing={serviceId:'deleted',runId:'gone',unitSlots:[1]};
    d.operationalTrips=[{id:'broken',defaults:{destination:'Lost'},segments:[missing]},{id:'partial',defaults:{destination:'E'},segments:[first,missing]},{id:'empty',defaults:{},segments:[]}];
    expect(orphanDisplayPlans(d).map((plan)=>plan.id)).toEqual(['broken','empty']);
    expect(displayPlansForRun(d,'1','1')[0].segments).toEqual([first,missing]);
    const attached=attachDisplayPlanRun(d,'broken',second);
    expect(attached.operationalTrips?.[0].segments).toEqual([missing,second]);expect(orphanDisplayPlans(attached).map((plan)=>plan.id)).toEqual(['empty']);
    const repaired=updateDisplayPlanLeg(attached,'broken',0,{...first,unitSlots:[2]});
    expect(repaired.operationalTrips?.[0].defaults).toBe(d.operationalTrips[0].defaults);expect(repaired.operationalTrips?.[1]).toBe(d.operationalTrips[1]);
    const removed=removeDisplayPlan(repaired,'empty');expect(orphanDisplayPlans(removed)).toEqual([]);
    expect(removeDisplayPlanLeg(removed,'partial',1).operationalTrips?.[1].segments).toEqual([first]);
  });
  it('reorders exact leg objects and removes last leg and defaults in one transaction',()=>{
    const d=attachDisplayPlanRun(createDisplayPlan(document(),first),'1',second),plan=d.operationalTrips![0];
    const moved=moveDisplayPlanLeg(d,'1',1,-1);expect(moved.operationalTrips![0].segments).toEqual([second,first]);
    expect(moved.operationalTrips![0].segments[0]).toBe(plan.segments[1]);expect(plan.segments).toEqual([first,second]);
    expect(moveDisplayPlanLeg(d,'1',0,-1)).toBe(d);
    const one=removeDisplayPlanLeg(d,'1',0);expect(one.operationalTrips![0].segments).toEqual([second]);
    const empty=removeDisplayPlanLeg(one,'1',0);expect(empty.operationalTrips).toEqual([]);
    expect(()=>removeDisplayPlanLeg(d,'1',2)).toThrow('leg no longer exists');expect(()=>removeDisplayPlan(d,'gone')).toThrow('plan no longer exists');
  });
  it('keeps edits atomic through history and exact saved recovery data',()=>{
    const initial=document(),created=createDisplayPlan(initial,first),attached=attachDisplayPlanRun(created,'1',second);
    const history=updateDocumentHistory(updateDocumentHistory(createDocumentHistory(initial),created),attached);
    expect(undoDocumentHistory(history).present).toBe(created);expect(redoDocumentHistory(undoDocumentHistory(history)).present).toBe(attached);
    expect(undoDocumentHistory(updateDocumentHistory(history,removeDisplayPlan(attached,'1'))).present).toBe(attached);
    const saved=authoringDocumentForSave(attached),recovered=parseAuthoringRecovery(JSON.stringify({version:1,owner:'p',world:'w',documentId:'doc',formalScriptName:'weekday.js',persistedFormalScriptName:'weekday.js',savedDocumentJson:'{}',document:saved}),'p','w');
    expect(recovered?.document).toEqual(saved);expect(recovered?.document.operationalTrips?.[0].segments).toEqual([first,second]);
  });
  it('leaves generated movement and run scopes unchanged while emitting exact current trip facts',()=>{
    const initial=document();initial.runs=initial.runs.slice(0,2).map((run)=>({...run,diagramLineId:'main'}));initial.diagramLines=[{id:'main',upStationIds:['a','b'],downStationIds:['b','a']}];
    const next=updateDisplayPlanDefaults(attachDisplayPlanRun(createDisplayPlan(initial,first),'1',second),'1',{destination:'B'});
    const before=generateTimetableSource(initial,lines),after=generateTimetableSource(next,lines);
    expect(before.issues).toEqual([]);expect(after.issues).toEqual([]);
    expect(after.source).toContain('timetable.operationalTrip("1", JSON.parse(');
    expect(after.source).toContain(JSON.stringify(JSON.stringify({defaults:{destination:'B'},segments:[first,second]})));
    expect(after.source.split('\n').filter((line)=>!line.includes('timetable.operationalTrip('))).toEqual(before.source.split('\n'));
    expect(next.runs).toBe(initial.runs);expect(next.duties).toBe(initial.duties);
  });
});
