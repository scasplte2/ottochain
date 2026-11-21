import {Node, makeScene2D} from '@motion-canvas/2d';
import {createSignal, tween} from '@motion-canvas/core';
import facts from './scene-data.json';

const P = {bg:'#0e191d',panel:'#17272c',line:'#395054',paper:'#f6efd9',white:'#eef1de',muted:'#a2b5af',
  green:'#a9e6ad',red:'#ff796c',pink:'#ed9acb',gold:'#f6d57e',orange:'#ffb276'};
const wires = {signature:P.gold,amount:P.orange,payee:P.pink,logic:P.green};
const clamp = (x:number) => Math.min(1, Math.max(0,x));
function ramp(t:number, start:number, duration:number) {
  const u=clamp((t-start)/duration); return u*u*(3-2*u);
}
type C = CanvasRenderingContext2D;
function box(c:C,x:number,y:number,w:number,h:number,r:number,fill:string,edge?:string) {
  c.beginPath(); c.roundRect(x,y,w,h,r); c.fillStyle=fill; c.fill();
  if(edge){c.strokeStyle=edge;c.lineWidth=1.5;c.stroke();}
}
function text(c:C,s:string,x:number,y:number,size:number,color=P.white,weight=500,mono=false) {
  c.font=weight+' '+size+'px '+(mono?'"Ubuntu Mono", monospace':'"Ubuntu Sans", Ubuntu, sans-serif');
  c.fillStyle=color;c.fillText(s,x,y);
}
function path(c:C,points:number[][],color:string,width=2) {
  c.strokeStyle=color;c.lineWidth=width;c.lineJoin='round';c.lineCap='round';c.beginPath();
  points.forEach(([x,y],i)=>i?c.lineTo(x,y):c.moveTo(x,y));c.stroke();
}
function circle(c:C,x:number,y:number,r:number,color:string) {
  c.beginPath();c.arc(x,y,r,0,Math.PI*2);c.fillStyle=color;c.fill();
}
function light(c:C,points:number[][],fraction:number,color:string) {
  if(fraction<=0 || fraction>=1)return;
  const lengths=points.slice(1).map(([x,y],i)=>Math.hypot(x-points[i][0],y-points[i][1]));
  let distance=fraction*lengths.reduce((a,b)=>a+b,0);
  for(let i=0;i<lengths.length;i++){
    if(distance<=lengths[i] && lengths[i]>0){
      const u=distance/lengths[i], [x,y]=points[i], [dx,dy]=points[i+1];
      c.save();c.shadowBlur=18;c.shadowColor=color;
      circle(c,x+(dx-x)*u,y+(dy-y)*u,5,color);c.restore();return;
    }
    distance-=lengths[i];
  }
}
function mark(c:C,x:number,y:number,pass:boolean,color:string) {
  if(pass) path(c,[[x-5,y],[x-1,y+4],[x+6,y-5]],color,2.8);
  else {path(c,[[x-5,y-5],[x+5,y+5]],color,2.8);path(c,[[x+5,y-5],[x-5,y+5]],color,2.8);}
}
function receiptCard(c:C,x:number,y:number,scale:number) {
  c.save();c.translate(x,y);c.scale(scale,scale);
  c.shadowColor='#030a0b80';c.shadowBlur=20;c.shadowOffsetY=6;
  c.beginPath();c.moveTo(0,0);c.lineTo(265,0);c.lineTo(300,35);c.lineTo(300,282);
  for(let i=20;i>=0;i--)c.lineTo(i*15,282-(i%2)*6);
  c.closePath();c.fillStyle=P.paper;c.fill();c.shadowBlur=0;c.shadowOffsetY=0;
  path(c,[[265,1],[265,35],[299,35]],'#948f7a',1.5);
  text(c,'SIGNED REQUEST',24,40,17,'#4d5c55',600,true);
  text(c,'$'+facts.request.amountCents/100,20,120,81,'#142828',730);
  path(c,[[24,139],[276,139]],'#a3aa98',1);
  text(c,'PAY TO',24,172,17,'#48584d',600,true);
  text(c,'“'+facts.request.payee+'”',20,212,40,'#41233a',700);
  text(c,'Agent key  /  request '+facts.request.requestNumber,24,255,17,'#43574a',600,true);
  c.restore();
}
function hud(c:C,t:number) {
  text(c,'OTTOCHAIN',54,53,23,P.white,750);
  path(c,[[212,30],[212,57]],P.line);
  text(c,'AGENT MANDATE',233,52,18,P.muted,600,true);
  text(c,'Animation of recorded ledger events',1080,51,20,P.muted,500);
  path(c,[[54,76],[1546,76]],P.line,1);
  const parts=t<4.2?['A signed test request names a supplier outside the mandate.']:
    t<8.1?['The signature is valid. The amount fits the remaining budget.']:
    t<13.2?['Every clause must pass. The supplier check returns false.']:
    t<16.3?['The owner allowed supplier-a and supplier-b. The request names “other”.']:
    ['The ledger refused the request at #'+facts.ordinal+'. The test budget stayed at $'+facts.balance+'.'];
  path(c,[[54,809],[1546,809]],P.line,1);
  text(c,parts[0],55,863,31,P.white,550);
  path(c,[[54,889],[1546,889]],'#27393d',2);
  path(c,[[54,889],[54+1492*t/20,889]],P.gold,2);
}
function board(c:C,t:number) {
  const reject=10.3,fall=ramp(t,reject,0.4);
  text(c,t<16?'Authorize a payment':'Payment refused',54,157,62,t<16?P.white:P.red,700);
  text(c,'Local JLVM replay of the active mandate',57,202,26,P.muted);
  // Fan-out is a schematic of independent clause replay, not a validator instruction trace.
  const x=568,w=365,top=273,step=49;
  text(c,'SIGNED INPUT',57,253,17,P.gold,550,true);
  text(c,'AUTHORIZE  /  ALL CLAUSES REQUIRED',x,251,17,P.muted,550,true);
  receiptCard(c,56,298,0.96);
  const feeds=[{y:342,key:'signature'}, {y:403,key:'amount'},{y:478,key:'payee'},{y:539,key:'logic'}];
  feeds.forEach(({y,key},i)=>{
    const trunk=398+i*25, color=wires[key as keyof typeof wires];
    const rows=facts.gates.flatMap((g,j)=>g.wire===key?[top+j*step+18]:[]);
    path(c,[[345,y],[trunk,y]],color,2);
    if(key==='signature')path(c,[[345,y+5],[trunk-5,y+5],[trunk-5,Math.max(...rows)]],color,1);
    path(c,[[trunk,Math.min(y,...rows)],[trunk,Math.max(y,...rows)]],color,1.7);
    rows.forEach(gy=>{path(c,[[trunk,gy],[x-4,gy]],color,1.8);circle(c,trunk,gy,3,color);});
  });
  const bus=992, hubY=top+4*step+18;
  path(c,[[bus,top+18],[bus,top+7*step+18]],P.line,2);
  facts.gates.forEach((g,i)=>{
    const y=top+i*step,cy=y+18, color=wires[g.wire as keyof typeof wires];
    const input=feeds.findIndex(f=>f.key===g.wire), trunk=398+input*25,fy=feeds[input].y;
    const when=g.pass?5.9+i*.43:reject;
    const active=t>=when, result=g.pass?P.green:P.red;
    const route=[[345,fy],[trunk,fy],[trunk,cy],[x,cy]];
    light(c,route,(t-when+1.35)/1.35,color);
    box(c,x,y,w,36,7,active?(g.pass?'#223730':'#4b292b'):P.panel,active?result:P.line);
    circle(c,x+16,cy,3,color);
    text(c,g.label,x+33,y+25,22,active?P.white:P.muted,570);
    if(active)mark(c,x+w-22,cy,g.pass,result);
    path(c,[[x+w,cy],[bus,cy]],active?result:P.line,1.7);
    if(active)light(c,[[x+w,cy],[bus,cy],[bus,hubY],[1030,hubY]],(t-when)/0.85,result);
    if(!g.pass && t>=reject && t<reject+.7){
      const u=(t-reject)/.7;c.save();c.globalAlpha=1-u;
      for(let k=0;k<10;k++){
        const angle=k*Math.PI/5, radius=14+u*35, xx=x+w-22;
        path(c,[[xx+radius*Math.cos(angle),cy+radius*Math.sin(angle)],
          [xx+(radius+8)*Math.cos(angle),cy+(radius+8)*Math.sin(angle)]],P.red,2);
      }c.restore();
    }
  });
  path(c,[[bus,hubY],[1034,hubY]],fall?P.red:P.line,3);
  c.beginPath();c.moveTo(1034,hubY-28);c.lineTo(1054,hubY-28);c.arc(1054,hubY,28,-Math.PI/2,Math.PI/2);
  c.lineTo(1034,hubY+28);c.closePath();c.fillStyle=fall?'#522d2e':P.panel;c.fill();
  c.strokeStyle=fall?P.red:P.muted;c.stroke();text(c,'&',1043,hubY+10,29,fall?P.red:P.white,700);
  path(c,[[1083,hubY],[1134,hubY]],P.line,2);
  if(fall)mark(c,1111,hubY,false,P.red);
  box(c,1137,272,409,316,17,P.panel,P.line);
  text(c,'REMAINING TEST BUDGET',1166,317,20,P.muted,600,true);
  text(c,'$'+facts.balance,1162,443,111,P.white,710);
  for(let i=0;i<20;i++) box(c,1169+i*17,471,12,27,2,i<20*facts.balance/facts.startBudget?P.green:'#314246');
  text(c,fall?'No debit recorded':'Available before request',1168,548,23,fall?P.green:P.muted);
  text(c,'SNAPSHOTS',57,699,17,P.muted,600,true);
  const strip=[[59,738],[981,738]];path(c,strip,P.line,2);
  const positions=[138,458,813], nums=[facts.previous,facts.ordinal,facts.checkpoint];
  const notes=['Prior balance','Refusal receipt','Balance witness'];
  nums.forEach((n,i)=>{
    const seen=i===0 || (i===1?t>=reject:t>=15.7),color=seen?(i===1?P.red:P.green):P.line;
    circle(c,positions[i],738,seen?6:3,color);
    if(seen){text(c,'#'+n,positions[i]+13,731,21,color,600,true);text(c,notes[i],positions[i]+13,766,18,P.muted);}
  });
  light(c,strip,ramp(t,reject-.15,1.4)*.432,P.red);
  box(c,1098,623,448,159,12,'#0b1518',P.line);
  text(c,'TERMINAL  /  REPLAY STDOUT',1119,654,17,P.muted,600,true);
  facts.terminal.forEach((line,i)=>{if(t>=[10.4,10.9,15.7][i])text(c,line,1119,692+i*31,22,i===0?P.red:P.green,550,true);});
}
function detail(c:C,t:number) {
  const alpha=ramp(t,11.35,.45)*(1-ramp(t,15.35,.65));if(alpha<=0)return;
  c.save();c.globalAlpha=alpha;
  box(c,46,235,1510,385,18,'#17252a',P.pink);
  text(c,'CLAUSE DETAIL   /   LOCAL JLVM',80,289,19,P.pink,600,true);
  text(c,'Is this supplier on the list?',80,378,62,P.white,700);
  text(c,'REQUEST',88,452,18,P.muted,600,true);
  text(c,'“'+facts.request.payee+'”',82,520,53,P.pink,650);
  const u=ramp(t,11.7,1.2);
  path(c,[[330,498],[489,498]],P.pink,3);light(c,[[330,498],[487,498]],u,P.pink);
  box(c,490,460,90,77,10,'#29363b',P.pink);text(c,'∈',513,513,49,P.white,500);
  path(c,[[580,498],[661,498]],P.line,3);
  text(c,'OWNER’S ALLOW-LIST',679,452,18,P.muted,600,true);
  text(c,facts.suppliers.join('  /  '),675,510,37,P.white,620);
  box(c,1222,454,253,87,12,'#512d2f',P.red);mark(c,1254,498,false,P.red);
  text(c,'FALSE',1280,513,39,P.red,650,true);
  text(c,'definition.json',87,605,21,P.muted,500,true);
  text(c,'in ( event.payee, state.suppliers )',333,605,23,P.white,500,true);
  c.restore();
}
function intro(c:C,t:number) {
  const a=1-ramp(t,3.65,.65);if(!a)return;c.save();c.globalAlpha=a;
  box(c,0,80,1600,725,0,P.bg);
  text(c,'SIMULATED COMPROMISED AGENT',59,224,22,P.gold,600,true);
  text(c,'Same amount.',52,344,101,P.white,720);
  text(c,'Wrong supplier.',52,461,101,P.pink,720);
  text(c,'The previous $20 went to '+facts.previousPayee+'.',60,546,34,P.white);
  text(c,'Would you authorize it?',60,598,32,P.muted);
  c.save();c.translate(1020+36*(1-ramp(t,0,1.3)),211+10*Math.sin(t*1.5));
  c.rotate(-.037*(1-ramp(t,2.6,1)));receiptCard(c,0,0,1.34);c.restore();
  text(c,'Recorded four-wallet test submission',60,737,22,P.muted,500,true);
  c.restore();
}
class Circuit extends Node {
  constructor(private readonly time:()=>number){super({});}
  protected draw(c:C) {
    const t=this.time();c.save();c.scale(1.2,1.2);c.translate(-800,-450);
    c.fillStyle=P.bg;c.fillRect(0,0,1600,900);
    c.fillStyle='#273639';for(let y=99;y<797;y+=23)for(let x=12;x<1590;x+=23)c.fillRect(x,y,1,1);
    board(c,t);detail(c,t);intro(c,t);hud(c,t);c.restore();
  }
}
export default makeScene2D(function*(view){
  const time=createSignal(0);view.add(new Circuit(time));
  yield* tween(20, value=>time(value*20));
});
