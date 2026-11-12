import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach,describe,expect,it,vi } from 'vitest';
import { I18nProvider } from './i18n';
import { MetadataJsonEditor } from './MetadataJsonEditor';
import { metadataConventionFields,updateMetadataField,updateMetadataTranslation } from './metadataConventions';

afterEach(()=>vi.unstubAllGlobals());
describe('metadata display conventions',()=>{
  it('edits just one field or translation while preserving arbitrary JSON and other languages',()=>{
    const original=JSON.parse('{"name":{"zh":"中央","en":"Central","ja":"中央駅","custom":{"keep":true}},"transfers":["branch","unknown"],"custom":{"a":[1,null]},"__proto__":{"keep":true}}');
    const translated=updateMetadataTranslation(original,'name','en','New Central');
    expect(translated).toEqual({...original,name:{...original.name,en:'New Central'}});
    expect(original.name.en).toBe('Central');
    const removed=updateMetadataField(translated,'transfers',undefined);
    expect(removed.transfers).toBeUndefined();
    expect(removed.custom).toEqual(original.custom);
    expect(Object.hasOwn(removed,'__proto__')).toBe(true);
    expect(updateMetadataField(removed,'transfers',[]).transfers).toEqual([]);
    expect(updateMetadataTranslation({},'name','zh','').name).toEqual({zh:''});
  });
  it.each(['en-US','zh-CN'])('renders applicable fields and scoped convention help in %s',(locale)=>{
    vi.stubGlobal('window',{localStorage:{getItem:()=>locale},navigator:{language:locale}});
    const render=(scope:'station'|'line'|'run',draft='{}')=>renderToStaticMarkup(<I18nProvider><MetadataJsonEditor scope={scope} draft={draft} onChange={()=>{}}/></I18nProvider>);
    const station=render('station');
    expect(station).toContain(locale==='zh-CN'?'查看 metadata 约定':'View metadata conventions');
    for(const field of metadataConventionFields.station)expect(station).toContain(`<code>${field}</code>`);
    expect(station).not.toContain('<code>class</code>');
    expect(render('line')).toContain('<code>themeColor</code>');
    expect(render('run')).toContain('<code>class</code>');
    const unsupported=render('station','{"name":42,"transfers":[{"lineId":"x"}],"custom":"<script>bad</script>"}');
    expect(unsupported).toContain(locale==='zh-CN'?'请使用下方 JSON':'needs editing in the JSON');
    expect(unsupported).not.toContain('<script>bad</script>');
    const invalid=render('station','{');
    expect(invalid).toContain('textarea');
    expect(invalid).not.toContain('metadata-convention-fields');
  });
});
