// 60-second vertical cut (1080×1920). An edit decision list remaps vertical time onto the main film's
// timeline, so every frame is the same recorded-run animation, re-framed by a vertical camera, with
// vertical captions/titles. cut.mjs applies the SAME list to the audio, keeping the narration in sync.
import {Img, Node, makeScene2D} from '@motion-canvas/2d';
import {createRef, createSignal, tween} from '@motion-canvas/core';
import ottoSrc from '../assets/otto.png';
import {VERTICAL_DURATION, drawWorld, filmLines, segmentAt} from './film';
import {C, K, background, measure, ramp, roundRect, smooth, text, window01} from './kit';

const W = 1080, H = 1920;

function wrap(c: C, s: string, size: number, max: number, weight = 700) {
  const words = s.split(' '), lines: string[] = [];
  let cur = '';
  for (const w of words) {
    const next = cur ? cur + ' ' + w : w;
    if (measure(c, next, size, weight) > max && cur) { lines.push(cur); cur = w; } else cur = next;
  }
  if (cur) lines.push(cur);
  return lines;
}

class Vertical extends Node {
  constructor(private readonly time: () => number) { super({}); }
  protected draw(c: C) {
    const v = this.time();
    const seg = segmentAt(v);
    const m = seg.from + (v - seg.start);
    c.save();
    c.translate(-W / 2, -H / 2);
    background(c, W, H, m);
    // camera: a hard cut per segment (with a flash), plus a slow push-in
    const [cx, cy, z0] = seg.cam;
    const z = z0 * (1 + 0.04 * smooth((v - seg.start) / Math.max(1, seg.to - seg.from)));
    c.save(); c.translate(W / 2, H / 2 - 120); c.scale(z, z); c.translate(-cx, -cy);
    drawWorld(c, m);
    c.restore();
    // bands that separate the title and captions from the board
    const top = c.createLinearGradient(0, 0, 0, 300); top.addColorStop(0, 'rgba(3,7,15,0.98)'); top.addColorStop(0.7, 'rgba(3,7,15,0.85)'); top.addColorStop(1, 'rgba(3,7,15,0)');
    c.fillStyle = top; c.fillRect(0, 0, W, 300);
    const bot = c.createLinearGradient(0, H - 700, 0, H); bot.addColorStop(0, 'rgba(3,7,15,0)'); bot.addColorStop(0.35, 'rgba(3,7,15,0.8)'); bot.addColorStop(1, 'rgba(3,7,15,0.98)');
    c.fillStyle = bot; c.fillRect(0, H - 700, W, 700);
    // header
    text(c, 'OTTOCHAIN', W / 2, 120, 44, {weight: 900, align: 'center', spacing: 8});
    text(c, 'a bike-shop story', W / 2, 168, 26, {weight: 600, align: 'center', color: K.yellow});
    // captions (bigger, wrapped, lower third)
    for (const l of filmLines()) {
      const a = window01(m, l.start - 0.1, l.end + 0.25, 0.15);
      if (a <= 0) continue;
      const lines = wrap(c, l.text, 50, W - 140);
      const bh = 30 + lines.length * 64, y0 = H - 300 - bh;
      c.save(); c.globalAlpha = a; roundRect(c, 50, y0, W - 100, bh, 22); c.fillStyle = 'rgba(4,10,22,0.8)'; c.fill(); c.restore();
      lines.forEach((s, i) => text(c, s, W / 2, y0 + 62 + i * 64, 50, {weight: 700, align: 'center', alpha: a}));
    }
    // the climax line, stacked
    const climax = seg.id === 'poisoned' ? window01(m, seg.climax ?? 1e9, (seg.climax ?? 1e9) + 4, 0.4) : 0;
    if (climax > 0) {
      c.save(); c.globalAlpha = 0.85 * climax; c.fillStyle = '#02060e'; c.fillRect(0, 0, W, H); c.restore();
      text(c, 'The agent', W / 2, H / 2 - 140, 96, {weight: 900, align: 'center', alpha: climax});
      text(c, 'was fooled.', W / 2, H / 2 - 40, 96, {weight: 900, align: 'center', alpha: climax});
      text(c, 'The mandate', W / 2, H / 2 + 100, 96, {weight: 900, align: 'center', color: K.yellow, alpha: climax, glow: K.yellow});
      text(c, 'wasn’t.', W / 2, H / 2 + 200, 96, {weight: 900, align: 'center', color: K.yellow, alpha: climax, glow: K.yellow});
    }
    // end card
    const end = ramp(v, VERTICAL_DURATION - 5.5, 0.6);
    if (end > 0) {
      c.save(); c.globalAlpha = 0.9 * end; c.fillStyle = '#03070f'; c.fillRect(0, 0, W, H); c.restore();
      ['Readable contracts', 'Typed assets', 'Contracts that talk', 'Privacy when you need it'].forEach((p, i) => {
        const a = ramp(v, VERTICAL_DURATION - 5.3 + i * 0.25, 0.4) * end;
        const w = measure(c, p, 44, 800) + 80;
        c.save(); c.globalAlpha = a; roundRect(c, W / 2 - w / 2, 560 + i * 120, w, 90, 45); c.fillStyle = 'rgba(12,30,60,0.92)'; c.fill();
        c.strokeStyle = [K.cyan, K.cyan, K.mint, K.yellow][i]; c.lineWidth = 3; c.stroke(); c.restore();
        text(c, p, W / 2, 620 + i * 120, 44, {weight: 800, align: 'center', alpha: a});
      });
      text(c, 'OTTOCHAIN', W / 2, 1180, 120, {weight: 900, align: 'center', alpha: end, spacing: 14});
      text(c, 'all open source', W / 2, 1250, 36, {mono: true, weight: 700, align: 'center', color: K.yellow, alpha: end});
      text(c, 'github.com/scasplte2/ottochain', W / 2, 1300, 32, {mono: true, weight: 700, align: 'center', color: K.yellow, alpha: end});
      c.save(); c.globalAlpha = end; c.strokeStyle = K.yellow; c.lineWidth = 6; c.shadowColor = K.yellow; c.shadowBlur = 30;
      c.beginPath(); c.arc(W / 2, 1520, 130, 0, Math.PI * 2); c.stroke(); c.restore();
    }
    // segment flash between cuts
    const flash = 1 - ramp(v - seg.start, 0, 0.25);
    if (seg.start > 0 && flash > 0) { c.save(); c.globalAlpha = 0.25 * flash; c.fillStyle = '#ffffff'; c.fillRect(0, 0, W, H); c.restore(); }
    text(c, 'Recorded ledger events · local devnet, one operator · AI narration in James Aman’s voice', W / 2, H - 48, 18,
      {mono: true, color: K.dim, align: 'center'});
    c.restore();
  }
}

export default makeScene2D(function* (view) {
  const time = createSignal(0);
  view.add(new Vertical(time));
  const otto = createRef<Img>();
  view.add(<Img ref={otto} src={ottoSrc} width={244} height={244} radius={122} clip opacity={0} y={1520 - H / 2} />);
  yield* tween(VERTICAL_DURATION, v => {
    const s = v * VERTICAL_DURATION;
    time(s);
    otto().opacity(ramp(s, VERTICAL_DURATION - 5.0, 0.6));
  });
});
