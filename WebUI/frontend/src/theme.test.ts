import {describe,expect,it} from 'vitest';
import {initialTheme} from './theme';
describe('theme preference',()=>{
  it('uses a saved explicit theme',()=>{expect(initialTheme('light',true)).toBe('light');expect(initialTheme('dark',false)).toBe('dark');});
  it('falls back to browser preference',()=>{expect(initialTheme(null,true)).toBe('dark');expect(initialTheme(null,false)).toBe('light');});
});
