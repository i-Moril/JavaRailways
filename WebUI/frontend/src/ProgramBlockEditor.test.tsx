import {renderToStaticMarkup} from 'react-dom/server';
import {describe,expect,it} from 'vitest';
import {ProgramBlockEditor} from './ProgramBlockEditor';
import {StationStopProgramEditor} from './StationStopProgramEditor';
import {I18nProvider,translate} from './i18n';
import {blocksToPreparationLead,preparationLeadToBlock} from './programBlocks';
import {defaultStationStopProgram} from './stationPlans';
import {reconcileProgramEntryLead,type ProgramBlockSnapshot} from './programBlockDrafts';
const noop=()=>undefined;
const render=(element:React.ReactNode)=>renderToStaticMarkup(<I18nProvider>{element}</I18nProvider>);

describe('program editor controls',()=>{
 it('keeps accessible icon history controls without the redundant step toolbar',()=>{
  const html=render(<ProgramBlockEditor value={{parameters:[],steps:[]}} onChange={noop} fieldScope="test"/>);
  expect(html).toContain(`aria-label="${translate('en-US','programs.blockUndo')}"`);
  expect(html).toContain(`aria-label="${translate('en-US','programs.blockRedo')}"`);
  expect(html).toContain('↶');expect(html).toContain('↷');
  expect(html).not.toContain(`aria-label="${translate('en-US','programs.blockInsertTarget')}"`);
  expect(html).not.toContain(`aria-label="${translate('en-US','programs.addStep')}"`);
  expect(html).not.toContain(translate('en-US','common.moveUp'));
  expect(html).not.toContain(translate('en-US','programs.blocksHint'));
 });
 it('has two phase canvases with no lead-time input outside the blocks',()=>{
  const html=render(<StationStopProgramEditor scope="station-test" value={defaultStationStopProgram('A')} onChange={noop}/>);
  expect(html.match(/class="program-block-canvas"/g)).toHaveLength(2);
  expect(html).toContain(translate('en-US','stationPrograms.arrival'));
  expect(html).toContain(translate('en-US','stationPrograms.preparation'));
  expect(html).not.toContain('<input');
  expect(html).not.toContain(`aria-label="${translate('en-US','stationPrograms.lead')}"`);
 });
});

describe('preparation entry draft recovery',()=>{
 const draft:ProgramBlockSnapshot={acceptedPreparationLead:{value:20},parameters:[],workspace:{blocks:{languageVersion:0,blocks:[{type:'rail_entry',id:'$rail-entry',inputs:{PREPARATION_LEAD:{block:preparationLeadToBlock({value:35})}},next:{block:{type:'rail_END',id:'end'}}},{type:'rail_WAIT_DURATION',id:'loose'}]}}};
 it('retains unaccepted lead and loose steps when the accepted document is unchanged',()=>{
  expect(reconcileProgramEntryLead(draft,{value:20})).toBe(draft);
  expect(blocksToPreparationLead({...draft.workspace,blocks:{...draft.workspace.blocks!,blocks:[draft.workspace.blocks!.blocks[0]]}})).toEqual({value:35});
 });
 it('uses externally changed document lead without dropping an incomplete step draft',()=>{
  const recovered=reconcileProgramEntryLead(draft,{value:10});
  expect(blocksToPreparationLead({...recovered.workspace,blocks:{...recovered.workspace.blocks!,blocks:[recovered.workspace.blocks!.blocks[0]]}})).toEqual({value:10});
  expect(recovered.workspace.blocks!.blocks[0].next).toEqual(draft.workspace.blocks!.blocks[0].next);
  expect(recovered.workspace.blocks!.blocks[1]).toBe(draft.workspace.blocks!.blocks[1]);
  expect(recovered.acceptedPreparationLead).toEqual({value:10});
  expect(blocksToPreparationLead({...draft.workspace,blocks:{...draft.workspace.blocks!,blocks:[draft.workspace.blocks!.blocks[0]]}})).toEqual({value:35});
 });
});
