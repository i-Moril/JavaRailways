import { afterEach, describe, expect, it, vi } from 'vitest';
import { parseTrainFrame, trainFrameIsStale } from './railMap';
const train={id:'t',name:'Train',revision:1,speedMetersPerSecond:0,derailed:false,reversed:false,railId:'rail',arcLengthMeters:1,facing:'AB',cars:[{id:'car',lengthMeters:20,x:0,y:0,z:0,directionX:1,directionZ:0}]};
afterEach(()=>vi.useRealTimers());
describe('train frame freshness',()=>{
  it('expires exactly three seconds after the last valid frame and resumes on new data',()=>{
    vi.useFakeTimers();vi.setSystemTime(10_000);let last:number|null=null;
    expect(trainFrameIsStale(last,Date.now())).toBe(true);
    parseTrainFrame(JSON.stringify([train]));last=Date.now();
    vi.advanceTimersByTime(2_999);expect(trainFrameIsStale(last,Date.now())).toBe(false);
    vi.advanceTimersByTime(1);expect(trainFrameIsStale(last,Date.now())).toBe(true);
    parseTrainFrame('[]');last=Date.now();expect(trainFrameIsStale(last,Date.now())).toBe(false);
  });
  it('rejects malformed JSON, wrong shapes and nonfinite or missing render fields',()=>{
    for(const value of ['{','{}','null','[{}]',JSON.stringify([{...train,cars:[{...train.cars[0],x:null}]}]),JSON.stringify([{...train,facing:'bad'}])])expect(()=>parseTrainFrame(value)).toThrow();
    expect(parseTrainFrame(JSON.stringify([train]))).toEqual([train]);
    expect(parseTrainFrame('[]')).toEqual([]);
  });
});
