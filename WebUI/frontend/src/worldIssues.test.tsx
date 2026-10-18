import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { filterWorldIssues, IssuesPage } from './IssuesPage';
import { I18nProvider } from './i18n';
import type { WorldIssue } from './types';

describe('world issues',()=>{
  it('filters current issues by object type without losing diagnostic identity',()=>{
    const train:WorldIssue={source:'models',kind:'TRAIN',objectId:'t1',code:'missing',message:'Missing model',details:'snapshot 123',firstSeen:'2026-10-17T00:00:00Z',lastSeen:'2026-10-17T00:01:00Z'};
    const signal:WorldIssue={...train,source:'signals',kind:'SIGNAL',objectId:'s1'};
    expect(filterWorldIssues([train,signal],'TRAIN')).toEqual([train]);
    expect(filterWorldIssues([train,signal],'')).toEqual([train,signal]);
  });
  it('exposes refresh and all object filters with a loading state',()=>{
    const html=renderToStaticMarkup(<I18nProvider><IssuesPage world="world"/></I18nProvider>);
    for(const type of ['WORLD','TRAIN','PROTECTION_REGION','BLOCK','SIGNAL'])expect(html).toContain(`value="${type}"`);
    expect(html).toContain('role="status"');
  });
});
