import {renderToStaticMarkup} from 'react-dom/server';
import {describe,expect,it} from 'vitest';
import {TimedOperationFields} from './TimedOperationFields';
import {I18nProvider} from './i18n';
import type {BoundStationProgram,StationProgramRecord,TimedOperation} from './types';
const render=(operation:TimedOperation,readOnly=false)=>renderToStaticMarkup(<I18nProvider><TimedOperationFields operation={operation} onChange={()=>{}} fieldScope="qa" stationIds={['a']} stationNames={new Map([['a','Central']])} library={[]} bindings={[{programId:'1',contentIdentity:'hash',revision:1,definition:{parameters:[{id:'text',type:'STRING'}],steps:[]}}]} allowMarker readOnly={readOnly}/></I18nProvider>);
describe('shared timed operation form',()=>{
 it('uses the same optional station, timing, duration and real program arguments',()=>{
  const html=render({id:'a',tick:100,stationId:'a',program:{programId:'1',arguments:{text:'Next stop'}},duration:{type:'MANUAL',ticks:50}});
  expect(html).toContain('Display station (optional)');expect(html).toContain('Central');expect(html).toContain('Planned duration (ticks)');expect(html).toContain('value="Next stop"');expect(html).toContain('does not move the train');
 });
 it('distinguishes a marker without creating an executable call or defaulting a station',()=>{
  const html=render({id:'marker',tick:0});expect(html).toContain('Time marker only');expect(html).toContain('cannot be added to a duty as work');expect(html).not.toContain('unit-slots-input');
 });
 it('disables shared controls for references and keeps simulated unknown honest',()=>{
  const html=render({id:'a',tick:100,program:{programId:'1',arguments:{text:'Next stop'}},duration:{type:'SIMULATED'}},true);
  expect(html).toContain('Duration not measured');expect(html).toContain('disabled=""');expect(html).not.toContain('Planned duration (ticks)</label>');
 });
 it('surfaces a stale bound snapshot and offers the latest library revision',()=>{
  const library:StationProgramRecord[]=[{formatVersion:1,id:'1',name:'Program',revision:2,contentIdentity:'new',estimatedDurationTicks:80,definition:{parameters:[{id:'text',type:'STRING'}],steps:[]}}];
  const bindings:BoundStationProgram[]=[{programId:'1',revision:1,contentIdentity:'old',definition:{parameters:[{id:'text',type:'STRING'}],steps:[]}}];
  const html=renderToStaticMarkup(<I18nProvider><TimedOperationFields operation={{id:'a',tick:100,program:{programId:'1',arguments:{text:'Next stop'}},duration:{type:'PROGRAM'}}} onChange={()=>{}} fieldScope="qa" stationIds={['a']} stationNames={new Map([['a','Central']])} library={library} bindings={bindings} allowMarker onAdoptProgram={()=>{}}/></I18nProvider>);
  expect(html).toContain('The library has a newer revision');expect(html).toContain('80 ticks');expect(html).toContain('Adopt new revision r2');
 });
});
