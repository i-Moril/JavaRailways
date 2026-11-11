import {describe,it,expect} from 'vitest';
import {emptyAuthoringDocument,generateTimetableSource,type TimetableAuthoringDocumentV1} from './authoring';
import {createDutyFormation,updateDutyFormation,removeDutyFormation,setDutyVehicleSource,formationPlannedEnd,formationEventShape,renameFormationDuty,validateFormationAuthoring,type AuthoringFormationEvent} from './formationAuthoring';
import {parseAuthoringRecovery} from './authoringRecovery';
import {createDocumentHistory,updateDocumentHistory,undoDocumentHistory} from './documentHistory';
const event=():AuthoringFormationEvent=>({id:'split',tick:10,duration:{type:'MANUAL',ticks:5},kind:'SPLIT',coordinatorDutyId:'source',partition:{from:'HEAD',firstUnitCount:1},outputs:[{id:'first',group:'FIRST',disposition:{type:'DUTY',dutyId:'source'}},{id:'rest',group:'REST',disposition:{type:'DUTY',dutyId:'child'}}]});
const document=():TimetableAuthoringDocumentV1=>({...emptyAuthoringDocument(),formationEvents:[event()],duties:[{id:'source',selector:{type:'TAKE_FROM_WAYPOINT',wayPointIds:['start'],tick:0},elements:[{id:'invoke',type:'FORMATION',eventId:'split'}]},{id:'child',selector:{type:'TAKE_FROM_FORMATION_OUTPUT',eventId:'split',outputId:'rest'},elements:[]}]});
describe('fixed formation authoring',()=>{
  it('generates declared static events and exact output acquisition from the sole document',()=>{
    const value=document();expect(validateFormationAuthoring(value)).toEqual([]);
    const generated=generateTimetableSource(value);
    expect(generated.source).toContain('timetable.formationEvent("split", JSON.parse(');
    expect(generated.source).toContain('timetable.takeFromFormationOutput("split", "rest", 10)');
    expect(generated.source).toContain('taskScope.split("split")');
    expect(generated.source).not.toContain('duration');
    const next={...value,formationEvents:[{...event(),id:'different'}]};
    expect(generateTimetableSource(next).documentHashInput).not.toBe(generated.documentHashInput);
    expect(undoDocumentHistory(updateDocumentHistory(createDocumentHistory(value),next)).present).toBe(value);
  });
  it('rejects wrong participants, missing reciprocal outputs and duplicate consumption',()=>{
    const value=document();value.duties[1].selector={type:'TAKE_FROM_FORMATION_OUTPUT',eventId:'split',outputId:'first'};
    expect(validateFormationAuthoring(value).map((i)=>i.message).join(' ')).toContain('cannot be reacquired');
    value.duties[0].elements=[];
    expect(validateFormationAuthoring(value).map((i)=>i.message).join(' ')).toContain('exactly one invocation');
  });
  it('renames participants and output ownership together without mutating previous snapshots',()=>{
    const original=[event()],renamed=renameFormationDuty(original,'source','renamed')!;
    expect(renamed[0].coordinatorDutyId).toBe('renamed');
    expect(renamed[0].kind==='SPLIT'&&renamed[0].outputs[0].disposition).toEqual({type:'DUTY',dutyId:'renamed'});
    expect(original[0].coordinatorDutyId).toBe('source');
  });
  it('recovers valid catalogs but rejects malformed partitions and output dispositions',()=>{
    expect(formationEventShape(event())).toBe(true);
    const recovery={version:1,owner:'a',world:'w',documentId:'',formalScriptName:'a.js',persistedFormalScriptName:'',savedDocumentJson:'{}',document:document()};
    expect(parseAuthoringRecovery(JSON.stringify(recovery),'a','w')?.document.formationEvents).toEqual([event()]);
    const bad={...event(),partition:{from:'TAIL',firstUnitCount:1}};
    expect(formationEventShape(bad)).toBe(false);
    expect(parseAuthoringRecovery(JSON.stringify({...recovery,document:{...document(),formationEvents:[bad]}}),'a','w')).toBeUndefined();
    expect(formationEventShape({...event(),unexpected:true})).toBe(false);
  });
  it('assigns only existing receiver duties atomically and leaves missing-source diagnostics on removal',()=>{
    let serial=0;const base={...emptyAuthoringDocument(),duties:document().duties.map((d)=>({...d,elements:[],selector:{type:'TRAIN_NAME' as const,name:d.id}}))};
    const created=createDutyFormation(base,'source','SPLIT','e',()=>`task${++serial}`);
    const e=created.formationEvents![0];if(e.kind!=='SPLIT')throw new Error('split');
    expect(created.duties).toHaveLength(2);expect(formationPlannedEnd(e)).toBeUndefined();
    const assigned=updateDutyFormation(created,{...e,outputs:e.outputs.map((o)=>o.group==='REST'?{...o,disposition:{type:'DUTY',dutyId:'child'}}:o)});
    expect(assigned.duties[1].selector).toEqual({type:'TAKE_FROM_FORMATION_OUTPUT',eventId:'e',outputId:'e:rest'});
    expect(created.duties[1].selector.type).toBe('TRAIN_NAME');expect(validateFormationAuthoring(assigned)).toEqual([]);
    expect(validateFormationAuthoring(removeDutyFormation(assigned,'e')).some((i)=>i.path.includes('selector'))).toBe(true);
    expect(()=>updateDutyFormation(assigned,{...e,outputs:e.outputs.map((o)=>({...o,disposition:{type:'DUTY',dutyId:'missing'}}))})).toThrow();
  });
  it('keeps planning-only edits out of executable source and identity',()=>{
    const d=document(),before=generateTimetableSource(d),changed={...d,formationEvents:d.formationEvents!.map((e)=>({...e,duration:{type:'MANUAL' as const,ticks:8},stationId:'central'}))};
    expect(generateTimetableSource(changed).source).toBe(before.source);expect(generateTimetableSource(changed).documentHashInput).toBe(before.documentHashInput);
  });
  it('diagnoses receiver tasks before availability and cyclic source dependencies',()=>{
    const d=document();d.duties[1].elements=[{id:'too-early',type:'PROGRAM',tick:12,program:{programId:'missing',arguments:{}}}];
    expect(validateFormationAuthoring(d).some((i)=>i.message.includes('available'))).toBe(true);
    d.duties[0].selector={type:'TAKE_FROM_FORMATION_OUTPUT',eventId:'split',outputId:'first'};
    expect(validateFormationAuthoring(d).some((i)=>i.message.includes('cycle'))).toBe(true);
  });
  it('couples participants without a program and ends the consumed participant',()=>{
    const d={...emptyAuthoringDocument(),duties:document().duties.map((d)=>({...d,elements:[],selector:{type:'TAKE_FROM_WAYPOINT' as const,wayPointIds:['start'],tick:0}}))};let serial=0;
    const created=createDutyFormation(d,'source','COUPLE','join',()=>`task${++serial}`),e=created.formationEvents![0];if(e.kind!=='COUPLE')throw new Error('couple');
    const joined=updateDutyFormation(created,{...e,rendezvous:{approachDestinationId:'a',stationaryDestinationId:'b'},tick:20});
    expect(validateFormationAuthoring(joined)).toEqual([]);expect(generateTimetableSource(joined).source.match(/taskScope.couple/g)).toHaveLength(2);
    joined.duties[1].elements.push({id:'later',type:'PROGRAM',tick:30,program:{programId:'p',arguments:{}}});
    expect(validateFormationAuthoring(joined).some((i)=>i.message.includes('must end'))).toBe(true);
  });

  it('reassigns received sources and releasing a source updates both sides with undo',()=>{
    const d=document(),released=setDutyVehicleSource(d,'child',{type:'TRAIN_NAME',name:'another'});
    expect(released.formationEvents![0].kind==='SPLIT'&&released.formationEvents![0].outputs[1].disposition).toEqual({type:'HOLD'});
    expect(validateFormationAuthoring(released)).toEqual([]);
    const nextEvent={...event(),id:'second',tick:30,outputs:(d.formationEvents![0] as Extract<AuthoringFormationEvent,{kind:'SPLIT'}>).outputs} as AuthoringFormationEvent;
    expect(()=>updateDutyFormation(d,nextEvent)).toThrow('already acquires');
    const moved=updateDutyFormation(released,nextEvent);
    expect(moved.duties[1].selector).toEqual({type:'TAKE_FROM_FORMATION_OUTPUT',eventId:'second',outputId:'rest'});
    expect(moved.formationEvents![0].kind==='SPLIT'&&moved.formationEvents![0].outputs[1].disposition).toEqual({type:'HOLD'});
    expect(validateFormationAuthoring(moved)).toEqual([]);
    expect(undoDocumentHistory(updateDocumentHistory(createDocumentHistory(d),moved)).present).toBe(d);
    const edited=updateDutyFormation(moved,{...moved.formationEvents![0],duration:{type:'MANUAL',ticks:8}});
    expect(edited.formationEvents!.map((e)=>e.id)).toEqual(moved.formationEvents!.map((e)=>e.id));
    expect(generateTimetableSource(edited).documentHashInput).toBe(generateTimetableSource(moved).documentHashInput);
  });

  it('rejects conflicting shared-event order and checks consumption by planned order',()=>{
    const d={...emptyAuthoringDocument(),duties:document().duties.map((d)=>({...d,elements:[],selector:{type:'TAKE_FROM_WAYPOINT' as const,wayPointIds:['start'],tick:0}}))};let serial=0;
    const joined=createDutyFormation(d,'source','COUPLE','join',()=>`task${++serial}`),event=joined.formationEvents![0];if(event.kind!=='COUPLE')throw new Error('couple');
    const first={...event,tick:20,rendezvous:{approachDestinationId:'a',stationaryDestinationId:'b'}};
    const next=updateDutyFormation(joined,first);next.formationEvents!.push({...first,id:'other'});
    next.duties[0].elements.push({id:'other-a',type:'FORMATION',eventId:'other'});
    next.duties[1].elements.unshift({id:'other-b',type:'FORMATION',eventId:'other'});
    expect(validateFormationAuthoring(next).some((i)=>i.message==='Formation task dependency cycle')).toBe(true);
    const consumed=updateDutyFormation(joined,first);
    consumed.duties[1].elements.unshift({id:'later',type:'PROGRAM',tick:30,program:{programId:'p',arguments:{}}});
    expect(validateFormationAuthoring(consumed).some((i)=>i.message.includes('must end'))).toBe(true);
  });
  it('recovers incomplete coupling draft references without inventing destinations',()=>{
    const d={...emptyAuthoringDocument(),duties:document().duties.map((d)=>({...d,elements:[],selector:{type:'TRAIN_NAME' as const,name:d.id}}))};let serial=0;
    const draft=createDutyFormation(d,'source','COUPLE','join',()=>`task${++serial}`);
    const recovery={version:1,owner:'a',world:'w',documentId:'',formalScriptName:'a.js',persistedFormalScriptName:'',savedDocumentJson:'{}',document:draft};
    expect(parseAuthoringRecovery(JSON.stringify(recovery),'a','w')?.document).toEqual(draft);
    expect(generateTimetableSource(draft).issues.some((i)=>i.message.includes('rendezvous'))).toBe(true);
  });

  it('rejects coupling edits that orphan its owner or duplicate participants before mutation',()=>{
    const d=document();d.duties.push({id:'other',selector:{type:'TRAIN_NAME',name:'other'},elements:[]});let serial=0;
    const joined=createDutyFormation(d,'source','COUPLE','join',()=>`task${++serial}`),event=joined.formationEvents!.find((e)=>e.id==='join')!;if(event.kind!=='COUPLE')throw new Error('couple');
    expect(()=>updateDutyFormation(joined,{...event,approachingDutyId:'other'})).toThrow('coordinator');
    expect(()=>updateDutyFormation(joined,{...event,stationaryDutyId:event.approachingDutyId})).toThrow('distinct participants');
    expect(joined.formationEvents!.find((e)=>e.id==='join')).toBe(event);
  });

  it('emits initial check for named trains and validates known occupancy without inventing unknown spans',()=>{
    const d=document();d.duties[0]={...d.duties[0],selector:{type:'TRAIN_NAME',name:'train'}};d.initialPositions=[{trainName:'train',wayPointId:'start'}];
    expect(generateTimetableSource(d).source).toContain('scoped.check("start")');
    d.initialPositions=[];expect(generateTimetableSource(d).issues.some((i)=>i.message.includes('initial placement'))).toBe(true);
    const split=event();if(split.kind!=='SPLIT')throw new Error('split');
    const next={...split,id:'second',tick:12,outputs:split.outputs.map((o)=>o.group==='REST'?{...o,disposition:{type:'HOLD' as const}}:o)};
    const overlapping=updateDutyFormation(document(),next);
    expect(generateTimetableSource(overlapping).issues.some((i)=>i.message.includes('previous planned task'))).toBe(true);
    const unknown={...overlapping,formationEvents:overlapping.formationEvents!.map((e)=>e.id==='split'?{...e,duration:undefined}:e)};
    expect(generateTimetableSource(unknown).issues.some((i)=>i.message.includes('previous planned task'))).toBe(false);
    for(const firstUnitCount of [0,-1,1.5,2147483648])expect(()=>updateDutyFormation(document(),{...split,partition:{from:'HEAD',firstUnitCount}})).toThrow('Int32');
  });

});
