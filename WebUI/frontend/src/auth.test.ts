import {describe,expect,it} from 'vitest';
import {DEFAULT_VIEW,navigationForAuthentication} from './App';

describe('WebUI guest navigation',()=>{
  it('opens the rail map by default',()=>{
    expect(DEFAULT_VIEW).toBe('map');
  });

  it('exposes only the rail map without a verified session',()=>{
    expect(navigationForAuthentication(null)).toEqual(['map']);
    expect(navigationForAuthentication(false)).toEqual(['map']);
  });

  it('exposes protected pages after token authentication',()=>{
    expect(navigationForAuthentication(true)).toEqual(['map','graph','lines','scripts','simulation']);
  });
});
