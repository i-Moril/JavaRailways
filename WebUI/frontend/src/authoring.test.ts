import { describe,expect,it } from 'vitest';
import { generateTimetableSource,moveRunToDuty,validateAuthoringDocument,type TimetableAuthoringDocumentV1 } from './authoring';

const valid=():TimetableAuthoringDocumentV1=>({
  formatVersion:1,id:'l1',periodDays:1,
  diagramLines:[{id:'line-1',upStationIds:['a','b'],downStationIds:['b','a']}],arrivalOverrides:[],
  runs:[
    {id:'up-01',type:'PASSENGER',diagramLineId:'line-1',calls:[
      {id:'up-a',targetId:'a@1',departureTick:100},
      {id:'up-b',targetId:'b@1',arrivalTick:800},
    ]},
    {id:'down-01',type:'PASSENGER',diagramLineId:'line-1',calls:[
      {id:'down-b',targetId:'b@1',departureTick:1000},
      {id:'down-a',targetId:'a@2',arrivalTick:1800,doorSide:'NONE'},
    ]},
  ],
  duties:[{id:'duty-a',selector:{type:'TRAIN_NAME',name:'train-1'},elements:[
    {id:'initial',type:'FUNCTION',call:{id:'initial-call',type:'PLACE_INITIAL',trainName:'train-1',wayPointId:'a@1'}},
    {id:'up-element',type:'RUN',runId:'up-01'},
    {id:'reverse',type:'FUNCTION',call:{id:'reverse-call',type:'REVERSE',tick:900}},
    {id:'down-element',type:'RUN',runId:'down-01'},
  ]}],
});

describe('timetable editor document',()=>{
  it('requires every run to have one unique duty owner',()=>{
    const document=valid();
    document.duties.push({id:'duty-b',selector:{type:'TRAIN_NAME',name:'train-2'},elements:[
      {id:'duplicate',type:'RUN',runId:'up-01'},
    ]});
    const messages=validateAuthoringDocument(document).map((item)=>item.message);
    expect(messages).toContain('Run up-01 is already assigned to duty duty-a');
    document.duties[0].elements=document.duties[0].elements.filter((item)=>item.type!=='RUN'||item.runId!=='down-01');
    expect(validateAuthoringDocument(document).map((item)=>item.message)).toContain('Run down-01 is not assigned to a duty');
  });

  it('moves a run between duties atomically while preserving its element identity',()=>{
    const document=valid();
    document.duties.push({id:'duty-b',selector:{type:'TRAIN_NAME',name:'train-2'},elements:[]});
    const moved=moveRunToDuty(document,'up-01','duty-b','unused-new-id');

    expect(moved.duties[0].elements.some((item)=>item.type==='RUN'&&item.runId==='up-01')).toBe(false);
    expect(moved.duties[1].elements).toContainEqual({id:'up-element',type:'RUN',runId:'up-01'});
    expect(validateAuthoringDocument(moved).some((item)=>item.message.includes('already assigned'))).toBe(false);
  });

  it('requires initial placement at the matching named duty start',()=>{
    const document=valid();
    const initial=document.duties[0].elements.shift()!;
    document.duties[0].elements.push(initial);
    expect(validateAuthoringDocument(document).map((item)=>item.message)).toContain('Initial placement must be the first duty element');
  });

  it('deterministically generates one service per duty with origin trace scopes',()=>{
    const first=generateTimetableSource(valid());
    const second=generateTimetableSource(valid());
    expect(first.issues).toEqual([]);
    expect(first.source).toBe(second.source);
    expect(first.source).toContain('timetable.setInitialPosition(timetable.named("train-1"), timetable.named("a@1"))');
    expect(first.source).toContain('timetable.service("duty-a", "train-1", { diagramLineId: "line-1" }, t => {');
    expect(first.source).toContain('t.origin("duty:duty-a/element:up-element/run:up-01", scoped => {');
    expect(first.source).toContain('scoped.at(100).destination("b@1", { arrivalTick: 800 })');
    expect(first.source).toContain('scoped.at(1000).destination("a@2", { arrivalTick: 1800, doorSide: "NONE" })');
  });

  it('uses manual arrival overrides without changing runtime departure ticks',()=>{
    const document=valid();
    document.arrivalOverrides=[{runId:'up-01',callId:'up-b',tick:760}];
    const result=generateTimetableSource(document);
    expect(result.source).toContain('scoped.at(100).destination("b@1", { arrivalTick: 760 })');
  });

  it('rejects duty functions that violate element order or cross the period',()=>{
    const document=valid();
    document.duties[0].elements.splice(2,0,{id:'store-early',type:'FUNCTION',call:{id:'store-call',type:'STORE_AT_DEPOT',tick:850,depotId:'north',stablingPointIds:[]}});
    document.duties[0].elements.push({id:'wait-late',type:'FUNCTION',call:{id:'wait-call',type:'WAIT',tick:23_990,durationTicks:20}});
    const messages=validateAuthoringDocument(document).map((item)=>item.message);

    expect(messages).toContain('Store at depot must be the last duty element');
    expect(messages).toContain('Wait crosses the timetable period');
  });

  it('rejects stale duplicate and out-of-period arrival overrides',()=>{
    const document=valid();
    document.arrivalOverrides=[
      {runId:'missing',callId:'no-call',tick:24_000},
      {runId:'up-01',callId:'up-b',tick:700},
      {runId:'up-01',callId:'up-b',tick:710},
    ];
    const messages=validateAuthoringDocument(document).map((item)=>item.message);
    expect(messages).toContain('Unknown run: missing');
    expect(messages).toContain('Arrival override tick must be inside the timetable period');
    expect(messages).toContain('Duplicate arrival override for up-01/up-b');
  });

  it('rejects duties spanning multiple diagram lines instead of dropping presentation data',()=>{
    const document=valid();
    document.diagramLines.push({id:'line-2',upStationIds:['a','b'],downStationIds:['b','a']});
    document.runs[1].diagramLineId='line-2';
    expect(validateAuthoringDocument(document).map((item)=>item.message))
      .toContain('A duty cannot contain runs from multiple diagram lines');
  });

  it('validates function targets and stable IDs',()=>{
    const document=valid();
    const initial=document.duties[0].elements[0];
    if(initial.type!=='FUNCTION'||initial.call.type!=='PLACE_INITIAL') throw new Error('invalid fixture');
    initial.call.wayPointId='';
    document.runs[0].calls[1].id=document.runs[0].calls[0].id;
    const messages=validateAuthoringDocument(document).map((item)=>item.message);
    expect(messages).toContain('Initial placement waypoint is required');
    expect(messages).toContain(`Duplicate call ID: ${document.runs[0].calls[0].id}`);
  });

  it('generates sorted tag selectors for depot acquisition',()=>{
    const document=valid();
    document.duties[0].selector={type:'TAKE_FROM_DEPOT',depotId:'north',tick:20,tags:['z','metro']};
    document.duties[0].elements.shift();
    const result=generateTimetableSource(document);
    expect(result.issues).toEqual([]);
    expect(result.source).toContain('timetable.takeFromDepot("north", { tags: ["metro", "z"] }, 20)');
  });
});
