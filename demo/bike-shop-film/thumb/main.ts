// YouTube thumbnail (1280×720): the film's REFUSED moment, re-framed, with the hook line in large type.
import '@fontsource/inter/800.css';
import '@fontsource/inter/900.css';
import '@fontsource/jetbrains-mono/800.css';
import timing from '../timing.json';
import ottoUrl from '../assets/otto.png';
import {drawWorld} from '../scenes/film';
import {K, background, text} from '../scenes/kit';

const W = 1920, H = 1080;
const poisoned = timing.acts.find(a => a.id === 'poisoned')!;
const T = poisoned.lines[4].start + 0.55; // just after "Four rules fail" — the REFUSED burst

(window as any).renderThumb = async () => {
  for (const w of [800, 900]) await document.fonts.load(`${w} 80px Inter`);
  await document.fonts.load('800 30px "JetBrains Mono"');
  const otto = new Image(); otto.src = ottoUrl; await otto.decode();
  const big = document.createElement('canvas'); big.width = W; big.height = H;
  const c = big.getContext('2d')!;
  background(c, W, H, T);
  // world: the gate column + ALL node, pushed right and enlarged
  c.save(); c.translate(W * 0.68, H * 0.52); c.scale(1.45, 1.45); c.translate(-2400, -1060); drawWorld(c, T); c.restore();
  // left-side darkening for the type
  const g = c.createLinearGradient(0, 0, W * 0.72, 0);
  g.addColorStop(0, 'rgba(3,7,15,0.98)'); g.addColorStop(0.6, 'rgba(3,7,15,0.93)'); g.addColorStop(1, 'rgba(3,7,15,0)');
  c.fillStyle = g; c.fillRect(0, 0, W, H);
  text(c, 'OTTOCHAIN', 90, 150, 54, {weight: 900, spacing: 10});
  text(c, 'The agent', 84, 390, 150, {weight: 900});
  text(c, 'was fooled.', 84, 545, 150, {weight: 900});
  text(c, 'The rules', 84, 760, 150, {weight: 900, color: K.yellow, glow: K.yellow});
  text(c, 'weren’t.', 84, 915, 150, {weight: 900, color: K.yellow, glow: K.yellow});
  // Otto, top right
  const ox = W - 190, oy = 190, r = 130;
  c.save(); c.shadowColor = K.yellow; c.shadowBlur = 40; c.strokeStyle = K.yellow; c.lineWidth = 10;
  c.beginPath(); c.arc(ox, oy, r + 6, 0, Math.PI * 2); c.stroke(); c.restore();
  c.save(); c.beginPath(); c.arc(ox, oy, r, 0, Math.PI * 2); c.clip(); c.drawImage(otto, ox - r, oy - r, 2 * r, 2 * r); c.restore();
  // downscale to 1280×720
  const out = document.createElement('canvas'); out.width = 1280; out.height = 720;
  const o = out.getContext('2d')!; o.imageSmoothingQuality = 'high'; o.drawImage(big, 0, 0, 1280, 720);
  return out.toDataURL('image/png');
};
