import {renderToStaticMarkup} from 'react-dom/server';
import {describe,expect,it} from 'vitest';
import {TimedOperationFields} from './TimedOperationFields';
import {I18nProvider} from './i18n';
import type {StationProgramRecord,TimedOperation} from './types';
const library:StationProgramRecord[]=[{formatVersion:1,id:'1',name:'Program',definition:{parameters:[{id:'text',type:'STRING'}],steps:[]},estimatedDurationTicks:80}];
const render=(operation:TimedOperation,readOnly=false)=>renderToStaticMarkup(<I18nProvider><TimedOperationFields operation={operation} onChange={()=>{}} fieldScope="qa" stationIds={['a']} stationNames={new Map([['a','Central']])} library={library} allowMarker readOnly={readOnly}/></I18nProvider>);
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
 it('uses the local program estimate for function-duration planning',()=>{
  const html=render({id:'a',tick:100,program:{programId:'1',arguments:{text:'Next stop'}},duration:{type:'PROGRAM'}});
  expect(html).toContain('Uses the estimate in the bound function snapshot');expect(html).toContain('80 ticks');
 });
});
