import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { api } from './api';
import { LogRecord, LogsPage } from './LogsPage';
import { I18nProvider } from './i18n';
import type { ServerLogEntry, ServerLogPage } from './types';

afterEach(()=>vi.unstubAllGlobals());

describe('server log API',()=>{
  it('queries server sources without requiring a world',async()=>{
    const sources={sources:[{id:'main',label:'JavaRailways',available:true}]};
    const fetch=vi.fn().mockResolvedValue(new Response(JSON.stringify(sources)));
    vi.stubGlobal('fetch',fetch);
    await expect(api.logSources()).resolves.toEqual(sources);
    expect(fetch.mock.calls[0][0]).toBe('/api/v1/logs/sources');
  });
  it('preserves local sources and the explicit host-unavailable warning',async()=>{
    const response={sources:[{id:'webui-errors',label:'WebUI',available:true}],warning:'Host log sources unavailable'};
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue(new Response(JSON.stringify(response))));
    await expect(api.logSources()).resolves.toEqual(response);
  });
  it('encodes source, text and cursor separately and preserves bounded empty pages',async()=>{
    const page:ServerLogPage={source:'main',entries:[],nextCursor:'next',reset:false,scannedBytes:524288,retention:'current and rotated'};
    const fetch=vi.fn().mockResolvedValue(new Response(JSON.stringify(page)));
    vi.stubGlobal('fetch',fetch);
    await expect(api.logs({source:'a/b',query:'module=Core & <error>',cursor:'+/='})).resolves.toEqual(page);
    const url=new URL(fetch.mock.calls[0][0],'https://example.test');
    expect(url.pathname).toBe('/api/v1/logs');
    expect(Object.fromEntries(url.searchParams)).toEqual({source:'a/b',query:'module=Core & <error>',cursor:'+/='});
  });
  it('keeps source discovery and read failures visible instead of returning empty success',async()=>{
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue(new Response('disk unavailable',{status:502})));
    await expect(api.logSources()).rejects.toThrow('disk unavailable');
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue(new Response('read denied',{status:502})));
    await expect(api.logs({source:'host-errors'})).rejects.toThrow('read denied');
  });
  it('revokes privileged UI on unauthorized reads and does not invent an empty page',async()=>{
    const dispatchEvent=vi.fn();
    vi.stubGlobal('window',{dispatchEvent});
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue(new Response('expired',{status:401})));
    await expect(api.logs({source:'main'})).rejects.toThrow('expired');
    expect(dispatchEvent).toHaveBeenCalledOnce();
    expect(dispatchEvent.mock.calls[0][0].type).toBe('javarailways:unauthorized');
  });
});

describe('server log presentation',()=>{
  it('renders module and full cause/suppressed text as inert text',()=>{
    const entry:ServerLogEntry={module:'Core',level:'SEVERE',time:'2026-10-23T12:00:00Z',partial:false,text:'Tick failed <script>alert(1)</script>\nCaused by: java.lang.IllegalStateException: bad rail\n\tat Core.tick(Core.kt:1)\n\tSuppressed: java.io.IOException: close'};
    const html=renderToStaticMarkup(<I18nProvider><LogRecord entry={entry}/></I18nProvider>);
    expect(html).toContain('Core');
    expect(html).toContain('Caused by: java.lang.IllegalStateException: bad rail');
    expect(html).toContain('Suppressed: java.io.IOException: close');
    expect(html).toContain('&lt;script&gt;');
    expect(html).not.toContain('<script>');
    expect(html).toContain('<details>');
  });
  it('labels partial records and provides a world-independent loading view',()=>{
    const partial=renderToStaticMarkup(<I18nProvider><LogRecord entry={{text:'partial stack',module:null,time:null,level:null,partial:true}}/></I18nProvider>);
    expect(partial).toContain('ant-alert-warning');
    const html=renderToStaticMarkup(<I18nProvider><LogsPage/></I18nProvider>);
    expect(html).toContain('role="status"');
    expect(html).toContain('maxLength="200"');
  });
});
