import {Node, makeScene2D} from '@motion-canvas/2d';
import {createSignal, tween} from '@motion-canvas/core';
import {P,box,text,path,circle,light,mark,receiptCard,ramp,board,detail} from '../scene';
import data from './evidence.json';
import timing from './timing.json';
import preview from '../scene-data.json';

type C=CanvasRenderingContext2D;
const r=data.records;
type Payment=typeof r.solo;
const label=(c:C,s:string,x:number,y:number,col=P.muted)=>text(c,s,x,y,19,col,550,true);
function heading(c:C,title:string,sub:string){
  text(c,title,57,165,67,P.white,700);
  text(c,sub,60,214,26,P.muted);
}
function plate(c:C,x:number,y:number,w:number,h:number,kicker:string,title:string,accent:string){
  box(c,x,y,w,h,15,P.panel,P.line);path(c,[[x+3,y+20],[x+3,y+h-20]],accent,4);
  label(c,kicker,x+23,y+37,accent);text(c,title,x+23,y+99,36,P.white,640);
}
function wire(c:C,points:number[][],u:number,col:string,double=false){
  path(c,points,col,2);
  if(double) path(c,points.map(([x,y])=>[x,y+6]),col,1);
  light(c,points,u,col);
}
function budget(c:C,value:number,caption:string){
  box(c,1121,310,424,309,17,P.panel,P.line);label(c,'REMAINING TEST BUDGET',1151,357);
  text(c,'$'+value,1148,477,107,P.white,720);
  for(let i=0;i<20;i++)box(c,1151+i*17,507,12,28,2,i<value/5?P.green:'#334448');
  text(c,caption,1151,585,23,P.muted);
}
function snapshots(c:C,items:[number,string,string][],u:number){
  label(c,'RECORDED SNAPSHOTS',58,680);
  path(c,[[58,724],[1540,724]],P.line,2);
  items.forEach(([n,s,col],i)=>{
    const x=81+i*325;
    c.save();c.globalAlpha=.35+.65*ramp(u,i*.15,.22);circle(c,x,724,7,col);
    text(c,'#'+n,x+15,716,24,col,650,true);text(c,s,x+15,754,20,P.muted);c.restore();
  });
}
function intro(c:C,u:number){
  label(c,'SIMULATED COMPROMISED AGENT',60,221,P.gold);
  text(c,'A valid signature.',54,341,91,P.white,740);
  text(c,'The wrong supplier.',54,456,91,P.pink,740);
  text(c,'Who decides whether it goes through?',61,553,37,P.muted);
  c.save();c.translate(1060-45*ramp(u,.02,.5),205);c.rotate(-.04*(1-ramp(u,.35,.55)));receiptCard(c,0,0,1.32);c.restore();
  label(c,'SIGNED TEST REQUEST  /  RECORDED FOUR-WALLET RUN',62,715);
}
function issuance(c:C,u:number){
  heading(c,'The owner sets the terms','A signed JSON mandate becomes an active ledger record.');
  box(c,60,277,555,344,16,'#101e22',P.line);label(c,'MANDATE  /  INITIAL STATE',87,319,P.gold);
  const entries=[['Test budget','$'+r.created.after],['Solo limit','$'+data.soloLimit],['Suppliers','supplier-a, supplier-b'],['Owner power','Revoke']];
  entries.forEach(([key,v],i)=>{const y=380+i*62;text(c,key,89,y,23,P.muted);text(c,v,278,y,25,P.white,620);});
  wire(c,[[615,454],[695,454],[695,386],[774,386]],ramp(u,.1,.43),P.gold,true);
  plate(c,774,320,292,132,'OWNER KEY','Signed mandate',P.gold);
  wire(c,[[920,452],[920,527],[1121,527]],ramp(u,.38,.34),P.green);
  budget(c,r.created.after,'Initial authorization');snapshots(c,[[r.created.ordinal,'Mandate created',P.green]],u);
}
function actualModel(c:C,u:number){
  heading(c,'What the model did','Two separate local runs with synthetic invoices.');
  plate(c,58,340,350,145,'LOCAL MODEL',data.model.name,P.gold);
  for(const [i,y] of [305,490].entries()){
    const col=i?P.green:P.pink;
    wire(c,[[408,408],[486,408],[486,y+57],[550,y+57]],ramp(u,i*.4,.35),col);
    plate(c,551,y,443,131,i?'APPROVED SUPPLIER':'UNLISTED SUPPLIER',i?'Invoice for supplier-a':'Invoice for supplier-c',col);
    wire(c,[[995,y+62],[1130,y+62]],ramp(u,.15+i*.4,.3),col);
    text(c,i?'Submitted $20':'Declined',1144,y+79,44,col,680);
  }
  label(c,'TOOLS',60,690);text(c,'read_mandate    /    submit_payment',60,739,30,P.white,500,true);
  label(c,'APPROVED CONTROL  /  COMMITTED AT #'+data.model.ordinal,990,714,P.green);
}
function transfer(c:C,u:number,payment:Payment,role:string,clause:string,pass:boolean,sub:string,arrivals:[number,string,string][]){
  const hit=ramp(u,.42,.15), col=pass?P.green:P.red;
  heading(c,sub,pass?'An authorization on the recorded four-wallet mandate.':'A refusal on the recorded four-wallet mandate.');
  plate(c,60,348,357,154,'SIGNING IDENTITY',role,P.gold);
  text(c,'$'+payment.payload.amountCents/100+'  /  '+payment.payload.payee,84,475,22,P.muted,550,true);
  wire(c,[[417,423],[585,423]],ramp(u,.07,.39),P.gold,true);
  box(c,586,340,383,173,18,P.panel,hit?col:P.line);
  label(c,'MANDATE CHECK',610,385);
  text(c,clause,610,443,34,P.white,625);
  if(hit){mark(c,620,480,pass,col);text(c,pass?'PASS':'REFUSED',642,487,21,col,650,true);}
  wire(c,[[969,423],[1121,423]],pass?ramp(u,.49,.24):0,pass?P.green:P.line);
  budget(c,u>.64?payment.after:payment.before,u>.64?(pass?'Authorization committed':'No debit recorded'):'Before the decision');
  snapshots(c,arrivals,u);
}
function cosign(c:C,u:number){
  const single=r['cosigner-required'], both=r.authorized, second=u>.48, done=u>.72;
  heading(c,'Two keys for forty dollars','Both signatures must cover the identical request.');
  plate(c,63,275,359,129,'SIGNATURE 01','Agent key',P.gold);
  plate(c,63,481,359,129,'SIGNATURE 02','Co-signer key',second?P.gold:P.line);
  const merge=[[422,340],[500,340],[570,425],[647,425]];
  wire(c,merge,ramp(u,.04,.28),P.gold,true);
  wire(c,[[422,546],[495,546],[570,425],[647,425]],ramp(u,.48,.22),second?P.gold:P.line,true);
  box(c,648,330,335,182,14,P.panel,done?P.green:P.gold);label(c,'REQUEST '+both.payload.requestNumber,675,375,P.gold);
  text(c,'$40 to supplier-a',674,433,34,P.white,650);text(c,done?'2 authorized signatures':'Co-signer required',675,476,22,done?P.green:P.gold);
  wire(c,[[984,427],[1120,427]],ramp(u,.67,.15),done?P.green:P.line);
  budget(c,done?both.after:single.after,done?'Authorized once':'After the solo refusal');
  snapshots(c,second?[[single.ordinal,'Agent alone refused',P.red],[both.ordinal,'Co-signed action',P.green],[both.checkpoint,'Balance witness',P.green]]:[[single.ordinal,'Agent alone refused',P.red]],u);
}
function revoke(c:C,u:number){
  const off=u>.28;
  heading(c,'The owner revokes access','Revocation changes which transitions the mandate permits.');
  plate(c,59,342,330,133,'OWNER KEY','Revoke',P.gold);
  wire(c,[[389,413],[554,413]],ramp(u,.02,.29),P.gold,true);
  box(c,554,345,405,137,15,P.panel,off?P.red:P.green);
  label(c,'MANDATE STATE',583,385);text(c,off?'Revoked':'Active',583,450,48,off?P.red:P.green,690);
  path(c,[[960,413],[1015,413]],P.line,3);path(c,[[1042,off?380:413],[1102,413]],P.line,3);
  if(off)mark(c,1028,413,false,P.red);
  budget(c,r.revoked.after,'Fresh $10 request refused');
  snapshots(c,[[r.revoked.ordinal,'Owner revoked',P.gold],[r['revoked-refusal'].ordinal,'Retry refused',P.red]],u);
}
function proof(c:C,u:number,bad:boolean){
  heading(c,bad?'Change one cent':'The supplier checks the evidence',bad?'An edit to the saved record breaks the cryptographic proof.':'Replay from the pinned capture, offline.');
  const stops=[124,440,756,1072], steps=bad?['Edited record','Trie leaf','State root','Snapshot']:['Signed request','Record & trie','State root','Snapshot'];
  stops.forEach((x,i)=>{
    const active=u>i*.12, col=bad?(i<2?P.red:P.line):P.green;
    circle(c,x,389,31,active?col:P.line);
    if(active && (!bad || i===1))mark(c,x,389,!bad,P.bg);
    text(c,steps[i],x-45,461,27,P.white,610);
    if(i<3)wire(c,[[x+33,389],[stops[i+1]-34,389]],bad&&i>0?0:ramp(u,i*.12,.2),col);
  });
  box(c,62,525,1482,198,15,'#0a1418',P.line);
  label(c,'SUPPLIER TERMINAL   /   RECORDED REPLAY OUTPUT',87,564);
  const lines=bad?[data.tamper[0],data.tamper[1]]:[data.decision[1],data.decision[2],data.decision[3]];
  lines.forEach((s,i)=>{c.save();c.globalAlpha=.13+.87*ramp(u,.06+i*.18,.2);text(c,s,88,609+i*39,27,bad&&i?P.red:P.green,500,true);c.restore();});
  if(bad){text(c,'$40.00  →  $40.01',1185,395,33,P.red,660);}
}
function end(c:C,u:number){
  label(c,'SUPPLIER VERIFICATION',59,226,P.green);text(c,'Replay the decision.',52,348,90,P.white,720);
  text(c,'1 snapshot source   /   1 local operator',60,432,35,P.muted);
  text(c,'Validator signature threshold unchecked',61,484,29,P.muted);
  box(c,60,548,1478,128,13,P.panel,P.line);label(c,'FROM e2e-test  /  AFTER npm ci',82,587,P.gold);
  text(c,'node examples/agent-mandate/decision.ts',82,644,36,P.white,600,true);
  text(c,'github.com/scasplte2/ottochain',60,749,24,P.green,540,true);
}
function captions(c:C,s:string){
  const words=s.split(' ');let line='';const lines=[];
  c.font='530 29px "Ubuntu Sans", Ubuntu, sans-serif';
  for(const word of words){const next=line?line+' '+word:word;if(c.measureText(next).width>1470){lines.push(line);line=word;}else line=next;}
  lines.push(line);
  lines.forEach((l,i)=>text(c,l,57,lines.length>1?828+38*i:852,29,P.white,530));
}
class Film extends Node {
  constructor(private clock:()=>number){super({});}
  protected draw(c:C){
    const frame=Math.min(timing.frames-1,Math.floor(this.clock()+.001));
    const s=timing.sections.find(section=>frame<section.end)!;
    const u=(frame-s.start)/(s.end-s.start);
    c.save();c.scale(1.2,1.2);c.translate(-800,-450);c.fillStyle=P.bg;c.fillRect(0,0,1600,900);
    c.fillStyle='#273639';for(let y=99;y<779;y+=23)for(let x=12;x<1590;x+=23)c.fillRect(x,y,1,1);
    switch(s.id){
    case 'open': intro(c,u);break;
    case 'issue': issuance(c,u);break;
    case 'model': actualModel(c,u);break;
    case 'solo': transfer(c,u,r.solo,'Agent key','All eight gates',r.solo.clauses.every(Boolean),'Twenty dollars for supplier A',[[r.created.ordinal,'$100 at creation',P.gold],[r.solo.ordinal,'$80 remaining',P.green]]);break;
    case 'refusal':{const t=3.8+u*16.2;board(c,t);detail(c,t,JSON.stringify(data.payeeGuard));break;}
    case 'stranger': transfer(c,u,r['stranger-refused'],'Stranger key',preview.gates[r['stranger-refused'].clauses.indexOf(false)].label,false,'Right supplier. Wrong key.',[[r['stranger-refused'].ordinal,'Stranger refused',P.red]]);break;
    case 'cosign': cosign(c,u);break;
    case 'revoke': revoke(c,u);break;
    case 'proof': proof(c,u,false);break;
    case 'tamper': proof(c,u,true);break;
    case 'close': end(c,u);break;
    }
    text(c,'OTTOCHAIN',55,54,23,P.white,750);path(c,[[212,30],[212,56]],P.line);
    label(c,'AGENT MANDATE',233,53);text(c,'Animation of recorded ledger events',1080,51,20,P.muted);
    path(c,[[54,77],[1546,77]],P.line,1);path(c,[[54,785],[1546,785]],P.line,1);
    const cue=s.captions.filter(k=>k.frame<=frame).at(-1)!;captions(c,cue.text);
    path(c,[[54,889],[1546,889]],P.line,2);path(c,[[54,889],[54+1492*frame/timing.frames,889]],P.gold,2);
    c.restore();
  }
}
export default makeScene2D(function*(view){
  const time=createSignal(0);view.add(new Film(time));
  yield* tween(timing.frames/timing.fps,value=>time(value*timing.frames));
});
