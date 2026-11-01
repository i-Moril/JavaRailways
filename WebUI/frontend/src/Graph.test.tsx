import { renderToStaticMarkup } from 'react-dom/server';
import { describe,expect,it } from 'vitest';
import { Graph } from './Graph';
import {graphServiceColor} from './graph';
import { I18nProvider } from './i18n';
import { ThemeProvider } from './theme';
import type { SavedTimetableV1 } from './types';

const value:SavedTimetableV1={
  id:'demo',periodDays:1,diagramLines:[{id:'airport',upStationIds:['harbour','airport'],downStationIds:['airport','harbour']}],
  services:[{id:'AP101',diagramLineId:'airport',selectorType:'TRAIN_ID',activation:{type:'SCHEDULED'},steps:[
    {type:'CHECK',wayPointId:'harbour@1'},
    {type:'AT',tick:1_200,action:{type:'STATION_DESTINATION_OPTIONS',stationId:'airport',berthId:'1',arrivalTick:4_700,wayPointIds:[],requiredTags:[],requiredCapabilities:[],stablingPointIds:[],thenSteps:[],elseSteps:[]}},
    {type:'AT',tick:1_200,action:{type:'DESTINATION',wayPointIds:['airport@1'],requiredTags:[],requiredCapabilities:[],stablingPointIds:[],thenSteps:[],elseSteps:[]}},
  ]}],
};

describe('running graph component',()=>{
  it('exposes arrival and departure points as accessible point actions without time dragging',()=>{
    const render=(editable:boolean)=>renderToStaticMarkup(<I18nProvider><ThemeProvider><Graph value={value} graphLineId="airport" metadata={{stations:[{stationId:'harbour',berthId:'1',destinationId:'harbour@1'},{stationId:'airport',berthId:'1',destinationId:'airport@1'}]}} onSelectPoint={editable?()=>{}:undefined}/></ThemeProvider></I18nProvider>);
    const interactive=render(true);
    expect(interactive).toContain('role="group"');
    expect(interactive.match(/data-graph-point="true"/g)).toHaveLength(2);
    expect(interactive.match(/tabindex="0"/g)?.length).toBeGreaterThanOrEqual(2);
    expect(interactive).toContain('cursor:pointer');
    expect(interactive).not.toContain('ew-resize');
    expect(interactive).toContain('Departure');
    expect(interactive).toContain('Arrival');
    const readonly=render(false);
    expect(readonly).toContain('role="img"');
    expect(readonly).not.toContain('ew-resize');
  });

  it('exposes event points as keyboard actions only when event editing is available',()=>{
    const render=(editable:boolean)=>renderToStaticMarkup(<I18nProvider><ThemeProvider><Graph value={value} graphLineId="airport" operations={[{operation:{id:'1',stationId:'airport',tick:1500,label:'Turnback'},owner:'GRAPH',startTick:1500,endTick:1500}]} onEditEvent={editable?()=>{}:undefined} onEventContext={editable?()=>{}:undefined}/></ThemeProvider></I18nProvider>);
    expect(render(true)).toMatch(/data-event-id="1"[^>]*role="button"[^>]*tabindex="0"/);
    expect(render(true)).toContain('Turnback');
    expect(render(true)).toContain('aria-haspopup="menu"');
    expect(render(false)).not.toContain('tabindex="0"');
    expect(render(false)).toContain('role="img"');
  });

  it('renders the studio canvas with independent axis controls',()=>{
    const html=renderToStaticMarkup(<I18nProvider><ThemeProvider><Graph value={value} graphLineId="airport" metadata={{stations:[
      {stationId:'harbour',stationDisplayName:'Harbour',berthId:'1',destinationId:'harbour@1'},
      {stationId:'airport',stationDisplayName:'Airport T2',berthId:'1',destinationId:'airport@1'},
    ]}}/></ThemeProvider></I18nProvider>);
    expect(html).toContain('class="canvas-sheet"');
    expect(html).toContain('class="diagram-svg"');
    expect(html).toContain('+X');
    expect(html).toContain('−X');
    expect(html).toContain('+Y');
    expect(html).toContain('−Y');
    expect(html).toContain('Harbour');
    expect(html).toContain('AP101');
    expect(html).not.toContain('graph-surface');
  });
});

describe('timed operation ownership on graph',()=>{
 it('shows graph intervals and read-only duty projections with distinct identities',()=>{
  const html=renderToStaticMarkup(<I18nProvider><ThemeProvider><Graph value={value} graphLineId="airport" operations={[
   {owner:'GRAPH',operation:{id:'1',tick:1000,stationId:'airport'},startTick:1000,endTick:2000},
   {owner:'DUTY',dutyId:'D1',elementId:'1',operation:{id:'1',tick:2000,stationId:'airport',program:{programId:'2',arguments:{}}},startTick:2000,endTick:3000},
   {owner:'DUTY',dutyId:'D2',elementId:'1',operation:{id:'1',tick:3000,program:{programId:'2',arguments:{}}},startTick:3000,endTick:4000},
  ]} onEditEvent={()=>{}} onOpenDutyOperation={()=>{}} onDutyOperationContext={()=>{}}/></ThemeProvider></I18nProvider>);
  expect(html).toContain('data-event-id="1"');expect(html).toContain('data-duty-operation="D1/1"');expect(html).not.toContain('data-duty-operation="D2/1"');
  expect(html).toContain('Duty-owned operation (read-only) D1');expect(html).toContain('stroke-dasharray="4 3"');
  expect(html.match(/data-operation-owner=/g)).toHaveLength(2);
 });
});


describe('running graph styling',()=>{
  const render=(props:Partial<Parameters<typeof Graph>[0]>={})=>renderToStaticMarkup(<I18nProvider><ThemeProvider><Graph value={{...value,services:[value.services[0],{...value.services[0],id:'AP102'}]}} graphLineId="airport" metadata={{stations:[{stationId:'harbour',berthId:'1',destinationId:'harbour@1'},{stationId:'airport',berthId:'1',destinationId:'airport@1'}]}} {...props}/></ThemeProvider></I18nProvider>);
  it('keeps ordinary paths thin and emphasizes only an explicitly selected run',()=>{
    const normal=render();
    expect(normal.match(/<polyline[^>]*stroke-width="1.25"/g)).toHaveLength(2);
    expect(normal).not.toContain('class="selected-path"');
    const selected=render({highlightedServiceIds:new Set(['AP101'])});
    expect(selected.match(/<polyline[^>]*stroke-width="2"/g)).toHaveLength(1);
    expect(selected.match(/<polyline[^>]*stroke-width="1"/g)).toHaveLength(1);
    expect(selected).toContain('opacity="0.28"');
    expect(selected).toContain('r="6"');expect(selected).toContain('r="4.5"');
  });
  it('uses the shared automatic color resolver for each service when no line color is specified',()=>{
    const paths=render().match(/<polyline[^>]*>/g)!;
    expect(paths[0]).toContain(`stroke="${graphServiceColor('dark',0)}"`);
    expect(paths[1]).toContain(`stroke="${graphServiceColor('dark',1)}"`);
  });
  it('uses the run color before line color, preserves fallback and preview styling',()=>{
    const html=render({line:{id:'airport',displayName:'Airport',upStationIds:['harbour','airport'],downStationIds:[],color:'#123456'},serviceColors:new Map([['AP101','#abcdef']]),previewServiceIds:new Set(['AP101'])});
    const paths=html.match(/<polyline[^>]*>/g)!;
    expect(paths[0]).toContain('stroke="#abcdef"');expect(paths[0]).toContain('stroke-dasharray="10 7"');
    expect(paths[1]).toContain('stroke="#123456"');expect(paths[1]).not.toContain('stroke-dasharray');
  });
});
