import { afterEach,describe,expect,it,vi } from 'vitest';
import { api } from './api';

afterEach(()=>vi.unstubAllGlobals());
describe('station metadata API',()=>{
  it('encodes world and station IDs and sends both current JSON and exact baseline',async()=>{
    const station={id:'station/one',displayName:'Station One',metadata:{transfers:[{line:'two'}]}};
    const fetch=vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({stations:[station]})))
      .mockResolvedValueOnce(new Response(JSON.stringify(station)));
    vi.stubGlobal('fetch',fetch);
    await expect(api.stations('world/one')).resolves.toEqual({stations:[station]});
    await expect(api.saveStationMetadata('world/one',station.id,station.metadata,{custom:{old:true}})).resolves.toEqual(station);
    expect(fetch.mock.calls[0][0]).toBe('/api/v1/worlds/world%2Fone/stations');
    expect(fetch.mock.calls[1][0]).toBe('/api/v1/worlds/world%2Fone/stations/station%2Fone/metadata');
    expect(fetch.mock.calls[1][1].method).toBe('PUT');
    expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual({metadata:station.metadata,expectedMetadata:{custom:{old:true}}});
  });
  it('preserves conflict and missing-station responses without retries or implicit overwrite',async()=>{
    const fetch=vi.fn().mockResolvedValueOnce(new Response('{"error":"metadata changed","code":"CONFLICT"}',{status:409}))
      .mockResolvedValueOnce(new Response('{"error":"station missing","code":"NOT_FOUND"}',{status:404}));
    vi.stubGlobal('fetch',fetch);
    await expect(api.saveStationMetadata('world','station',{notice:'draft'},{})).rejects.toMatchObject({status:409,code:'CONFLICT'});
    await expect(api.saveStationMetadata('world','station',{notice:'draft'},{})).rejects.toMatchObject({status:404,code:'NOT_FOUND'});
    expect(fetch).toHaveBeenCalledTimes(2);
  });
});
