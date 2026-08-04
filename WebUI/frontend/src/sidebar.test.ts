import {describe,expect,it} from 'vitest';
import {clampAuthoringSidebarWidth} from './DutyEditor';

describe('authoring sidebar sizing',()=>{
  it('keeps the editor usable while protecting graph space',()=>{
    expect(clampAuthoringSidebarWidth(100,1_400)).toBe(360);
    expect(clampAuthoringSidebarWidth(700,1_400)).toBe(700);
    expect(clampAuthoringSidebarWidth(2_000,1_200)).toBe(880);
    expect(clampAuthoringSidebarWidth(2_000,2_000)).toBe(960);
  });
});
