import {describe,expect,it} from 'vitest';
import {parseAuthoringRecovery} from './authoringRecovery';

const document={formatVersion:1 as const,id:'weekday',periodDays:1,runs:[],duties:[],diagramLines:[],arrivalOverrides:[]};

describe('authoring recovery',()=>{
  it('restores a valid unsaved workspace for the same world',()=>{
    const recovered=parseAuthoringRecovery(JSON.stringify({
      version:1,owner:'Alice',world:'world',documentId:'draft-1',formalScriptName:'weekday.js',persistedFormalScriptName:'',
      document,savedDocumentJson:'{"old":true}',selectedRun:'run-1',selectedDuty:'duty-1',sidebarTab:'duties',
      graphLineId:'line-1',profileAssignments:{'duty-1':'TRAIN_TEMPLATE:metro'},delayThresholdTicks:600,updatedAtEpochMillis:123,
    }),'Alice','world');

    expect(recovered?.document).toEqual(document);
    expect(recovered?.sidebarTab).toBe('duties');
    expect(recovered?.profileAssignments).toEqual({'duty-1':'TRAIN_TEMPLATE:metro'});
  });

  it('rejects corrupt, old, and cross-world recovery data',()=>{
    expect(parseAuthoringRecovery('{','Alice','world')).toBeUndefined();
    expect(parseAuthoringRecovery(JSON.stringify({version:2,owner:'Alice',world:'world',document,savedDocumentJson:'x'}),'Alice','world')).toBeUndefined();
    expect(parseAuthoringRecovery(JSON.stringify({version:1,owner:'Alice',world:'other',document,savedDocumentJson:'x'}),'Alice','world')).toBeUndefined();
    expect(parseAuthoringRecovery(JSON.stringify({version:1,owner:'Bob',world:'world',document,savedDocumentJson:'x'}),'Alice','world')).toBeUndefined();
  });
});
