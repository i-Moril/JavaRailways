import { describe,expect,it } from 'vitest';
import { formatTimetableTick } from './timetableTime';

describe('formatTimetableTick',()=>{
  it('keeps ticks authoritative while translating the Minecraft clock',()=>{
    expect(formatTimetableTick(0,'en-US')).toBe('D1 06:00');
    expect(formatTimetableTick(1_000,'en-US')).toBe('D1 07:00');
    expect(formatTimetableTick(18_000,'zh-CN')).toBe('第2天 00:00');
  });
});
