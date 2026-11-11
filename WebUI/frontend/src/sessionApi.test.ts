import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from './api';
import { demoApi } from './frontendDev';

afterEach(()=>vi.unstubAllGlobals());

describe('session API',()=>{
  it('keeps read-only polling separate from explicit activity renewal',async()=>{
    const session={authenticated:true,expiresAtEpochMillis:12345};
    const fetch=vi.fn().mockImplementation(async()=>new Response(JSON.stringify(session)));
    vi.stubGlobal('fetch',fetch);
    const controller=new AbortController();
    await expect(api.session()).resolves.toEqual(session);
    await expect(api.renewSession(controller.signal)).resolves.toEqual(session);
    expect(fetch.mock.calls[0]).toEqual(['/auth/session',{headers:{'Content-Type':'application/json'}}]);
    expect(fetch.mock.calls[1]).toEqual(['/auth/session',{headers:{'Content-Type':'application/json'},method:'POST',signal:controller.signal}]);
  });

  it('uses the existing unauthorized notification without retrying expired authentication',async()=>{
    const dispatchEvent=vi.fn();
    vi.stubGlobal('window',{dispatchEvent});
    const fetch=vi.fn().mockResolvedValue(new Response('expired',{status:401}));
    vi.stubGlobal('fetch',fetch);
    await expect(api.renewSession()).rejects.toThrow('expired');
    expect(dispatchEvent).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({type:'javarailways:unauthorized'}));
    expect(fetch).toHaveBeenCalledOnce();
  });

  it('ignores a late unauthorized response after activity tracking aborts the renewal',async()=>{
    const dispatchEvent=vi.fn();
    vi.stubGlobal('window',{dispatchEvent});
    let respond!:(response:Response)=>void;
    const fetch=vi.fn().mockImplementation(()=>new Promise<Response>((resolve)=>{respond=resolve;}));
    vi.stubGlobal('fetch',fetch);
    const controller=new AbortController();
    const renewal=api.renewSession(controller.signal);
    controller.abort();
    respond(new Response('expired',{status:401}));
    await expect(renewal).rejects.toThrow('expired');
    expect(dispatchEvent).not.toHaveBeenCalled();
  });

  it('supports activity renewal in the explicit frontend demo',async()=>{
    const renew=demoApi.renewSession;
    await expect(renew()).resolves.toMatchObject({authenticated:true,worldId:'demo-world'});
  });
});
