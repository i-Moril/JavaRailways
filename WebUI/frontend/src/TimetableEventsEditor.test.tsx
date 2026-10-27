import {renderToStaticMarkup} from 'react-dom/server';
import {describe,expect,it} from 'vitest';
import {I18nProvider} from './i18n';
import {TimetableEventsEditor} from './TimetableEventsEditor';

describe('time point index',()=>{
  it('shows real station names and explicit edit actions without separate editing fields',()=>{
    const html=renderToStaticMarkup(<I18nProvider><TimetableEventsEditor events={[{id:'1',stationId:'a',tick:100,label:'Turnback'}]} stationNames={new Map([['a','Central']])} onEdit={()=>{}}/></I18nProvider>);
    expect(html).toContain('Central');expect(html).toContain('Turnback');
    expect(html).toContain('aria-label="Edit time point 1"');
    expect(html).not.toContain('<input');expect(html).not.toContain('Add event point');
  });
  it('points an empty list to the graph creation button',()=>{
    const html=renderToStaticMarkup(<I18nProvider><TimetableEventsEditor events={[]} stationNames={new Map()} onEdit={()=>{}}/></I18nProvider>);
    expect(html).toContain('Use the add button above the graph');
  });
});
