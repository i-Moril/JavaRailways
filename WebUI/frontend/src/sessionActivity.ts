import type { WebSessionStatus } from './types';

const RENEW_INTERVAL_MS=60_000;
const ACTIVITY_EVENTS=['pointerdown','keydown','input','wheel'] as const;

/** Renew only after user input; background polling and idle tabs never keep a session alive. */
export function trackSessionActivity(
  target:EventTarget,
  renew:(signal:AbortSignal)=>Promise<WebSessionStatus>,
  unauthorized:()=>void,
):()=>void {
  let stopped=false;
  let lastAttempt:number|undefined;
  let pendingActivity=false;
  let lastActivity=0;
  let inFlight=false;
  let timer:ReturnType<typeof setTimeout>|undefined;
  const controller=new AbortController();

  const stop=()=>{
    stopped=true;
    controller.abort();
    if(timer!==undefined)clearTimeout(timer);
    for(const event of ACTIVITY_EVENTS)target.removeEventListener(event,onActivity,true);
  };
  const schedule=()=>{
    if(stopped||inFlight||timer!==undefined||!pendingActivity)return;
    if(Date.now()-lastActivity>RENEW_INTERVAL_MS){pendingActivity=false;return;}
    const delay=lastAttempt===undefined?0:Math.max(0,lastAttempt+RENEW_INTERVAL_MS-Date.now());
    if(delay>0){timer=setTimeout(()=>{timer=undefined;schedule();},delay);return;}
    pendingActivity=false;
    lastAttempt=Date.now();
    inFlight=true;
    void renew(controller.signal).then((session)=>{
      if(!stopped&&!session.authenticated){stop();unauthorized();}
    }).catch(()=>undefined).finally(()=>{inFlight=false;schedule();});
  };
  function onActivity(event:Event){
    // Layout/focus scrolls are excluded; deliberate scrolling starts with wheel, key or pointer input.
    if(!event.isTrusted)return;
    pendingActivity=true;
    lastActivity=Date.now();
    schedule();
  }
  for(const event of ACTIVITY_EVENTS)target.addEventListener(event,onActivity,{capture:true,passive:true});
  return stop;
}
