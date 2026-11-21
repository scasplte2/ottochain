// Apply the vertical cut's edit decision list (same segments as scenes/film.tsx `EDL`) to the film's
// audio mix, so the 60-second cut's narration and sound stay frame-accurate with its picture.
// Usage: node cut.mjs <film-mix.wav> <out.wav>   → prints the cut duration
import {execFileSync} from 'node:child_process';
import {readFileSync} from 'node:fs';

const [src, out] = process.argv.slice(2);
const timing = JSON.parse(readFileSync(new URL('./timing.json', import.meta.url), 'utf8'));
const act = id => timing.acts.find(a => a.id === id);
const S = id => act(id).start, E = id => act(id).end, L = (id, i) => act(id).lines[i].start, LE = (id, i) => act(id).lines[i].end;
const EDL = [
  [S('scene') - 0.2, LE('scene', 3) + 0.5],
  [L('mandate', 1) - 0.4, LE('mandate', 1) + 0.5],
  [L('poisoned', 0) - 0.3, LE('poisoned', 5) + 1.4],
  [L('verify', 1) - 0.3, LE('verify', 2) + 0.7],
  [L('close', 1) - 0.4, E('close')],
].map(([a, b]) => [Math.max(0, a), b]);
const parts = EDL.map(([a, b], i) => `[0:a]atrim=${a.toFixed(3)}:${b.toFixed(3)},asetpts=PTS-STARTPTS,afade=t=in:d=0.04,afade=t=out:st=${(b - a - 0.06).toFixed(3)}:d=0.06[s${i}]`);
const filter = parts.join(';') + ';' + EDL.map((_, i) => `[s${i}]`).join('') + `concat=n=${EDL.length}:v=0:a=1[a]`;
execFileSync('ffmpeg', ['-y', '-v', 'error', '-i', src, '-filter_complex', filter, '-map', '[a]', out]);
console.log(EDL.reduce((d, [a, b]) => d + (b - a), 0).toFixed(3));
