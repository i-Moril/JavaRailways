import { afterEach,describe,expect,it,vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { filterWorldIssues,issueKinds,IssuesPage,pollWorldIssues } from './IssuesPage';
import { api } from './api';
import { I18nProvider } from './i18n';
import type { WorldIssue } from './types';
const train:WorldIssue={source:'models',kind:'TRAIN',objectId:'t1',code:'missing',message:'Missing model',details:'snapshot 123',firstSeen:'2026-10-17T00:00:00Z',lastSeen:'2026-10-17T00:01:00Z'};
const deferred=()=>{let resolve!:(issues:WorldIssue[])=>void;let reject!:(error:Error)=>void;const promise=new Promise<WorldIssue[]>((yes,no)=>{resolve=yes;reject=no;});return {promise,resolve,reject};};
const callbacks=()=>({onIssues:vi.fn(),onError:vi.fn(),onLoading:vi.fn()});
afterEach(()=>{vi.useRealTimers();vi.unstubAllGlobals();});
describe('world issues',()=>{
  it('filters all upstream object kinds without losing diagnostic identity',()=>{
    const signal:WorldIssue={...train,source:'signals',kind:'SIGNAL',objectId:'s1'};
    expect(issueKinds).toEqual(['WORLD','TRAIN','PROTECTION_REGION','BLOCK','SIGNAL']);
    expect(filterWorldIssues([train,signal],'TRAIN')).toEqual([train]);expect(filterWorldIssues([train,signal],'')).toEqual([train,signal]);
  });
  it('uses studio controls with an explicit initial loading status',()=>{
    const html=renderToStaticMarkup(<I18nProvider><IssuesPage world="world"/></I18nProvider>);
    expect(html).toContain('Object type');expect(html).toContain('Refresh');expect(html).toContain('role="status"');expect(html).toContain('Loading issues');expect(html).toContain('ant-select');expect(html).toContain('ant-table');expect(html).not.toContain('No unresolved issues.');
  });
  it('GETs the current world issues through the encoded protected API path',async()=>{
    const fetch=vi.fn().mockResolvedValue(new Response(JSON.stringify([train]),{status:200}));vi.stubGlobal('fetch',fetch);
    expect(await api.issues('world/a b')).toEqual([train]);
    expect(fetch).toHaveBeenCalledWith('/api/v1/worlds/world%2Fa%20b/issues',expect.objectContaining({headers:{'Content-Type':'application/json'}}));
    expect(fetch.mock.calls[0][1].method).toBeUndefined();
  });
  it('uses the shared unauthorized event rather than treating a failed request as an empty result',async()=>{
    const dispatchEvent=vi.fn();vi.stubGlobal('window',{dispatchEvent});vi.stubGlobal('fetch',vi.fn().mockResolvedValue(new Response('Session expired',{status:401})));
    await expect(api.issues('world')).rejects.toThrow('Session expired');expect(dispatchEvent).toHaveBeenCalledWith(expect.objectContaining({type:'javarailways:unauthorized'}));
  });
  it('does not overlap manual or five-second refreshes while the current request is pending',async()=>{
    vi.useFakeTimers();const request=deferred(),next=deferred(),load=vi.fn().mockReturnValueOnce(request.promise).mockReturnValue(next.promise),events=callbacks();
    const poller=pollWorldIssues('world',events,load);
    expect(load).toHaveBeenCalledTimes(1);await poller.refresh();await vi.advanceTimersByTimeAsync(15_000);expect(load).toHaveBeenCalledTimes(1);
    request.resolve([train]);await vi.advanceTimersByTimeAsync(0);expect(events.onIssues).toHaveBeenCalledExactlyOnceWith([train]);
    await vi.advanceTimersByTimeAsync(5_000);expect(load).toHaveBeenCalledTimes(2);
    poller.stop();next.resolve([]);await vi.advanceTimersByTimeAsync(10_000);expect(events.onIssues).toHaveBeenCalledTimes(1);expect(load).toHaveBeenCalledTimes(2);
  });
  it('discards a previous world response after cancellation',async()=>{
    vi.useFakeTimers();const old=deferred(),oldEvents=callbacks(),newEvents=callbacks();
    const prior=pollWorldIssues('old',oldEvents,()=>old.promise);prior.stop();
    const current=pollWorldIssues('new',newEvents,async()=>[]);await vi.advanceTimersByTimeAsync(0);old.resolve([train]);await vi.advanceTimersByTimeAsync(0);
    expect(oldEvents.onIssues).not.toHaveBeenCalled();expect(oldEvents.onError).not.toHaveBeenCalled();expect(newEvents.onIssues).toHaveBeenCalledExactlyOnceWith([]);current.stop();
  });
  it('keeps the last successful snapshot on poll failure and can refresh afterward',async()=>{
    vi.useFakeTimers();const events=callbacks(),load=vi.fn().mockResolvedValueOnce([train]).mockRejectedValueOnce(new Error('Bridge unavailable')).mockResolvedValue([]);
    const poller=pollWorldIssues('world',events,load);await vi.advanceTimersByTimeAsync(0);await vi.advanceTimersByTimeAsync(5_000);
    expect(events.onIssues).toHaveBeenCalledExactlyOnceWith([train]);expect(events.onError).toHaveBeenLastCalledWith('Bridge unavailable');
    await poller.refresh();expect(events.onIssues).toHaveBeenLastCalledWith([]);expect(events.onError).toHaveBeenLastCalledWith('');poller.stop();
  });
});
