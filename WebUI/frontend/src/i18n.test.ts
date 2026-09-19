import { describe, expect, it } from 'vitest';
import { normalizeLocale, translate } from './i18n';

describe('i18n', () => {
  it('normalizes Chinese browser locales and falls back to English', () => {
    expect(normalizeLocale('zh-CN')).toBe('zh-CN');
    expect(normalizeLocale('zh-TW')).toBe('zh-CN');
    expect(normalizeLocale('en-GB')).toBe('en-US');
    expect(normalizeLocale(undefined)).toBe('en-US');
  });

  it('translates stable UI keys without changing serialized enum values', () => {
    expect(translate('zh-CN', 'actions.REVERSE')).toBe('换向');
    expect(translate('en-US', 'actions.REVERSE')).toBe('Reverse');
  });

  it('names the authoring workspace as the timetable page in both locales', () => {
    expect(translate('zh-CN', 'nav.graph')).toBe('时刻表');
    expect(translate('en-US', 'nav.graph')).toBe('Timetable');
  });
});
