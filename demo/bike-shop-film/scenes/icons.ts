// Line-art icons and illustrations for the bike-shop film, drawn in the blueprint style of kit.ts.
// Every function draws at the current transform; `u` (0..1) is a "draw-on" progress where it applies.
import {C, K, clamp, ramp, roundRect, smooth, text} from './kit';

const stroke = (c: C, color: string, w = 3, glow = 0) => {
  c.strokeStyle = color; c.lineWidth = w; c.lineCap = 'round'; c.lineJoin = 'round';
  c.shadowColor = glow ? color : 'transparent'; c.shadowBlur = glow;
};

/** Trace any path progressively using a dash offset; `len` is an over-estimate of the path length. */
export function drawOn(c: C, u: number, len: number, build: () => void) {
  if (u <= 0) return;
  c.save();
  c.setLineDash([len * clamp(u), len]);
  c.beginPath(); build(); c.stroke();
  c.restore();
}

export function wheel(c: C, x: number, y: number, r: number, spin: number, color: string, u = 1) {
  c.save(); stroke(c, color, 3, 8);
  drawOn(c, u, 2 * Math.PI * r + 10, () => c.arc(x, y, r, 0, Math.PI * 2));
  c.globalAlpha *= clamp(u * 1.5 - 0.5);
  for (let k = 0; k < 8; k++) {
    const a = spin + (k * Math.PI) / 4;
    c.beginPath(); c.moveTo(x, y); c.lineTo(x + Math.cos(a) * r * 0.92, y + Math.sin(a) * r * 0.92);
    c.lineWidth = 1; c.stroke();
  }
  c.restore();
}

/** A road bike, ~ (w=300, h=170) with the rear hub at (x, y). */
export function bike(c: C, x: number, y: number, s: number, color: string, u = 1, spin = 0) {
  const r = 62 * s, rear = [x, y], front = [x + 200 * s, y];
  const bb = [x + 92 * s, y + 4 * s], seat = [x + 70 * s, y - 92 * s], head = [x + 172 * s, y - 86 * s];
  wheel(c, rear[0], rear[1], r, spin, color, u);
  wheel(c, front[0], front[1], r, spin, color, u);
  c.save(); stroke(c, color, 4, 10);
  drawOn(c, ramp(u, 0.25, 0.75), 900 * s, () => {
    c.moveTo(rear[0], rear[1]); c.lineTo(bb[0], bb[1]); c.lineTo(seat[0], seat[1]); c.lineTo(rear[0], rear[1]);
    c.moveTo(bb[0], bb[1]); c.lineTo(head[0], head[1]); c.lineTo(seat[0], seat[1]);
    c.moveTo(head[0], head[1]); c.lineTo(front[0], front[1]);
    c.moveTo(head[0] - 6 * s, head[1] - 14 * s); c.lineTo(head[0] + 22 * s, head[1] - 22 * s); c.lineTo(head[0] + 30 * s, head[1] - 6 * s);
    c.moveTo(seat[0] - 18 * s, seat[1] - 10 * s); c.lineTo(seat[0] + 18 * s, seat[1] - 10 * s);
  });
  c.restore();
}

/** A minimal person: head + shoulders + body; `accent` colours a scarf/badge. */
export function person(c: C, x: number, y: number, s: number, color: string, accent: string, u = 1) {
  if (u <= 0) return;
  c.save(); c.globalAlpha *= smooth(u); stroke(c, color, 3.5, 6);
  c.beginPath(); c.arc(x, y - 92 * s, 22 * s, 0, Math.PI * 2); c.stroke();
  c.beginPath(); c.moveTo(x - 46 * s, y + 40 * s); c.quadraticCurveTo(x - 44 * s, y - 52 * s, x, y - 56 * s);
  c.quadraticCurveTo(x + 44 * s, y - 52 * s, x + 46 * s, y + 40 * s); c.stroke();
  c.fillStyle = accent; c.shadowColor = accent; c.shadowBlur = 12;
  c.beginPath(); c.arc(x + 20 * s, y - 24 * s, 6 * s, 0, Math.PI * 2); c.fill();
  c.restore();
}

/** Name tag under a figure. */
export function tag(c: C, x: number, y: number, name: string, role: string, color: string, a = 1) {
  if (a <= 0) return;
  text(c, name, x, y, 30, {weight: 800, align: 'center', alpha: a, color});
  text(c, role, x, y + 30, 18, {mono: true, weight: 700, align: 'center', alpha: a, color: K.muted, spacing: 1.5});
}

/** Storefront line-art with a glowing sign. */
export function shopfront(c: C, x: number, y: number, w: number, h: number, u: number, signGlow: number) {
  c.save(); stroke(c, '#4f74ad', 2.5, 0);
  drawOn(c, u, 2 * (w + h) + 3 * w, () => {
    c.rect(x, y, w, h);
    c.moveTo(x - 20, y); c.lineTo(x + w + 20, y);
    for (let k = 0; k <= 10; k++) { c.moveTo(x - 20 + (k * (w + 40)) / 10, y); c.lineTo(x - 20 + (k * (w + 40)) / 10 + 18, y + 46); }
    c.moveTo(x - 20, y + 46); c.lineTo(x + w + 20, y + 46);
  });
  c.restore();
  const sa = ramp(u, 0.6, 0.4);
  if (sa > 0) {
    c.save(); c.globalAlpha = sa;
    roundRect(c, x + w / 2 - 210, y - 96, 420, 70, 14);
    c.fillStyle = 'rgba(10,20,40,0.85)'; c.fill();
    c.strokeStyle = K.yellow; c.lineWidth = 2.5; c.shadowColor = K.yellow; c.shadowBlur = 16 + 18 * signGlow; c.stroke();
    c.restore();
    text(c, "MAYA'S CYCLES", x + w / 2, y - 48, 36, {weight: 900, align: 'center', color: K.yellow, alpha: sa, spacing: 5, glow: K.yellow});
  }
}

/** Repair stand: a tripod + clamp arm. */
export function stand(c: C, x: number, y: number, s: number, color: string, u: number) {
  c.save(); stroke(c, color, 3, 0);
  drawOn(c, u, 700 * s, () => {
    c.moveTo(x, y); c.lineTo(x, y - 150 * s); c.lineTo(x + 70 * s, y - 175 * s);
    c.moveTo(x, y); c.lineTo(x - 60 * s, y + 40 * s); c.moveTo(x, y); c.lineTo(x + 60 * s, y + 40 * s);
  });
  c.restore();
}

/** The AI purchasing agent: a glowing chip with pins and an eye. */
export function agentChip(c: C, x: number, y: number, s: number, a: number, t: number, accent = K.cyan) {
  if (a <= 0) return;
  c.save(); c.globalAlpha *= a;
  const w = 150 * s, h = 150 * s;
  c.strokeStyle = accent; c.lineWidth = 3; c.shadowColor = accent; c.shadowBlur = 24;
  for (let k = 0; k < 4; k++) {
    const o = -w / 2 + (k + 0.5) * (w / 4);
    c.beginPath(); c.moveTo(x + o, y - h / 2 - 16 * s); c.lineTo(x + o, y - h / 2); c.stroke();
    c.beginPath(); c.moveTo(x + o, y + h / 2); c.lineTo(x + o, y + h / 2 + 16 * s); c.stroke();
    c.beginPath(); c.moveTo(x - w / 2 - 16 * s, y + o); c.lineTo(x - w / 2, y + o); c.stroke();
    c.beginPath(); c.moveTo(x + w / 2, y + o); c.lineTo(x + w / 2 + 16 * s, y + o); c.stroke();
  }
  roundRect(c, x - w / 2, y - h / 2, w, h, 22 * s);
  const g = c.createLinearGradient(x, y - h / 2, x, y + h / 2);
  g.addColorStop(0, 'rgba(30,70,120,0.95)'); g.addColorStop(1, 'rgba(10,30,60,0.95)');
  c.fillStyle = g; c.fill(); c.stroke();
  const p = 0.5 + 0.5 * Math.sin(t * 3);
  c.beginPath(); c.arc(x, y - 8 * s, 26 * s, 0, Math.PI * 2); c.stroke();
  c.fillStyle = accent; c.beginPath(); c.arc(x, y - 8 * s, (9 + 4 * p) * s, 0, Math.PI * 2); c.fill();
  c.restore();
  text(c, 'AI AGENT', x, y + 46 * s, 18 * s, {mono: true, weight: 800, align: 'center', color: accent, alpha: a, spacing: 2});
}

/** Typed-asset token: a coin with label + a 5-bit behaviour strip (T S C E G). */
export function token(c: C, x: number, y: number, s: number, label: string, sub: string, bits: number, a: number,
  opts: {accent?: string; highlightBit?: string; pulse?: number} = {}) {
  if (a <= 0) return;
  const acc = opts.accent ?? K.cyan;
  c.save(); c.globalAlpha *= a;
  c.shadowColor = acc; c.shadowBlur = 20 + 20 * (opts.pulse ?? 0);
  c.beginPath(); c.arc(x, y, 58 * s, 0, Math.PI * 2);
  const g = c.createRadialGradient(x - 18 * s, y - 22 * s, 4, x, y, 60 * s);
  g.addColorStop(0, 'rgba(60,110,170,1)'); g.addColorStop(1, 'rgba(12,32,62,1)');
  c.fillStyle = g; c.fill(); c.lineWidth = 3; c.strokeStyle = acc; c.stroke();
  c.restore();
  text(c, label, x, y + 4 * s, 26 * s, {weight: 900, align: 'center', alpha: a});
  text(c, sub, x, y + 28 * s, 12 * s, {mono: true, weight: 700, align: 'center', color: K.muted, alpha: a, spacing: 1});
  dna(c, x - 60 * s, y + 70 * s, s, bits, a, opts.highlightBit);
}

const BITS: [string, number, string][] = [['T', 16, 'transfer'], ['S', 8, 'split'], ['C', 4, 'combine'], ['E', 2, 'expires'], ['G', 1, 'governed']];
/** The 5-bit behaviour strip. Capabilities (T S C) light cyan; restrictions (E G) light amber. */
export function dna(c: C, x: number, y: number, s: number, bits: number, a: number, highlight?: string) {
  if (a <= 0) return;
  BITS.forEach(([b, v], i) => {
    const on = (bits & v) !== 0, restr = v <= 2;
    const col = on ? (restr ? '#ffb15e' : K.cyan) : 'rgba(120,150,200,0.25)';
    const bx = x + i * 25 * s;
    c.save(); c.globalAlpha *= a;
    roundRect(c, bx, y, 21 * s, 26 * s, 5 * s);
    c.fillStyle = on ? (restr ? 'rgba(255,177,94,0.25)' : 'rgba(56,225,255,0.22)') : 'rgba(20,35,60,0.7)';
    c.fill(); c.lineWidth = highlight === b ? 3 : 1.4; c.strokeStyle = col;
    if (highlight === b) { c.shadowColor = col; c.shadowBlur = 18; }
    c.stroke(); c.restore();
    text(c, b, bx + 10.5 * s, y + 18.5 * s, 14 * s, {mono: true, weight: 800, align: 'center', color: on ? col : 'rgba(150,170,210,0.45)', alpha: a});
  });
}
export const BIT_NAMES = BITS;

/** Contract card (registry entry). */
export function card(c: C, x: number, y: number, w: number, h: number, title: string, sub: string, a: number, accent = K.cyan, glow = 0) {
  if (a <= 0) return;
  c.save(); c.globalAlpha *= a;
  c.shadowColor = 'rgba(0,0,0,0.5)'; c.shadowBlur = 24; c.shadowOffsetY = 10;
  roundRect(c, x, y, w, h, 14); c.fillStyle = 'rgba(16,34,66,0.95)'; c.fill();
  c.shadowColor = accent; c.shadowBlur = 20 * glow; c.shadowOffsetY = 0;
  c.lineWidth = 2; c.strokeStyle = glow > 0 ? accent : 'rgba(120,190,255,0.3)'; c.stroke();
  c.fillStyle = accent; c.globalAlpha *= 0.9; c.fillRect(x + 18, y + 18, 34, 5); c.restore();
  text(c, title, x + 18, y + 54, 22, {weight: 700, alpha: a});
  text(c, sub, x + 18, y + 82, 16, {mono: true, weight: 700, color: K.muted, alpha: a, spacing: 1});
}

/** A paper invoice. */
export function invoice(c: C, x: number, y: number, s: number, lines: [string, number, string?][], rot: number, a: number) {
  if (a <= 0) return;
  c.save(); c.globalAlpha *= a; c.translate(x, y); c.rotate(rot); c.scale(s, s);
  c.shadowColor = 'rgba(0,0,0,0.6)'; c.shadowBlur = 30; c.shadowOffsetY = 12;
  roundRect(c, 0, 0, 400, 236, 12); c.fillStyle = K.paper; c.fill(); c.shadowColor = 'transparent';
  let yy = 40;
  for (const [s1, size, col] of lines) { text(c, s1, 24, yy, size, {weight: size > 30 ? 900 : 700, color: col ?? K.ink, mono: size < 20}); yy += size + 14; }
  c.restore();
}

export function stamp(c: C, x: number, y: number, label: string, color: string, u: number) {
  if (u <= 0) return;
  const s = 1 + 0.7 * (1 - clamp(u * 3));
  c.save(); c.translate(x, y); c.rotate(-0.16); c.scale(s, s); c.globalAlpha *= clamp(u * 3);
  c.shadowColor = color; c.shadowBlur = 18; c.strokeStyle = color; c.lineWidth = 3.5;
  roundRect(c, -92, -30, 184, 60, 10); c.stroke(); c.restore();
  c.save(); c.translate(x, y); c.rotate(-0.16); c.scale(s, s);
  text(c, label, 0, 11, 30, {weight: 900, align: 'center', color, alpha: clamp(u * 3), spacing: 3}); c.restore();
}

/** Laptop outline with a screen area returned for content. */
export function laptop(c: C, x: number, y: number, w: number, h: number, a: number) {
  if (a <= 0) return;
  c.save(); c.globalAlpha *= a;
  roundRect(c, x, y, w, h, 16); c.fillStyle = 'rgba(6,14,28,0.96)'; c.fill();
  c.lineWidth = 3; c.strokeStyle = '#6f8fc4'; c.stroke();
  c.beginPath(); c.moveTo(x - 50, y + h + 10); c.lineTo(x + w + 50, y + h + 10); c.lineTo(x + w + 20, y + h + 34); c.lineTo(x - 20, y + h + 34); c.closePath();
  c.fillStyle = 'rgba(20,36,64,0.95)'; c.fill(); c.stroke();
  c.restore();
}

/** Truck glyph. */
export function truck(c: C, x: number, y: number, s: number, color: string, a: number) {
  if (a <= 0) return;
  c.save(); c.globalAlpha *= a; stroke(c, color, 3, 10);
  c.beginPath(); c.rect(x, y - 50 * s, 90 * s, 50 * s); c.moveTo(x + 90 * s, y - 34 * s); c.lineTo(x + 122 * s, y - 34 * s);
  c.lineTo(x + 138 * s, y - 16 * s); c.lineTo(x + 138 * s, y); c.lineTo(x + 90 * s, y); c.stroke();
  c.beginPath(); c.arc(x + 24 * s, y + 4 * s, 12 * s, 0, Math.PI * 2); c.stroke();
  c.beginPath(); c.arc(x + 112 * s, y + 4 * s, 12 * s, 0, Math.PI * 2); c.stroke();
  c.restore();
}

/** Padlock glyph. */
export function lock(c: C, x: number, y: number, s: number, color: string, a: number) {
  if (a <= 0) return;
  c.save(); c.globalAlpha *= a; stroke(c, color, 3, 8);
  c.beginPath(); c.arc(x, y - 12 * s, 14 * s, Math.PI, 0); c.stroke();
  roundRect(c, x - 22 * s, y - 12 * s, 44 * s, 34 * s, 6 * s); c.stroke(); c.restore();
}
