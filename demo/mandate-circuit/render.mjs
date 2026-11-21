import assert from 'node:assert/strict';
import { spawn, execFileSync } from 'node:child_process';
import { once } from 'node:events';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, renameSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright-core';
import { createServer } from 'vite';

process.chdir(dirname(fileURLToPath(import.meta.url)));
const fullFilm=process.argv[3]==='--film';
const timing=fullFilm?JSON.parse(readFileSync('film/timing.json','utf8')):{frames:480,fps:24};
const duration=timing.frames/timing.fps;
const voiceDir=fullFilm?resolve(process.argv[4]):null;
if(fullFilm){
  assert.deepEqual(JSON.parse(readFileSync(join(voiceDir,'timing.json'),'utf8')),timing,'Voice timing differs from scene timing');
  execFileSync(process.execPath,['film/capture.mjs'],{stdio:'inherit'});
  process.env.OTTO_FILM_PROJECT='film/project.ts';
}
const destination=resolve(process.argv[2] ?? '../../.workspace/mandate-circuit/refusal-preview.mp4');
assert.ok(!existsSync(destination),'Choose a new output path.');
mkdirSync(dirname(destination),{recursive:true});
const scratch=mkdtempSync(join(dirname(destination),'.encode-'));
const partial=join(scratch,'preview.mp4');
let browser, server, encoder;
try {
  execFileSync(process.execPath,['replay.mjs','scene-data.json'],{stdio:'inherit'});
  const data=JSON.parse(readFileSync('scene-data.json','utf8'));
  const sounds=data.gates.map((gate,i)=>{
    const local=gate.pass?5.9+i*.43:10.3;
    const section=fullFilm?timing.sections.find(s=>s.id==='refusal'):null;
    const at=section?(section.start+(local-3.8)/16.2*(section.end-section.start))/timing.fps:local;
    const hz=gate.pass?590+i*49:67;
    const amp=gate.pass?0.085:0.32;
    const fall=gate.pass?35:8;
    return 'if(between(t,'+at+','+(at+.7)+'),'+amp+'*sin(2*PI*'+hz+'*(t-'+at+'))*exp(-'+fall+'*(t-'+at+')),0)';
  });
  const audioInputs=['-f','lavfi','-i',"aevalsrc='"+sounds.join('+')+"':s=48000:d="+duration];
  const mix=fullFilm?['-i',join(voiceDir,'narration.wav'),'-filter_complex',
    '[2:a]loudnorm=I=-16:TP=-1.8:LRA=9,aresample=48000[voice];[1:a]volume=0.60[ticks];[voice][ticks]amix=inputs=2:duration=first:normalize=0,alimiter=limit=0.74:level=0:latency=1,atrim=duration='+duration+'[a]','-map','0:v','-map','[a]']:[];
  server=await createServer({server:{host:'127.0.0.1',port:0},logLevel:'error'});
  await server.listen();
  browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH,headless:true});
  const page=await browser.newPage();
  page.on('pageerror',err=>console.error(err));
  page.on('console',msg=>{if(msg.type()==='error')console.error(msg.text());});
  encoder=spawn('ffmpeg',['-v','error','-f','image2pipe','-framerate','24','-i','pipe:0',
    ...audioInputs,...mix,'-c:v','libx264',
    '-crf','17','-preset','fast','-pix_fmt','yuv420p','-c:a','aac','-b:a','144k','-movflags','+faststart',partial],
    {stdio:['pipe','inherit','inherit']});
  const done=once(encoder,'close');
  let count=0;
  await page.exposeFunction('encodeFrame',async (index,image)=>{
    assert.equal(index,count++);
    await new Promise((res,rej)=>encoder.stdin.write(Buffer.from(image.split(',')[1],'base64'),err=>err?rej(err):res()));
  });
  await page.goto('http://127.0.0.1:'+server.httpServer.address().port+'/export.html'+(fullFilm?'?film':''));
  await page.waitForFunction(()=>typeof window.exportScene==="function",null,{timeout:45000});
  const result=await page.evaluate(async()=>await window.exportScene());
  assert.equal(result,0,'Motion Canvas export failed');
  assert.equal(count,timing.frames,'Incomplete render');
  encoder.stdin.end();
  assert.equal((await done)[0],0,'FFmpeg encode failed');
  renameSync(partial,destination);
  console.log(destination);
} finally {
  encoder?.kill(); await browser?.close(); await server?.close();
  rmSync(scratch,{recursive:true,force:true});
}
