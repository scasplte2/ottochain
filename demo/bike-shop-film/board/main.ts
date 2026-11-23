// Interactive board: the film's drawing code on a plain canvas, with transport controls, act jumps and an
// evidence panel. Built as static files (vite build --config vite.board.config.ts) for ottochain.ai.
import '@fontsource/inter/400.css';
import '@fontsource/inter/600.css';
import '@fontsource/inter/700.css';
import '@fontsource/inter/800.css';
import '@fontsource/inter/900.css';
import '@fontsource/jetbrains-mono/400.css';
import '@fontsource/jetbrains-mono/700.css';
import '@fontsource/jetbrains-mono/800.css';
import timing from '../timing.json';
import ottoUrl from '../assets/otto.png';
import audioUrl from '../output/film-mix.m4a';
import {drawFrame} from '../scenes/film';

const EVIDENCE: Record<string, string> = {
  'cold-open': 'Title — the refusal you are about to see.',
  scene: 'Set-up: Maya delegates a purchase to an AI agent.',
  stakes: 'The two questions the rest of the film answers.',
  registry: 'publishVersion purchasing-mandate.machine@1.0.0 (#7) · mandate bound to 1.0.0 (#35) · alias spoke-and-sprocket.machine (#30)',
  assets: 'mint $100 shop credit (#46, behavior 28 = T|S|C) → FRACTIONALIZE ×5 (#58) → 2 pieces into the mandate (#70, #74)',
  mandate: 'authorize #1 $20 → credit to the desk + receive_order in one atomic cascade (#133)',
  poisoned: 'authorize $500 to QuickParts refused (#176) · JLVM replay: ✗ per-order limit ✗ budget ✗ supplier ✗ credit match',
  delivery: 'dispatch checks custody (#179) → _spawn delivery tracker → deliver _triggers delivery_received (#202)',
  zk: 'trade account prove: SP1 Groth16 over "score ≥ 700", Maya signature required (#211)',
  kit: 'COMPOSE pads(20) + voucher(22) → kit(22) (#277) · kit → Rosa (#334) · soulbound resale rejected at DL1',
  control: 'publish 1.1.0 (#373) while the mandate stays on 1.0.0 (#400) · revoke (#378) · next request refused (#394)',
  verify: 'verify-mandate: inclusion · ordinal catalog · combined commitment · pinned snapshot (#400) · $80 → $80.01 rejected',
  close: 'Readable contracts · typed assets · contracts that talk · privacy when you need it · all open source.',
};
const PROOF: Record<string, string> = {
  registry: 'e2e-test/examples/bike-shop/mandate.definition.json', assets: 'e2e-test/examples/bike-shop/morphism.ts',
  mandate: 'modules/shared-data/src/test/scala/xyz/kd5ujc/shared_data/BikeShopSuite.scala', poisoned: 'demo/bike-shop-film/derive.mjs',
  delivery: 'e2e-test/examples/bike-shop/order-desk.definition.json', zk: 'e2e-test/examples/bike-shop/trade-account.definition.json',
  kit: 'e2e-test/examples/bike-shop/example.ts', control: 'e2e-test/examples/bike-shop/evidence/mandate.bundle.json',
  verify: 'e2e-test/examples/bike-shop/tamper.ts',
};

const canvas = document.getElementById('board') as HTMLCanvasElement;
const ctx = canvas.getContext('2d')!;
const play = document.getElementById('play') as HTMLButtonElement;
const scrub = document.getElementById('scrub') as HTMLInputElement;
const clock = document.getElementById('clock') as HTMLSpanElement;
const capBtn = document.getElementById('captions') as HTMLButtonElement;
const sndBtn = document.getElementById('sound') as HTMLButtonElement;
const acts = document.getElementById('acts')!;
const evTitle = document.getElementById('ev-title')!, evLine = document.getElementById('ev-line')!, evProof = document.getElementById('ev-proof')!;

const D = timing.duration;
const otto = new Image(); otto.src = ottoUrl;
const audio = new Audio(audioUrl); audio.preload = 'auto';
let t = 0, playing = false, last = 0, captions = true;

const fmt = (s: number) => `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}`;
for (const a of timing.acts) {
  const b = document.createElement('button');
  b.textContent = a.label || (a.id === 'close' ? 'Close' : 'Opening');
  b.addEventListener('click', () => seek(a.start + 0.01));
  acts.appendChild(b);
}
function seek(s: number) {
  t = Math.min(D, Math.max(0, s));
  try { audio.currentTime = t; } catch { /* not loaded yet */ }
}
function setPlaying(p: boolean) {
  playing = p;
  play.textContent = p ? '❚❚ Pause' : '▶ Play';
  if (p && sndBtn.classList.contains('on')) { audio.currentTime = t; audio.play().catch(() => undefined); } else audio.pause();
}
play.addEventListener('click', () => setPlaying(!playing));
scrub.addEventListener('input', () => seek((Number(scrub.value) / 1000) * D));
capBtn.addEventListener('click', () => { captions = !captions; capBtn.classList.toggle('on', captions); capBtn.setAttribute('aria-pressed', String(captions)); });
sndBtn.addEventListener('click', () => {
  const on = !sndBtn.classList.contains('on');
  sndBtn.classList.toggle('on', on); sndBtn.setAttribute('aria-pressed', String(on));
  if (!on) audio.pause(); else if (playing) { audio.currentTime = t; audio.play().catch(() => undefined); }
});
document.addEventListener('keydown', e => {
  if (e.target instanceof HTMLInputElement) return;
  if (e.code === 'Space') { e.preventDefault(); setPlaying(!playing); }
  if (e.code === 'ArrowRight') seek(t + 5);
  if (e.code === 'ArrowLeft') seek(t - 5);
});

let lastAct = '';
function frame(now: number) {
  const dt = last ? (now - last) / 1000 : 0; last = now;
  if (playing) {
    // follow the audio clock when sound is on, so picture and narration never drift
    t = sndBtn.classList.contains('on') && !audio.paused ? audio.currentTime : t + dt;
    if (t >= D) { t = D; setPlaying(false); }
  }
  drawFrame(ctx, t, {otto, captions});
  scrub.value = String(Math.round((t / D) * 1000));
  clock.textContent = `${fmt(t)} / ${fmt(D)}`;
  const cur = [...timing.acts].reverse().find(a => t >= a.start) ?? timing.acts[0];
  if (cur.id !== lastAct) {
    lastAct = cur.id;
    evTitle.textContent = cur.label || (cur.id === 'close' ? 'This is OttoChain' : 'OttoChain — a bike-shop story');
    evLine.textContent = EVIDENCE[cur.id] ?? '';
    evProof.textContent = PROOF[cur.id] ?? '';
    [...acts.children].forEach((b, i) => b.classList.toggle('on', timing.acts[i].id === cur.id));
  }
  requestAnimationFrame(frame);
}
document.fonts.ready.then(() => requestAnimationFrame(frame));
