import {describe,it,expect} from 'vitest';
import {emptyAuthoringDocument,generateTimetableSource,type TimetableAuthoringDocumentV1} from './authoring';
import {formationEventShape,renameFormationDuty,validateFormationAuthoring,type AuthoringFormationEvent} from './formationAuthoring';
import {parseAuthoringRecovery} from './authoringRecovery';
import {createDocumentHistory,updateDocumentHistory,undoDocumentHistory} from './documentHistory';
const event=():AuthoringFormationEvent=>({id:'split',kind:'SPLIT',coordinatorDutyId:'source',partition:{from:'HEAD',firstUnitCount:1},outputs:[{id:'first',group:'FIRST',disposition:{type:'DUTY',dutyId:'source'}},{id:'rest',group:'REST',disposition:{type:'DUTY',dutyId:'child'}}]});
const document=():TimetableAuthoringDocumentV1=>({...emptyAuthoringDocument(),formationEvents:[event()],boundPrograms:[{programId:'p',revision:1,contentIdentity:`sha256:${'1'.repeat(64)}`,definition:{parameters:[{id:'event',type:'FORMATION_EVENT'}],steps:[{id:'split',type:'SPLIT',eventId:{parameterId:'event'}}]}}],duties:[{id:'source',selector:{type:'TAKE_FROM_WAYPOINT',wayPointIds:['start'],tick:0},elements:[{id:'invoke',type:'PROGRAM',tick:10,program:{programId:'p',arguments:{event:'split'}}}]},{id:'child',selector:{type:'TAKE_FROM_FORMATION_OUTPUT',eventId:'split',outputId:'rest',tick:100},elements:[]}]});
describe('fixed formation authoring',()=>{
  it('generates declared static events and exact output acquisition from the sole document',()=>{
    const value=document();expect(validateFormationAuthoring(value)).toEqual([]);
    const generated=generateTimetableSource(value);
    expect(generated.source).toContain('timetable.formationEvent("split", JSON.parse(');
    expect(generated.source).toContain('timetable.takeFromFormationOutput("split", "rest", 100)');
    expect(generated.source).toContain('FORMATION_EVENT');
    const next={...value,formationEvents:[{...event(),id:'different'}]};
    expect(generateTimetableSource(next).documentHashInput).not.toBe(generated.documentHashInput);
    expect(undoDocumentHistory(updateDocumentHistory(createDocumentHistory(value),next)).present).toBe(value);
  });
  it('rejects wrong participants, missing reciprocal outputs and duplicate consumption',()=>{
    const value=document();value.duties[1].selector={type:'TAKE_FROM_FORMATION_OUTPUT',eventId:'split',outputId:'first',tick:100};
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
});
