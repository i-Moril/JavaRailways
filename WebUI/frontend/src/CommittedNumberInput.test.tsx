import { renderToStaticMarkup } from 'react-dom/server';
import { describe,expect,it } from 'vitest';
import { CommittedNumberInput,parseNumberDraft } from './CommittedNumberInput';
import { I18nProvider } from './i18n';
describe('committed numeric drafts',()=>{
  it('accepts signed offsets and optional empty times without clamping',()=>{
    expect(parseNumberDraft('-120',undefined,undefined,0)).toBe(-120);
    expect(parseNumberDraft(' 240 ',0,23999,0)).toBe(240);
    expect(parseNumberDraft('',0,23999,0)).toBeNull();
  });
  it('rejects partial, fractional, nonfinite or out-of-range ticks',()=>{
    for(const raw of ['-','1.5','NaN','Infinity','24000','-1'])expect(()=>parseNumberDraft(raw,0,23999,0)).toThrow();
  });
  it('renders the current time without committing while mounting a new field',()=>{
    let calls=0;
    const html=renderToStaticMarkup(<I18nProvider><CommittedNumberInput fieldKey="world/document/run/call/arrival" aria-label="Arrival tick" value={120} precision={0} onCommit={()=>calls++}/></I18nProvider>);
    expect(html).toContain('Arrival tick');expect(html).toContain('value="120"');expect(calls).toBe(0);
  });
});
