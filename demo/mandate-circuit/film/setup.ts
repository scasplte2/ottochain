import {P,box,text,path,circle,ramp} from '../scene';

type C = CanvasRenderingContext2D;
function polygon(c:C, points:number[][],fill:string,edge:string) {
  c.beginPath();points.forEach(([x,y],i)=>i?c.lineTo(x,y):c.moveTo(x,y));c.closePath();
  c.fillStyle=fill;c.fill();c.lineWidth=2;c.strokeStyle=edge;c.stroke();
}
function wheel(c:C,x:number,y:number,r:number,turn:number) {
  c.save();c.translate(x,y);c.rotate(turn);
  for(const [radius,width,col] of [[r,12,'#080f12'],[r-7,3,'#aebcb2'],[r-12,1,'#758981']] as const){
    c.strokeStyle=col;c.lineWidth=width;c.beginPath();c.arc(0,0,radius,0,Math.PI*2);c.stroke();
  }
  for(let i=0;i<20;i++){
    const a=i*Math.PI/10;path(c,[[0,0],[(r-11)*Math.cos(a),(r-11)*Math.sin(a)]],'#5d716e',1.4);
  }
  circle(c,0,0,9,'#ccd1b9');c.restore();
}
function ticket(c:C,x:number,y:number,angle:number) {
  c.save();c.translate(x,y);c.rotate(angle);
  box(c,5,8,245,133,4,'#091114');box(c,0,0,245,133,4,P.paper);
  text(c,'PARTS REQUEST',16,29,17,'#445247',600,true);
  text(c,'Brake pads',14,75,33,'#22372e',680);
  path(c,[[16,90],[227,90]],'#9caa92',1);
  text(c,'QTY    1 BOX',17,116,16,'#445247',550,true);c.restore();
}
function workshop(c:C,u:number) {
  box(c,735,134,811,572,18,'#202f31',P.line);
  // Tool board and shop bench sit behind the bike on the repair stand.
  for(let y=168;y<360;y+=22)for(let x=765;x<1522;x+=22)circle(c,x,y,1.7,'#51625b');
  path(c,[[782,227],[1008,227]],'#65776e',5);
  for(const x of [808,850,893]){
    path(c,[[x,229],[x,266]],'#8fa197',5);
    path(c,[[x-6,270],[x,276],[x+6,270]],'#8fa197',4);
  }
  box(c,754,603,775,11,4,'#8c7960');
  path(c,[[815,615],[815,682]],'#66766e',9);path(c,[[1463,615],[1463,682]],'#66766e',9);
  path(c,[[1138,552],[1138,655]],'#b2bdad',8);
  path(c,[[1105,666],[1138,651],[1174,666]],'#b2bdad',6);
  wheel(c,1005,478,100,u*.65);wheel(c,1332,478,100,u*.65);
  const frame='#b9cf99';
  path(c,[[1005,478],[1088,367],[1130,478],[1005,478],[1252,358],[1130,478]],frame,9);
  path(c,[[1088,367],[1252,358],[1332,478]],frame,10);
  path(c,[[1088,367],[1074,333]],'#c0c8b7',5);path(c,[[1045,330],[1086,330]],'#0a1314',11);
  path(c,[[1252,358],[1240,320],[1271,312],[1295,325]],'#bcc8b7',5);
  circle(c,1130,478,16,'#152326');path(c,[[1110,497],[1130,478],[1148,459]],'#b5bdb1',5);
  path(c,[[1098,498],[1117,498]],'#c2ccbd',4);
  const show=ramp(u,.39,.30);
  c.save();c.globalAlpha=show;ticket(c,1211+28*(1-show),500+38*(1-show),-.055);c.restore();
}
function shipping(c:C,u:number) {
  box(c,735,134,811,572,18,'#222f31',P.line);
  // Slats, packing table, and an open carton seen from above.
  for(let i=0;i<10;i++)path(c,[[762,168+i*22],[1518,168+i*22]],'#344447',2);
  path(c,[[757,594],[1523,594]],'#8c7960',10);
  path(c,[[802,600],[802,678]],'#657971',8);path(c,[[1470,600],[1470,678]],'#657971',8);
  polygon(c,[[926,419],[1133,365],[1365,422],[1162,487]],'#614d38','#af9370');
  polygon(c,[[926,419],[1162,487],[1162,611],[926,538]],'#ab8960','#cfad7a');
  polygon(c,[[1162,487],[1365,422],[1365,539],[1162,611]],'#82684d','#bda079');
  polygon(c,[[926,419],[876,378],[1082,327],[1133,365]],'#c0a478','#d5bb92');
  polygon(c,[[1133,365],[1196,331],[1427,382],[1365,422]],'#a98d66','#d5bb92');
  const progress=ramp(u,0,.47);
  ticket(c,1098+105*(1-progress),344-100*(1-progress),-.065);
  polygon(c,[[926,419],[1162,487],[1121,517],[891,448]],'#c7a778','#d5bb92');
  polygon(c,[[1162,487],[1365,422],[1399,455],[1198,522]],'#b29672','#ccaf86');
}
export function setup(c:C,u:number,atShop:boolean) {
  text(c,atShop?'AT THE REPAIR STAND':'AT THE SHIPPING DESK',60,221,22,P.gold,620,true);
  text(c,atShop?'One bike.':'The order',55,339,88,P.white,730);
  text(c,atShop?'One missing part.':'is signed.',55,444,atShop?72:88,atShop?P.green:P.white,720);
  text(c,atShop?'The customer is due back this afternoon.':'Who authorized the purchase?',60,541,30,atShop?P.muted:P.pink,560);
  text(c,atShop?'The owner delegates a routine purchase.':'The supplier has to decide before shipping.',60,598,26,P.muted);
  if(atShop) workshop(c,u);else shipping(c,u);
}
