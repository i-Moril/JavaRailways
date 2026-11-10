import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach,describe,expect,it,vi } from 'vitest';
import { I18nProvider } from './i18n';
import { StationMetadataEditor } from './StationsPage';

afterEach(()=>vi.unstubAllGlobals());
describe('station JSON editor presentation',()=>{
  it.each(['en-US','zh-CN'])('labels JSON controls and renders arbitrary data as text in %s',(locale)=>{
    vi.stubGlobal('window',{localStorage:{getItem:()=>locale},navigator:{language:locale}});
    const html=renderToStaticMarkup(<I18nProvider><StationMetadataEditor world="world" station={{id:'station',displayName:'Station',metadata:{notice:'<script>alert(1)</script>',transfers:[{name:'Line 2'}]}}}/></I18nProvider>);
    expect(html).toContain('textarea');
    expect(html).toContain(locale==='zh-CN'?'metadata JSON':'Metadata JSON');
    expect(html).toContain(locale==='zh-CN'?'保存 metadata':'Save metadata');
    expect(html).toContain('&lt;script&gt;alert(1)&lt;/script&gt;');
    expect(html).not.toContain('<script>alert(1)</script>');
    expect(html).toContain('Line 2');
  });
});
