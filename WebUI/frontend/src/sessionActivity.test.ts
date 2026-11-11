import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { trackSessionActivity } from './sessionActivity';

describe('authenticated user activity',()=>{
  let target:EventTarget;
  let stop:()=>void;
  const valid={authenticated:true};
  const activity=(type='input',trusted=true)=>{
    const event=new Event(type);
    Object.defineProperty(event,'isTrusted',{value:trusted});
    target.dispatchEvent(event);
  };
  beforeEach(()=>{vi.useFakeTimers();target=new EventTarget();stop=()=>undefined;});
  afterEach(()=>{stop();vi.useRealTimers();});

  it('does not renew on mount, idle, scripted events or automatic scroll',async()=>{
    const renew=vi.fn().mockResolvedValue(valid);
    stop=trackSessionActivity(target,renew,vi.fn());
    activity('input',false);activity('scroll');activity('visibilitychange');
    await vi.advanceTimersByTimeAsync(600_000);
    expect(renew).not.toHaveBeenCalled();
  });

  it('renews on input and coalesces ongoing editing with a trailing renewal',async()=>{
    const renew=vi.fn().mockResolvedValue(valid);
    stop=trackSessionActivity(target,renew,vi.fn());
    activity('pointerdown');
    expect(renew).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(10_000);
    activity('keydown');activity('input');activity('wheel');
    await vi.advanceTimersByTimeAsync(49_999);
    expect(renew).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(renew).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(600_000);
    expect(renew).toHaveBeenCalledTimes(2);
    activity();
    expect(renew).toHaveBeenCalledTimes(3);
  });

  it('stops on an expired session and never authenticates it again',async()=>{
    const renew=vi.fn().mockResolvedValue({authenticated:false});
    const unauthorized=vi.fn();
    stop=trackSessionActivity(target,renew,unauthorized);
    activity();
    await vi.advanceTimersByTimeAsync(0);
    expect(unauthorized).toHaveBeenCalledOnce();
    activity();
    await vi.advanceTimersByTimeAsync(120_000);
    expect(renew).toHaveBeenCalledOnce();
  });

  it('aborts pending work and ignores its late result after logout/unmount',async()=>{
    let complete!:(value:{authenticated:boolean})=>void;
    const renew=vi.fn().mockImplementation(()=>new Promise((resolve)=>{complete=resolve;}));
    const unauthorized=vi.fn();
    stop=trackSessionActivity(target,renew,unauthorized);
    activity();activity();
    const signal=renew.mock.calls[0][0] as AbortSignal;
    stop();complete({authenticated:false});
    activity();
    await vi.advanceTimersByTimeAsync(120_000);
    expect(signal.aborted).toBe(true);
    expect(unauthorized).not.toHaveBeenCalled();
    expect(renew).toHaveBeenCalledOnce();
  });

  it('does not retry failures without new input or replay stale activity after a slow response',async()=>{
    let complete!:(value:{authenticated:boolean})=>void;
    const renew=vi.fn().mockImplementationOnce(()=>new Promise((resolve)=>{complete=resolve;})).mockRejectedValue(new Error('offline'));
    stop=trackSessionActivity(target,renew,vi.fn());
    activity();activity();
    await vi.advanceTimersByTimeAsync(120_000);
    complete(valid);
    await vi.advanceTimersByTimeAsync(0);
    expect(renew).toHaveBeenCalledOnce();
    activity();
    await vi.advanceTimersByTimeAsync(600_000);
    expect(renew).toHaveBeenCalledTimes(2);
  });
});
