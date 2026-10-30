import {renderToStaticMarkup} from 'react-dom/server';
import {describe,expect,it} from 'vitest';
import {I18nProvider} from './i18n';
import {TimetableEventsEditor} from './TimetableEventsEditor';

describe('time point index',()=>{
  it('shows real station names and explicit edit actions without separate editing fields',()=>{
    const html=renderToStaticMarkup(<I18nProvider><TimetableEventsEditor operations={[{operation:{id:'1',stationId:'a',tick:100,label:'Turnback'},owner:'GRAPH',startTick:100,endTick:100}]} stationNames={new Map([['a','Central']])} onEdit={()=>{}} onOpenDuty={()=>{}}/></I18nProvider>);
    expect(html).toContain('Central');expect(html).toContain('Turnback');
    expect(html).toContain('aria-label="Edit timed operation 1"');
    expect(html).not.toContain('<input');expect(html).not.toContain('Add event point');
  });

  it('does not expose internal duty element identities as player-facing names',()=>{
    const html=renderToStaticMarkup(<I18nProvider><TimetableEventsEditor operations={[{operation:{id:'element-internal-random',tick:100,program:{programId:'1',arguments:{}}},owner:'DUTY',dutyId:'2',elementId:'element-internal-random',startTick:100,endTick:100}]} stationNames={new Map()} onEdit={()=>{}} onOpenDuty={()=>{}}/></I18nProvider>);
    expect(html).not.toContain('element-internal-random');expect(html).toContain('Go to owning duty');
  });
  it('points an empty list to the graph creation button',()=>{
    const html=renderToStaticMarkup(<I18nProvider><TimetableEventsEditor operations={[]} stationNames={new Map()} onEdit={()=>{}} onOpenDuty={()=>{}}/></I18nProvider>);
    expect(html).toContain('Use the add button above the graph');
  });
});
