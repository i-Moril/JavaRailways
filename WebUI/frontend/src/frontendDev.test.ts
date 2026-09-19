import { describe, expect, it } from 'vitest';
import { demoApi, isFrontendDemoEnabled } from './frontendDev';

describe('frontend development mode',()=>{
  it('can only be enabled explicitly in a development build',()=>{
    expect(isFrontendDemoEnabled(true,'true')).toBe(true);
    expect(isFrontendDemoEnabled(true,undefined)).toBe(false);
    expect(isFrontendDemoEnabled(false,'true')).toBe(false);
  });

  it('provides an authenticated, navigable demo world without a backend',async()=>{
    await expect(demoApi.session()).resolves.toMatchObject({authenticated:true,worldId:'demo-world'});
    await expect(demoApi.worlds()).resolves.toEqual([{id:'demo-world',name:'Frontend Demo World'}]);
    const lines=await demoApi.lines('demo-world');
    expect(lines.lines).toHaveLength(3);
    expect(lines.lines[0].upStationIds).not.toEqual(lines.lines[1].upStationIds);
    await expect(demoApi.editorDocuments('demo-world')).resolves.toEqual([
      expect.objectContaining({id:'weekday-demo'}),
    ]);
  });
});
