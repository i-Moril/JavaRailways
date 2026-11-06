import {afterEach,describe,expect,it,vi} from 'vitest';
import {ApiError,parseApiError,simulationFailureReason} from './apiErrors';
import {api} from './api';
afterEach(()=>vi.unstubAllGlobals());
describe('API error protocol and localized presentation',()=>{
 it('explains infeasible line dwell and retains the actual station and timing diagnostic',()=>{
  const error=parseApiError(422,JSON.stringify({code:'LINE_DWELL_INSUFFICIENT',error:'shiomi: required 500 ticks, supplied 400 ticks'}));
  expect(error.localized('zh-CN')).toBe('模拟用停站时间不足，无法回填；原时刻表未改变。\n详细原因: shiomi: required 500 ticks, supplied 400 ticks');
  expect(error.localized('en-US')).toContain('Timings were not applied');
  expect(error.localized('en-US')).toContain('shiomi: required 500 ticks, supplied 400 ticks');
 });
 it('uses real stable error codes in both configured languages without displaying JSON',()=>{
  const error=parseApiError(504,JSON.stringify({error:'request timed out',code:'BRIDGE_TIMEOUT',requestId:'abc-123'}));
  expect(error.localized('zh-CN')).toBe('等待服务器桥接响应超时。\n日志追踪编号: abc-123');
  expect(error.localized('en-US')).toBe('Timed out waiting for the server bridge.\nLog reference: abc-123');
  expect(error.localized('zh-CN')).not.toContain('storage');
  const domainTimeout=parseApiError(504,'{"error":"request expired before entering the simulation context","code":"REQUEST_TIMEOUT"}');
  expect(domainTimeout.localized('zh-CN')).toBe('等待服务器响应超时。');
  expect(domainTimeout.localized('en-US')).toBe('Timed out waiting for the server response.');
 });
 it('retains actual validation reasons instead of substituting an unrelated infrastructure failure',()=>{
  const error=parseApiError(422,JSON.stringify({error:'No unique placement at dpt@1',code:'SIMULATION_REJECTED'}));
  expect(error.localized('zh-CN')).toBe('无法执行这次模拟。\n详细原因: No unique placement at dpt@1');
  expect(parseApiError(400,'{"error":"missing profile"}').localized('en-US')).toContain('Details: missing profile');
 });
 it('handles unknown codes and nonJSON responses without rendering serialized objects or proxy HTML',()=>{
  expect(parseApiError(422,'{"code":"toString","error":"Reason"}').localized('en-US')).toBe('Request failed (HTTP 422)\nDetails: Reason');
  expect(parseApiError(418,'{"code":"TEAPOT","error":"A precise reason"}').localized('en-US')).toBe('Request failed (HTTP 418)\nDetails: A precise reason');
  expect(parseApiError(500,'<html>proxy error</html>').localized('zh-CN')).toBe('服务器处理请求时发生内部错误。');
  expect(parseApiError(400,'[{"error":"bad"}]').localized('en-US')).toBe('The request is invalid.');
  expect(parseApiError(403,'').localized('zh-CN')).toBe('没有执行此操作的权限。');
  expect(parseApiError(429,'').localized('en-US')).toBe('Too many requests. Try again later.');
  expect(parseApiError(504,'').localized('en-US')).toBe('Timed out waiting for the server response.');
  expect(parseApiError(422,'Plain diagnostic').localized('en-US')).toContain('Plain diagnostic');
 });
 it('reads configured locale when the error is consumed and survives unavailable local storage',()=>{
  const getItem=vi.fn().mockReturnValue('zh-CN');vi.stubGlobal('window',{localStorage:{getItem},navigator:{language:'en-US'}});
  const error=new ApiError(504,'SIMULATION_TIMEOUT');expect(error.message).toBe('模拟超过了服务器执行时间限制。');
  getItem.mockReturnValue('en-US');expect(error.message).toBe('Simulation exceeded the server execution time limit.');
  getItem.mockImplementation(()=>{throw new Error('blocked');});expect(error.message).toBe('Simulation exceeded the server execution time limit.');
 });
 it('preserves unauthorized dispatch, structured failures, network failures and 204 success through the real API',async()=>{
  const dispatchEvent=vi.fn();vi.stubGlobal('window',{dispatchEvent,localStorage:{getItem:()=> 'zh-CN'},navigator:{language:'en-US'}});
  const fetch=vi.fn().mockResolvedValueOnce(new Response('{"error":"session expired","code":"AUTH_REQUIRED"}',{status:401}))
   .mockRejectedValueOnce(new TypeError('Failed to fetch')).mockResolvedValueOnce(new Response(null,{status:204}));
  vi.stubGlobal('fetch',fetch);
  await expect(api.session()).rejects.toMatchObject({message:'请先登录。\n详细原因: session expired',status:401,code:'AUTH_REQUIRED'});
  expect(dispatchEvent).toHaveBeenCalledOnce();expect(dispatchEvent.mock.calls[0][0].type).toBe('javarailways:unauthorized');
  await expect(api.worlds()).rejects.toMatchObject({message:'网络请求失败，请检查连接。'});
  await expect(api.publish('world',{} as never)).resolves.toBeUndefined();
 });
 it('routes login exchange through the same localized error protocol and preserves the wire contract',async()=>{
  vi.stubGlobal('window',{dispatchEvent:vi.fn(),localStorage:{getItem:()=> 'zh-CN'},navigator:{language:'en-US'}});
  const fetch=vi.fn().mockResolvedValueOnce(new Response('{"error":"bridge wait failed","code":"BRIDGE_TIMEOUT","requestId":"login-123"}',{status:504}))
   .mockResolvedValueOnce(new Response(null,{status:204}));vi.stubGlobal('fetch',fetch);
  await expect(api.exchangeLoginToken('one-time-token')).rejects.toMatchObject({message:'等待服务器桥接响应超时。\n日志追踪编号: login-123'});
  expect(fetch).toHaveBeenNthCalledWith(1,'/auth/exchange',{method:'POST',headers:{'Content-Type':'application/json'},body:'{"token":"one-time-token"}'});
  await expect(api.exchangeLoginToken('replacement-token')).resolves.toBeUndefined();
 });
 it('localizes failed response body reads instead of leaking a native stream exception',async()=>{
  vi.stubGlobal('window',{dispatchEvent:vi.fn(),localStorage:{getItem:()=> 'zh-CN'},navigator:{language:'en-US'}});
  const response=new Response('partial body',{status:502});vi.spyOn(response,'text').mockRejectedValue(new TypeError('ReadableStream terminated'));
  vi.stubGlobal('fetch',vi.fn().mockResolvedValue(response));
  await expect(api.worlds()).rejects.toMatchObject({code:'NETWORK_ERROR',status:502,message:'网络请求失败，请检查连接。'});
 });
 it('reports unreadable success bodies without exposing JSON parsing exceptions',async()=>{
  vi.stubGlobal('fetch',vi.fn().mockResolvedValue(new Response('<html>bad proxy</html>',{status:200})));
  await expect(api.worlds()).rejects.toMatchObject({code:'INVALID_RESPONSE'});
 });
 it('localizes structured simulation limits and retains nonstructured execution diagnostics',()=>{
  expect(simulationFailureReason('zh-CN','tick limit reached','SIMULATION_LIMIT_REACHED')).toBe('已达到模拟推进上限。\n详细原因: tick limit reached');
  expect(simulationFailureReason('en-US','Cannot reverse while doors open')).toBe('Details: Cannot reverse while doors open');
  expect(simulationFailureReason('zh-CN','{"error":"raw"}')).toBeUndefined();
 });
});
