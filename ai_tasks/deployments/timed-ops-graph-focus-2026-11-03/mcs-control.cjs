const fs = require('fs');
const {io} = require('/opt/mcsmanager/web/node_modules/socket.io-client');
const config = JSON.parse(fs.readFileSync('/opt/mcsmanager/daemon/data/Config/global.json'));
const instanceUuid = '836082a48c4f48fcb1fea4e83c57dc96';
const action = process.env.JR_ACTION;
const socket = io(`http://127.0.0.1:${config.port || 24444}`, {transports:['websocket'], reconnection:false});
const timer = setTimeout(()=>{console.error('control timeout');process.exit(1)},45000);
function request(event,data) { return new Promise((resolve,reject)=>{socket.once(event,p=>p.status===200?resolve(p.data):reject(new Error(JSON.stringify(p.data))));socket.emit(event,{uuid:require('crypto').randomUUID(),status:200,event,data});}); }
socket.on('connect',async()=>{try {
  if (await request('auth',config.key)!==true) throw new Error('authentication failed');
  if(action==='status') { const r=await request('instance/detail',{instanceUuid}); console.log(JSON.stringify({instanceUuid:r.instanceUuid,status:r.status,processInfo:{pid:r.processInfo?.pid}})); }
  else if(action==='command') console.log(await request('instance/command',{instanceUuid,command:process.env.JR_COMMAND}));
  else if(action==='stop'||action==='open') console.log(await request('instance/'+action,{instanceUuids:[instanceUuid]}));
  else throw new Error('unsupported action');
  clearTimeout(timer);socket.close();
}catch(e){console.error(e.message);process.exit(1)}});
socket.on('connect_error',e=>{console.error(e.message);process.exit(1)});
