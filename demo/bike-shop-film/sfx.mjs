// Deterministic sound design for the style frame, synthesised with FFmpeg expressions (no samples, no
// third-party audio). Timings mirror scenes/styleframe.tsx's timeline `T`.
// Usage: node sfx.mjs <out.wav> [seconds=20]
import {execFileSync} from 'node:child_process';
import {readFileSync} from 'node:fs';

const [out, secondsArg = '20'] = process.argv.slice(2);
const seconds = Number(secondsArg);
const facts = JSON.parse(readFileSync(new URL('../mandate-circuit/scene-data.json', import.meta.url), 'utf8'));
const T = {stamp: 2.9, launch: 3.7, arrive: 5.2, gate0: 5.5, gateStep: 0.55, verdict: 10.4, ledger: 11.5, otto: 13.6};
const failIndex = facts.gates.findIndex(g => !g.pass);
const gateTime = i => T.gate0 + i * T.gateStep + (i > failIndex ? 0.35 : 0);

const at = (t0, len, body) => `if(between(t,${t0},${t0 + len}),${body.replaceAll('T', `(t-${t0})`)},0)`;
const parts = [
  // ambient pad, slow swell, fades at both ends
  `0.05*(sin(2*PI*55*t)+0.55*sin(2*PI*82.41*t)+0.35*sin(2*PI*110*t+0.3*sin(2*PI*0.2*t)))*(0.65+0.35*sin(2*PI*0.08*t))*min(1,t/2)*min(1,(${seconds}-t)/1.5)`,
  // launch: rising chirp
  at(T.launch, 1.5, '0.10*sin(2*PI*(180*T+380*T*T))*sin(PI*T/1.5)'),
  // signature stamp: thump + click
  at(T.stamp, 0.6, '0.45*sin(2*PI*68*T)*exp(-9*T)+0.12*sin(2*PI*2400*T)*exp(-60*T)'),
  // arrival at the spine
  at(T.arrive, 0.5, '0.10*sin(2*PI*520*T)*exp(-10*T)'),
  // per-gate ticks (rising), heavy impact on the failing clause
  ...facts.gates.map((g, i) => g.pass
    ? at(gateTime(i), 0.45, `0.11*sin(2*PI*${820 + i * 55}*T)*exp(-22*T)+0.05*sin(2*PI*${1640 + i * 110}*T)*exp(-30*T)`)
    : at(gateTime(i), 1.6, '0.55*sin(2*PI*52*T)*exp(-3.2*T)+0.22*sin(2*PI*233*T)*exp(-5*T)+0.14*sin(2*PI*311*T)*exp(-6*T)')),
  // verdict: sub boom
  at(T.verdict, 2.2, '0.6*sin(2*PI*(41-6*T)*T)*exp(-2.2*T)+0.12*sin(2*PI*164*T)*exp(-4*T)'),
  // ledger refusal receipt drop
  at(T.ledger, 0.6, '0.16*sin(2*PI*330*T)*exp(-8*T)+0.08*sin(2*PI*495*T)*exp(-9*T)'),
  // Otto: two-note bell
  at(T.otto + 0.3, 2.0, '0.12*sin(2*PI*660*T)*exp(-2.5*T)+0.05*sin(2*PI*1320*T)*exp(-4*T)'),
  at(T.otto + 0.62, 2.4, '0.12*sin(2*PI*990*T)*exp(-2.2*T)+0.04*sin(2*PI*1980*T)*exp(-4*T)'),
];
execFileSync('ffmpeg', ['-y', '-v', 'error', '-f', 'lavfi', '-i', `aevalsrc='${parts.join('+')}':s=48000:d=${seconds}`,
  '-af', 'aecho=0.8:0.6:60|140:0.25|0.15,alimiter=limit=0.85,pan=stereo|c0=c0|c1=c0', out], {stdio: 'inherit'});
console.log(out);
