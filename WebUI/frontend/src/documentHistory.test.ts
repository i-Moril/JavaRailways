import { describe,expect,it } from 'vitest';
import { createDocumentHistory,redoDocumentHistory,undoDocumentHistory,updateDocumentHistory } from './documentHistory';

describe('document history',()=>{
  it('undoes and redoes immutable document snapshots',()=>{
    const initial=createDocumentHistory({id:'a'});
    const changed=updateDocumentHistory(initial,{id:'b'});
    const undone=undoDocumentHistory(changed);
    const redone=redoDocumentHistory(undone);

    expect(undone.present.id).toBe('a');
    expect(redone.present.id).toBe('b');
  });

  it('clears redo state on a new edit and bounds retained snapshots',()=>{
    let history=createDocumentHistory(0);
    history=updateDocumentHistory(history,1,2);
    history=updateDocumentHistory(history,2,2);
    history=undoDocumentHistory(history);
    history=updateDocumentHistory(history,3,2);

    expect(history.future).toEqual([]);
    expect(history.past).toHaveLength(2);
    expect(history.present).toBe(3);
  });
});
