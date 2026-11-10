import {expect,it} from 'vitest';
import {metadataValueResult,parseMetadata} from './metadata';
import {parseStationMetadata} from './stationMetadata';

it('shares object-only parsing without evaluating or merging custom keys',()=>{
 const text='{"__proto__":{"polluted":true},"transfers":[{"lineId":"branch"}],"unknown":{"enabled":true,"amount":2.5}}';
 const parsed=parseMetadata(text);
 expect(parsed.error).toBeUndefined();expect(parsed).toEqual(parseStationMetadata(text));
 expect(JSON.stringify(parsed.value)).toBe(text);expect(({} as {polluted?:boolean}).polluted).toBeUndefined();
 for(const text of ['{','null','[]','1','true','"text"','{"overflow":1e999}'])expect(parseMetadata(text).error).toBeDefined();
 expect(metadataValueResult({bad:Infinity}).error).toBe('finiteNumbers');
});
