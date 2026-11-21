// Sound bed for the full film, synthesised with FFmpeg expressions (no samples, no third-party audio),
// timed from timing.json + film-data.json with the same cue formulas as scenes/film.tsx.
// Usage: node sfx-film.mjs <out.wav>
import {execFileSync} from 'node:child_process';
import {readFileSync, writeFileSync} from 'node:fs';

const out = process.argv[2];
const timing = JSON.parse(readFileSync(new URL('./timing.json', import.meta.url), 'utf8'));
const data = JSON.parse(readFileSync(new URL('./film-data.json', import.meta.url), 'utf8'));
const act = id => timing.acts.find(a => a.id === id);
const S = id => act(id).start, L = (id, i) => act(id).lines[i].start;
const LF = (id, i, f) => L(id, i) + (act(id).lines[i].end - L(id, i)) * f;
const D = timing.duration;
const gates = data.poisoned.gates;

const at = (t0, len, body) => `if(between(t,${t0.toFixed(3)},${(t0 + len).toFixed(3)}),${body.replaceAll('T', `(t-${t0.toFixed(3)})`)},0)`;
const tick = (t0, hz, amp = 0.09) => at(t0, 0.4, `${amp}*sin(2*PI*${hz}*T)*exp(-22*T)+${amp / 2}*sin(2*PI*${hz * 2}*T)*exp(-30*T)`);
const impact = t0 => at(t0, 1.5, '0.5*sin(2*PI*52*T)*exp(-3.4*T)+0.2*sin(2*PI*233*T)*exp(-5*T)+0.12*sin(2*PI*311*T)*exp(-6*T)');
const boom = t0 => at(t0, 2.2, '0.6*sin(2*PI*(41-6*T)*T)*exp(-2.2*T)+0.12*sin(2*PI*164*T)*exp(-4*T)');
const whoosh = (t0, len = 1.2) => at(t0, len, `0.07*sin(2*PI*(160*T+300*T*T))*sin(PI*T/${len})`);
const chime = (t0, f1 = 660, f2 = 990) => at(t0, 2.0, `0.07*sin(2*PI*${f1}*T)*exp(-2.6*T)`) + '+' + at(t0 + 0.28, 2.2, `0.07*sin(2*PI*${f2}*T)*exp(-2.4*T)`);
const thump = t0 => at(t0, 0.6, '0.4*sin(2*PI*68*T)*exp(-9*T)+0.1*sin(2*PI*2400*T)*exp(-60*T)');
const bloom = t0 => at(t0, 1.6, '0.08*sin(2*PI*523*T)*exp(-2*T)+0.06*sin(2*PI*659*T)*exp(-2*T)+0.05*sin(2*PI*784*T)*exp(-2*T)');

const parts = [];
// cold open
parts.push(boom(0.25), chime(1.9, 523, 784));
// act transitions (Otto badge)
for (const id of ['registry', 'assets', 'mandate', 'poisoned', 'delivery', 'zk', 'kit', 'control', 'verify']) parts.push(chime(S(id), 880, 1320));
// mandate: order #1 passes every gate, then the atomic step
gates.forEach((g, i) => parts.push(tick(LF('mandate', 1, 0.15) + i * 0.22, 820 + i * 55)));
parts.push(whoosh(LF('mandate', 1, 0.05), 0.8), whoosh(LF('mandate', 2, 0.2), 1.4), bloom(LF('mandate', 2, 0.2) + 1.4));
// poisoned: stamp, launch, per-gate verdicts, refusal
parts.push(thump(LF('poisoned', 2, 0.4)), whoosh(LF('poisoned', 2, 0.7), 0.9));
const p0 = LF('poisoned', 3, 0.02), p1 = LF('poisoned', 3, 0.98);
gates.forEach((g, i) => { const t0 = p0 + (p1 - p0) * (i / (gates.length - 1)); parts.push(g.pass ? tick(t0, 820 + i * 55) : impact(t0)); });
parts.push(boom(L('poisoned', 4)));
// delivery: dispatch, spawn, signal back
parts.push(tick(LF('delivery', 1, 0.1), 1046, 0.1), whoosh(LF('delivery', 1, 0.6), 1.0), bloom(LF('delivery', 2, 0.25)), whoosh(LF('delivery', 3, 0.25), 1.3), bloom(LF('delivery', 3, 0.25) + 1.3));
// zk
parts.push(whoosh(LF('zk', 1, 0.45), 1.0), chime(LF('zk', 1, 0.45) + 1.0, 784, 1175));
// kit: compose, gift, refused resale
parts.push(bloom(LF('kit', 0, 0.55)), whoosh(LF('kit', 1, 0.55), 1.0), impact(LF('kit', 2, 0.45) + 0.55));
// control: new version, revoke, refused request
parts.push(tick(L('control', 0), 1175, 0.08), thump(LF('control', 1, 0.35)), impact(LF('control', 1, 0.7) + 0.5));
// verify: checks tick, then the one-cent edit fails
[0, 1, 2, 3].forEach(i => parts.push(tick(LF('verify', 1, 0.35 + i * 0.15), 988 + i * 70)));
parts.push(impact(LF('verify', 2, 0.45)));
// close
parts.push(chime(L('close', 2), 523, 784));
// ambient pad under everything
parts.unshift(`0.035*(sin(2*PI*55*t)+0.55*sin(2*PI*82.41*t)+0.35*sin(2*PI*110*t+0.3*sin(2*PI*0.2*t)))*(0.65+0.35*sin(2*PI*0.06*t))*min(1,t/3)*min(1,(${D}-t)/3)`);

// FFmpeg expression parsing is slow for one giant sum; render in chunks and mix.
const chunks = [];
for (let i = 0; i < parts.length; i += 12) chunks.push(parts.slice(i, i + 12).join('+'));
const tmp = chunks.map((_, i) => out.replace(/\.wav$/, `.part${i}.wav`));
chunks.forEach((expr, i) => execFileSync('ffmpeg', ['-y', '-v', 'error', '-f', 'lavfi', '-i', `aevalsrc='${expr}':s=48000:d=${D}`, tmp[i]]));
execFileSync('ffmpeg', ['-y', '-v', 'error', ...tmp.flatMap(p => ['-i', p]), '-filter_complex',
  `${tmp.map((_, i) => `[${i}:a]`).join('')}amix=inputs=${tmp.length}:normalize=0,aecho=0.8:0.6:60|140:0.22|0.12,alimiter=limit=0.85,pan=stereo|c0=c0|c1=c0[a]`, '-map', '[a]', out]);
for (const p of tmp) writeFileSync(p, ''); // truncate temp parts; removed by the caller
console.log(out);
