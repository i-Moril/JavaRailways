import { renderToStaticMarkup } from 'react-dom/server';
import { describe,expect,it } from 'vitest';
import { Graph } from './Graph';
import { I18nProvider } from './i18n';
import { ThemeProvider } from './theme';
import type { SavedTimetableV1 } from './types';

const value:SavedTimetableV1={
  id:'demo',periodDays:1,diagramLines:[{id:'airport',upStationIds:['harbour','airport'],downStationIds:['airport','harbour']}],
  services:[{id:'AP101',diagramLineId:'airport',selectorType:'TRAIN_ID',activation:{type:'SCHEDULED'},steps:[
    {type:'CHECK',wayPointId:'harbour@1'},
    {type:'AT',tick:1_200,action:{type:'STATION_DESTINATION_OPTIONS',stationId:'airport',berthId:'1',arrivalTick:4_700,wayPointIds:[],requiredTags:[],requiredCapabilities:[],stablingPointIds:[],thenActions:[],elseActions:[]}},
    {type:'AT',tick:1_200,action:{type:'DESTINATION',wayPointIds:['airport@1'],requiredTags:[],requiredCapabilities:[],stablingPointIds:[],thenActions:[],elseActions:[]}},
  ]}],
};

describe('running graph component',()=>{
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
