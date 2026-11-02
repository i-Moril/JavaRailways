import {afterEach,describe,expect,it,vi} from 'vitest';
import {DocumentAutosave,type AutosaveState} from './documentAutosave';
const deferred=<T>()=>{let resolve!:(value:T)=>void;let reject!:(error:Error)=>void;const promise=new Promise<T>((yes,no)=>{resolve=yes;reject=no;});return {promise,resolve,reject};};
afterEach(()=>vi.useRealTimers());
const setup=()=>{
 vi.useFakeTimers();
 const saved=vi.fn(),states:AutosaveState[]=[];
 const write=vi.fn(async(id:string,_value:string)=>({id:id||'created'}));
 const saver=new DocumentAutosave<string>({write,onSaved:saved,onState:state=>states.push(state)});
 const update=(value:string,extra:Partial<Parameters<typeof saver.update>[0]>={})=>saver.update({scope:'owner/world/0',id:'',value,baseline:JSON.stringify('initial'),enabled:true,...extra});
 return {saver,write,saved,states,update};
};
describe('serialized document autosave',()=>{
 it('does not write untouched initial, resumed, disabled or unmounted documents',async()=>{
  const {saver,write,update}=setup();update('initial');await vi.advanceTimersByTimeAsync(2000);expect(write).not.toHaveBeenCalled();
  update('loaded',{id:'existing',baseline:JSON.stringify('loaded')});await vi.advanceTimersByTimeAsync(2000);expect(write).not.toHaveBeenCalled();
  update('edited',{enabled:false});await vi.advanceTimersByTimeAsync(2000);expect(write).not.toHaveBeenCalled();
  update('edited');saver.dispose();await vi.advanceTimersByTimeAsync(2000);expect(write).not.toHaveBeenCalled();
 });
 it('debounces edits, creates once, and saves later edits using assigned identity',async()=>{
  const {write,update}=setup();update('first');await vi.advanceTimersByTimeAsync(900);update('second');await vi.advanceTimersByTimeAsync(999);expect(write).not.toHaveBeenCalled();
  await vi.advanceTimersByTimeAsync(1);expect(write).toHaveBeenNthCalledWith(1,'','second');
  update('third',{baseline:JSON.stringify('second')});await vi.advanceTimersByTimeAsync(1000);expect(write).toHaveBeenNthCalledWith(2,'created','third');
 });
 it('serializes in-flight edits and never marks the newer value saved prematurely',async()=>{
  const {write,saved,states,update}=setup(),request=deferred<{id:string}>();write.mockImplementationOnce(()=>request.promise);
  update('first');await vi.advanceTimersByTimeAsync(1000);update('second');await vi.advanceTimersByTimeAsync(3000);expect(write).toHaveBeenCalledTimes(1);
  request.resolve({id:'created'});await vi.advanceTimersByTimeAsync(0);expect(saved).toHaveBeenCalledWith('created','first');expect(states.at(-1)?.status).toBe('pending');
  await vi.advanceTimersByTimeAsync(1000);expect(write).toHaveBeenNthCalledWith(2,'created','second');expect(states.at(-1)?.status).toBe('saved');
 });
 it('retains failure without a retry loop and allows explicit retry',async()=>{
  const {saver,write,saved,states,update}=setup();write.mockRejectedValueOnce(new Error('offline'));update('edited');await vi.advanceTimersByTimeAsync(1000);
  expect(states.at(-1)).toEqual({status:'failed',error:'offline'});expect(saved).not.toHaveBeenCalled();await vi.advanceTimersByTimeAsync(10000);expect(write).toHaveBeenCalledTimes(1);
  await saver.pause();expect(await saver.flush()).toBe(true);saver.resume();expect(write).toHaveBeenCalledTimes(2);expect(saved).toHaveBeenCalledWith('created','edited');
 });
 it('flushes pending edits before switching and drains before deletion without recreating',async()=>{
  const {saver,write,update}=setup();update('old edit');await saver.pause();expect(await saver.flush()).toBe(true);
  update('new initial',{scope:'owner/world/1',baseline:JSON.stringify('new initial')});saver.resume();await vi.advanceTimersByTimeAsync(2000);expect(write).toHaveBeenCalledTimes(1);
  update('deleted edit');await saver.pause();update('empty',{scope:'owner/world/2',baseline:JSON.stringify('empty')});saver.resume();await vi.advanceTimersByTimeAsync(2000);expect(write).toHaveBeenCalledTimes(1);
 });
 it('does not adopt a late write response after owner change or unmount',async()=>{
  for(const dispose of [false,true]){
   const {saver,write,saved,update}=setup(),request=deferred<{id:string}>();write.mockImplementationOnce(()=>request.promise);
   update('first');await vi.advanceTimersByTimeAsync(1000);
   if(dispose)saver.dispose();else update('other',{scope:'other/world/0',baseline:JSON.stringify('other')});
   request.resolve({id:'old'});await vi.advanceTimersByTimeAsync(2000);expect(saved).not.toHaveBeenCalled();
  }
 });
 it('does not flush disabled dirty inputs and survives StrictMode effect replay',async()=>{
  const {saver,write,update}=setup();update('edited',{enabled:false});await saver.pause();expect(await saver.flush()).toBe(false);expect(write).not.toHaveBeenCalled();saver.resume();
  saver.dispose();saver.activate();update('edited');await vi.advanceTimersByTimeAsync(1000);expect(write).toHaveBeenCalledTimes(1);
 });
 it('keeps a failed navigation flush dirty without rescheduling an endless retry',async()=>{
  const {saver,write,update}=setup();write.mockRejectedValue(new Error('offline'));update('edited');await saver.pause();expect(await saver.flush()).toBe(false);saver.resume();await vi.advanceTimersByTimeAsync(10000);expect(write).toHaveBeenCalledTimes(1);
 });
 it('pause waits for an existing request before a manual mutation may proceed',async()=>{
  const {saver,write,update}=setup(),request=deferred<{id:string}>();write.mockImplementationOnce(()=>request.promise);update('first');await vi.advanceTimersByTimeAsync(1000);
  let drained=false;const drain=saver.pause().then(()=>{drained=true;});await vi.advanceTimersByTimeAsync(0);expect(drained).toBe(false);
  request.resolve({id:'created'});await drain;expect(drained).toBe(true);saver.dispose();
 });
});
