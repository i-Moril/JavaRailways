import {renderToStaticMarkup} from 'react-dom/server';
import {describe,expect,it} from 'vitest';
import {UnitSlotsInput} from './UnitSlotsInput';
import {ProgramLiteralInput} from './ProgramFields';
import {I18nProvider} from './i18n';
import {isUnitSlots,parseUnitSlots,unitSlotCandidate,MAX_SELECTED_UNITS} from './unitSlots';
import {blocksToDefinition,definitionToBlocks} from './programBlocks';
import {validateStationProgram} from './stationPrograms';
import type {StationProgramDefinition} from './types';

describe('formation unit selection',()=>{
  it('retains exact positive unit numbers and rejects invalid or duplicate values',()=>{
    expect(parseUnitSlots('[3,1,2147483647]')).toEqual([3,1,2147483647]);
    for(const raw of ['', 'null','[]','[0]','[-1]','[1,1]','[1.5]','[2147483648]','["1"]','[NaN]','[1,]'])expect(parseUnitSlots(raw),raw).toBeUndefined();
    expect(isUnitSlots(Array.from({length:MAX_SELECTED_UNITS},(_,i)=>i+1))).toBe(true);
    expect(isUnitSlots(Array.from({length:MAX_SELECTED_UNITS+1},(_,i)=>i+1))).toBe(false);
  });
  it('never converts partial, fractional, nonfinite, repeated or oversized candidates',()=>{
    expect(unitSlotCandidate(' 12 ',[1,2])).toBe(12);
    for(const raw of ['','-','-1','0','1','1.1','1e2','NaN','Infinity','2147483648','9007199254740993'])expect(unitSlotCandidate(raw,[1]),raw).toBeUndefined();
    expect(unitSlotCandidate('129',Array.from({length:128},(_,i)=>i+1))).toBeUndefined();
  });
  it('labels real complete units without inventing available vehicles or committing on mount',()=>{
    let calls=0;
    const html=renderToStaticMarkup(<I18nProvider><UnitSlotsInput fieldKey="world/library/block" value={[1,3]} onChange={()=>calls++}/></I18nProvider>);
    expect(html).toContain('Unit 1');expect(html).toContain('Unit 3');expect(html).not.toContain('Unit 2');
    expect(html).toContain('A unit may contain several cars');expect(html).toContain('operating head changes');expect(html).toContain('Unit number to add');expect(calls).toBe(0);
  });
  it('preserves invalid raw Blockly text visibly for repair instead of showing an empty selection',()=>{
    const html=renderToStaticMarkup(<I18nProvider><UnitSlotsInput fieldKey="world/block" rawValue="[1,1]" onRawChange={()=>{}} onChange={()=>{throw Error('Must not coerce');}}/></I18nProvider>);
    expect(html).toContain('value="[1,1]"');expect(html).toContain('Invalid unit list');expect(html).not.toContain('Add unit');
  });
  it('uses the same understandable picker for program arguments',()=>{
    const html=renderToStaticMarkup(<I18nProvider><ProgramLiteralInput fieldKey="world/call/units" type="UNIT_SLOTS" value={[2]} onChange={()=>{}}/></I18nProvider>);
    expect(html).toContain('Unit 2');expect(html).toContain('Add unit');expect(html).not.toContain('ant-select');
  });
  it('keeps Blockly array serialization and parameter reference semantics unchanged',()=>{
    const definition:StationProgramDefinition={parameters:[{id:'units',type:'UNIT_SLOTS'}],steps:[{id:'display',type:'RESET_DISPLAY',unitSlots:{value:[3,1]}},{id:'param',type:'RESET_DISPLAY',unitSlots:{parameterId:'units'}}]};
    const state=definitionToBlocks(definition);
    expect(blocksToDefinition(state,definition.parameters)).toEqual(definition);
    const literal=state.blocks!.blocks[0].next!.block!.inputs!.unitSlots.shadow!;
    literal.fields!.VALUE='[1,1]';
    expect(validateStationProgram(blocksToDefinition(state,definition.parameters)).length).toBeGreaterThan(0);
    expect(literal.fields!.VALUE).toBe('[1,1]');
  });
});
