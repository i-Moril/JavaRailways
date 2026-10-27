import {renderToStaticMarkup} from 'react-dom/server';
import {describe,expect,it} from 'vitest';
import {TimedOperationFields} from './TimedOperationFields';
import {I18nProvider} from './i18n';
import type {TimedOperation} from './types';
const render=(operation:TimedOperation,readOnly=false)=>renderToStaticMarkup(<I18nProvider><TimedOperationFields operation={operation} onChange={()=>{}} fieldScope="qa" stationIds={['a']} stationNames={new Map([['a','Central']])} library={[]} bindings={[{programId:'1',contentIdentity:'hash',revision:1,definition:{parameters:[{id:'units',type:'UNIT_SLOTS'}],steps:[]}}]} allowMarker readOnly={readOnly}/></I18nProvider>);
describe('shared timed operation form',()=>{
 it('uses the same optional station, timing, duration and real program arguments',()=>{
  const html=render({id:'a',tick:100,stationId:'a',program:{programId:'1',arguments:{units:[1,2]}},duration:{type:'MANUAL',ticks:50}});
  expect(html).toContain('Display station (optional)');expect(html).toContain('Central');expect(html).toContain('Planned duration (ticks)');expect(html).toContain('unit-slots-input');expect(html).toContain('Unit 2');expect(html).toContain('does not move the train');
 });
 it('distinguishes a marker without creating an executable call or defaulting a station',()=>{
  const html=render({id:'marker',tick:0});expect(html).toContain('Time marker only');expect(html).toContain('cannot be added to a duty as work');expect(html).not.toContain('unit-slots-input');
 });
 it('disables shared controls for references and keeps simulated unknown honest',()=>{
  const html=render({id:'a',tick:100,program:{programId:'1',arguments:{units:[1]}},duration:{type:'SIMULATED'}},true);
  expect(html).toContain('Duration not measured');expect(html).toContain('disabled=""');expect(html).not.toContain('Planned duration (ticks)</label>');
 });
});
