import {describe,expect,it} from 'vitest';
import {resolveProgramSourceTarget} from './programNavigation';
import {emptyAuthoringDocument} from './authoring';
import type {TimetableAuthoringDocumentV1} from './authoring';
import type {SavedTimetableV1,StationProgramRecord} from './types';
const document=():TimetableAuthoringDocumentV1=>({...emptyAuthoringDocument(),id:'tt',duties:[{id:'duty',selector:{type:'TRAIN_NAME',name:'T'},elements:[{id:'element',type:'PROGRAM',tick:0,program:{programId:'p',arguments:{}}}]}]});
const programs=():StationProgramRecord[]=>[{formatVersion:1,id:'p',name:'P',definition:{parameters:[],steps:[{id:'reverse',type:'REVERSE'}]}}];
const origin='duty:duty/element:element/call:element/program:p/step:reverse';
describe('visual program source navigation',()=>{
  it('resolves a current stable call and readonly bound step from compiler context or local issue path',()=>{
    const value=document();expect(resolveProgramSourceTarget(value,`Error ${origin}: invalid`,'input',undefined,programs())).toEqual({dutyId:'duty',elementId:'element',callId:'element',programId:'p',stepId:'reverse'});
    expect(resolveProgramSourceTarget(value,'duties[0].elements[0].call.tick','input',undefined,programs())?.elementId).toBe('element');
  });
  it('uses exact service/step trace only when the compiled input still matches current document',()=>{
    const value=document();const timetable:SavedTimetableV1={id:'tt',periodDays:1,services:[],compilationTrace:[{serviceId:'duty',stepIndex:7,origin}]};
    expect(resolveProgramSourceTarget(value,'[tt/duty] step#7 invalid','current',{input:'current',timetable},programs())?.stepId).toBe('reverse');
    expect(resolveProgramSourceTarget(value,'[tt/duty] step#7 invalid','current',{input:'stale',timetable},programs())).toBeUndefined();
    expect(resolveProgramSourceTarget(value,'[tt/duty] step#70 invalid','current',{input:'current',timetable},programs())).toBeUndefined();
    expect(resolveProgramSourceTarget(value,'[tt/other] step#7 invalid','current',{input:'current',timetable},programs())).toBeUndefined();
  });
  it('distinguishes nested branch traces sharing one top-level index',()=>{
    const value=document(),progs=programs();progs[0].definition.steps=[{id:'choose',type:'BRANCH',typeId:'demo:condition',version:1,parameters:{value:{}},thenSteps:[{id:'reverse',type:'REVERSE'}],elseSteps:[]}];
    const timetable:SavedTimetableV1={id:'tt',periodDays:1,services:[],compilationTrace:[{serviceId:'duty',stepIndex:7,origin:origin.replace('step:reverse','step:choose')},{serviceId:'duty',stepIndex:7,branchPath:[{arm:'THEN',index:0}],origin}]};
    expect(resolveProgramSourceTarget(value,'[tt/duty] step#7/then[0] invalid','current',{input:'current',timetable},progs)?.stepId).toBe('reverse');
    expect(resolveProgramSourceTarget(value,'[tt/duty] step#7 invalid','current',{input:'current',timetable},progs)?.stepId).toBe('choose');
    expect(resolveProgramSourceTarget(value,'[tt/duty] step#7/else[0] invalid','current',{input:'current',timetable},progs)).toBeUndefined();
  });
  it('refuses removed steps, different call identities and prefix collisions',()=>{
    const value=document();expect(resolveProgramSourceTarget(value,origin.replace('step:reverse','step:removed'),'current',undefined,programs())).toBeUndefined();
    expect(resolveProgramSourceTarget(value,origin.replace('call:element','call:other'),'current',undefined,programs())).toBeUndefined();
    expect(resolveProgramSourceTarget(value,origin.replace('/program:p/','/program:prefix/'),'current',undefined,programs())).toBeUndefined();
    value.duties[0].elements=[];expect(resolveProgramSourceTarget(value,origin,'current',undefined,programs())).toBeUndefined();
  });
});
