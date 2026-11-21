// Drawing kit for the bike-shop film: "night workshop blueprint" look built on Otto's palette —
// deep navy board, safety-yellow for authority (keys, signatures), cyan light paths for data,
// coral-red for refusals. Everything is a pure function of time so frames render deterministically.

export type C = CanvasRenderingContext2D;
export type Pt = [number, number];

export const K = {
  bg0: '#050b17',
  bg1: '#0c1a33',
  grid: 'rgba(90,200,255,0.055)',
  gridMajor: 'rgba(90,200,255,0.10)',
  panel: 'rgba(14,30,58,0.78)',
  panelEdge: 'rgba(120,190,255,0.22)',
  trace: '#1c3557',
  cyan: '#38e1ff',
  mint: '#3ef0c8',
  yellow: '#ffc21a',
  red: '#ff4d5e',
  white: '#eaf2ff',
  muted: '#8aa2c8',
  dim: '#4a6189',
  paper: '#f4efe2',
  ink: '#16233b',
};

export const SANS = '"Inter", "Ubuntu Sans", sans-serif';
export const MONO = '"JetBrains Mono", "Ubuntu Mono", monospace';

export const clamp = (x: number, a = 0, b = 1) => Math.min(b, Math.max(a, x));
export const smooth = (u: number) => {
  const v = clamp(u);
  return v * v * (3 - 2 * v);
};
export const ramp = (t: number, start: number, dur: number) => smooth((t - start) / dur);
export const easeOutBack = (u: number) => {
  const v = clamp(u), s = 1.70158;
  return 1 + (s + 1) * Math.pow(v - 1, 3) + s * Math.pow(v - 1, 2);
};
export const easeOutExpo = (u: number) => (u >= 1 ? 1 : 1 - Math.pow(2, -10 * clamp(u)));
export const window01 = (t: number, a: number, b: number, fade = 0.35) => ramp(t, a, fade) * (1 - ramp(t, b - fade, fade));

/** Deterministic PRNG (mulberry32) so particles are identical on every render. */
export function rng(seed: number) {
  let s = seed >>> 0;
  return () => {
    s = (s + 0x6d2b79f5) >>> 0;
    let r = Math.imul(s ^ (s >>> 15), 1 | s);
    r = (r + Math.imul(r ^ (r >>> 7), 61 | r)) ^ r;
    return ((r ^ (r >>> 14)) >>> 0) / 4294967296;
  };
}

export function text(
  c: C, s: string, x: number, y: number, size: number,
  opts: {color?: string; weight?: number; mono?: boolean; align?: CanvasTextAlign; alpha?: number; spacing?: number; glow?: string} = {},
) {
  c.save();
  c.globalAlpha *= opts.alpha ?? 1;
  c.font = `${opts.weight ?? 500} ${size}px ${opts.mono ? MONO : SANS}`;
  c.textAlign = opts.align ?? 'left';
  c.textBaseline = 'alphabetic';
  if (opts.spacing) (c as any).letterSpacing = `${opts.spacing}px`;
  if (opts.glow) { c.shadowColor = opts.glow; c.shadowBlur = size * 0.6; }
  c.fillStyle = opts.color ?? K.white;
  c.fillText(s, x, y);
  c.restore();
}

export function measure(c: C, s: string, size: number, weight = 500, mono = false) {
  c.save();
  c.font = `${weight} ${size}px ${mono ? MONO : SANS}`;
  const w = c.measureText(s).width;
  c.restore();
  return w;
}

export function roundRect(c: C, x: number, y: number, w: number, h: number, r: number) {
  c.beginPath();
  c.roundRect(x, y, w, h, r);
}

/** Frosted-glass module panel with a top highlight and an optional accent edge glow. */
export function panel(c: C, x: number, y: number, w: number, h: number, opts: {accent?: string; glow?: number; alpha?: number; r?: number; dashed?: boolean} = {}) {
  const r = opts.r ?? 18;
  c.save();
  c.globalAlpha *= opts.alpha ?? 1;
  c.shadowColor = 'rgba(0,0,0,0.55)';
  c.shadowBlur = 40;
  c.shadowOffsetY = 18;
  roundRect(c, x, y, w, h, r);
  const g = c.createLinearGradient(x, y, x, y + h);
  g.addColorStop(0, 'rgba(24,46,86,0.86)');
  g.addColorStop(1, 'rgba(10,22,44,0.86)');
  c.fillStyle = g;
  c.fill();
  c.shadowColor = 'transparent';
  // top sheen
  roundRect(c, x + 1, y + 1, w - 2, Math.min(60, h / 3), r);
  const sheen = c.createLinearGradient(x, y, x, y + 60);
  sheen.addColorStop(0, 'rgba(255,255,255,0.07)');
  sheen.addColorStop(1, 'rgba(255,255,255,0)');
  c.fillStyle = sheen;
  c.fill();
  // edge
  roundRect(c, x, y, w, h, r);
  if (opts.dashed) c.setLineDash([8, 8]);
  c.lineWidth = 1.5;
  c.strokeStyle = K.panelEdge;
  c.stroke();
  if (opts.accent && (opts.glow ?? 0) > 0) {
    c.setLineDash([]);
    c.shadowColor = opts.accent;
    c.shadowBlur = 28 * (opts.glow ?? 0);
    c.globalAlpha *= clamp(opts.glow ?? 0);
    c.lineWidth = 2.2;
    c.strokeStyle = opts.accent;
    roundRect(c, x, y, w, h, r);
    c.stroke();
  }
  c.restore();
}

export function polyline(c: C, pts: Pt[], color: string, width: number, alpha = 1) {
  c.save();
  c.globalAlpha *= alpha;
  c.strokeStyle = color;
  c.lineWidth = width;
  c.lineJoin = 'round';
  c.lineCap = 'round';
  c.beginPath();
  pts.forEach(([x, y], i) => (i ? c.lineTo(x, y) : c.moveTo(x, y)));
  c.stroke();
  c.restore();
}

/** A PCB-style trace: dark base, plus an optional energised overlay with bloom.
 *  `reveal` < 1 draws only the leading fraction of the path ("printing" the circuit) with a spark at its tip. */
export function trace(c: C, pts: Pt[], energy = 0, color = K.cyan, width = 3, reveal = 1) {
  if (reveal <= 0) return;
  const p = reveal >= 1 ? pts : prefix(pts, reveal);
  polyline(c, p, K.trace, width + 3);
  polyline(c, p, '#0a1426', width - 1);
  if (reveal < 1) spark(c, p[p.length - 1], color);
  if (energy > 0) {
    c.save();
    c.shadowColor = color;
    c.shadowBlur = 22;
    polyline(c, p, color, width, 0.55 * energy);
    c.restore();
    polyline(c, p, '#ffffff', 1, 0.35 * energy);
  }
}

/** A thin wire that also prints on. */
export function wire(c: C, pts: Pt[], color: string, width: number, alpha: number, reveal: number) {
  if (reveal <= 0) return;
  const p = reveal >= 1 ? pts : prefix(pts, reveal);
  polyline(c, p, color, width, alpha);
  if (reveal < 1) spark(c, p[p.length - 1], K.cyan, 0.7);
}

export function spark(c: C, [x, y]: Pt, color: string, scale = 1) {
  c.save();
  c.globalCompositeOperation = 'lighter';
  const g = c.createRadialGradient(x, y, 0, x, y, 16 * scale);
  g.addColorStop(0, 'rgba(255,255,255,0.95)');
  g.addColorStop(0.3, color);
  g.addColorStop(1, 'rgba(0,0,0,0)');
  c.fillStyle = g;
  c.beginPath(); c.arc(x, y, 16 * scale, 0, Math.PI * 2); c.fill();
  c.restore();
}

/** The leading fraction f of a polyline, as its own polyline. */
export function prefix(pts: Pt[], f: number): Pt[] {
  const ls = lengths(pts);
  let d = clamp(f) * ls.reduce((a, b) => a + b, 0);
  const out: Pt[] = [pts[0]];
  for (let i = 0; i < ls.length; i++) {
    if (d <= ls[i]) {
      const u = ls[i] ? d / ls[i] : 0;
      out.push([pts[i][0] + (pts[i + 1][0] - pts[i][0]) * u, pts[i][1] + (pts[i + 1][1] - pts[i][1]) * u]);
      return out;
    }
    out.push(pts[i + 1]);
    d -= ls[i];
  }
  return out;
}

function lengths(pts: Pt[]) {
  return pts.slice(1).map(([x, y], i) => Math.hypot(x - pts[i][0], y - pts[i][1]));
}
export function pointAt(pts: Pt[], f: number): Pt {
  const ls = lengths(pts);
  let d = clamp(f) * ls.reduce((a, b) => a + b, 0);
  for (let i = 0; i < ls.length; i++) {
    if (d <= ls[i] || i === ls.length - 1) {
      const u = ls[i] ? d / ls[i] : 0;
      return [pts[i][0] + (pts[i + 1][0] - pts[i][0]) * u, pts[i][1] + (pts[i + 1][1] - pts[i][1]) * u];
    }
    d -= ls[i];
  }
  return pts[pts.length - 1];
}

/** Light pulse with a fading comet tail travelling along a polyline (f = head position 0..1). */
export function comet(c: C, pts: Pt[], f: number, color: string, opts: {tail?: number; size?: number} = {}) {
  if (f <= 0 || f >= 1.0001) return;
  const tail = opts.tail ?? 0.16, size = opts.size ?? 7, n = 22;
  c.save();
  c.globalCompositeOperation = 'lighter';
  for (let i = n; i >= 0; i--) {
    const g = f - (tail * i) / n;
    if (g < 0) continue;
    const [x, y] = pointAt(pts, g);
    const a = (1 - i / n) ** 2;
    c.beginPath();
    c.arc(x, y, size * (0.35 + 0.65 * (1 - i / n)), 0, Math.PI * 2);
    c.fillStyle = color;
    c.globalAlpha = 0.5 * a;
    c.fill();
  }
  const [hx, hy] = pointAt(pts, f);
  const rg = c.createRadialGradient(hx, hy, 0, hx, hy, size * 5);
  rg.addColorStop(0, 'rgba(255,255,255,0.95)');
  rg.addColorStop(0.18, color);
  rg.addColorStop(1, 'rgba(0,0,0,0)');
  c.globalAlpha = 1;
  c.fillStyle = rg;
  c.beginPath();
  c.arc(hx, hy, size * 5, 0, Math.PI * 2);
  c.fill();
  c.restore();
}

/** Expanding ring + radial sparks; u = 0..1 progress. */
export function burst(c: C, x: number, y: number, u: number, color: string, seed = 7, scale = 1) {
  if (u <= 0 || u >= 1) return;
  const r = rng(seed);
  c.save();
  c.globalCompositeOperation = 'lighter';
  const e = easeOutExpo(u);
  c.globalAlpha = 1 - u;
  c.lineWidth = 6 * (1 - u) + 1;
  c.strokeStyle = color;
  c.shadowColor = color;
  c.shadowBlur = 30;
  c.beginPath();
  c.arc(x, y, (18 + 150 * e) * scale, 0, Math.PI * 2);
  c.stroke();
  c.globalAlpha = (1 - u) * 0.6;
  c.beginPath();
  c.arc(x, y, (10 + 90 * e) * scale, 0, Math.PI * 2);
  c.stroke();
  for (let i = 0; i < 26; i++) {
    const a = r() * Math.PI * 2, sp = (60 + r() * 220) * scale;
    const d0 = 14 + sp * e * 0.7, d1 = 14 + sp * e;
    c.globalAlpha = (1 - u) * (0.5 + r() * 0.5);
    c.lineWidth = 2;
    c.beginPath();
    c.moveTo(x + Math.cos(a) * d0, y + Math.sin(a) * d0);
    c.lineTo(x + Math.cos(a) * d1, y + Math.sin(a) * d1);
    c.stroke();
  }
  c.restore();
}

export function ping(c: C, x: number, y: number, u: number, color: string, max = 46) {
  if (u <= 0 || u >= 1) return;
  c.save();
  c.globalCompositeOperation = 'lighter';
  c.globalAlpha = (1 - u) * 0.8;
  c.strokeStyle = color;
  c.lineWidth = 2;
  c.shadowColor = color;
  c.shadowBlur = 16;
  c.beginPath();
  c.arc(x, y, 8 + max * easeOutExpo(u), 0, Math.PI * 2);
  c.stroke();
  c.restore();
}

export function check(c: C, x: number, y: number, s: number, color: string, u = 1) {
  if (u <= 0) return;
  const pts: Pt[] = [[x - s, y], [x - s * 0.3, y + s * 0.7], [x + s, y - s * 0.8]];
  const ls = lengths(pts), total = ls[0] + ls[1];
  c.save();
  c.shadowColor = color; c.shadowBlur = 12;
  c.strokeStyle = color; c.lineWidth = 3.2; c.lineCap = 'round'; c.lineJoin = 'round';
  c.setLineDash([total * clamp(u), total]);
  c.beginPath(); c.moveTo(...pts[0]); c.lineTo(...pts[1]); c.lineTo(...pts[2]); c.stroke();
  c.restore();
}

export function cross(c: C, x: number, y: number, s: number, color: string, u = 1) {
  if (u <= 0) return;
  c.save();
  c.shadowColor = color; c.shadowBlur = 14;
  c.strokeStyle = color; c.lineWidth = 3.6; c.lineCap = 'round';
  const a = clamp(u * 2), b = clamp(u * 2 - 1);
  c.beginPath(); c.moveTo(x - s, y - s); c.lineTo(x - s + 2 * s * a, y - s + 2 * s * a); c.stroke();
  if (b > 0) { c.beginPath(); c.moveTo(x + s, y - s); c.lineTo(x + s - 2 * s * b, y - s + 2 * s * b); c.stroke(); }
  c.restore();
}

/** Blueprint background: radial navy, fine + major grid, drifting dust, vignette. */
export function background(c: C, w: number, h: number, t: number) {
  const g = c.createRadialGradient(w * 0.55, h * 0.42, 80, w * 0.5, h * 0.5, w * 0.75);
  g.addColorStop(0, K.bg1);
  g.addColorStop(1, K.bg0);
  c.fillStyle = g;
  c.fillRect(0, 0, w, h);
  c.save();
  c.lineWidth = 1;
  for (let x = 0; x <= w; x += 48) {
    c.strokeStyle = x % 240 === 0 ? K.gridMajor : K.grid;
    c.beginPath(); c.moveTo(x + 0.5, 0); c.lineTo(x + 0.5, h); c.stroke();
  }
  for (let y = 0; y <= h; y += 48) {
    c.strokeStyle = y % 240 === 0 ? K.gridMajor : K.grid;
    c.beginPath(); c.moveTo(0, y + 0.5); c.lineTo(w, y + 0.5); c.stroke();
  }
  const r = rng(42);
  c.globalCompositeOperation = 'lighter';
  for (let i = 0; i < 70; i++) {
    const x0 = r() * w, y0 = r() * h, sp = 6 + r() * 14, ph = r() * 6.28;
    const x = (x0 + t * sp) % w, y = y0 + Math.sin(t * 0.4 + ph) * 12;
    c.globalAlpha = 0.12 + 0.18 * (0.5 + 0.5 * Math.sin(t * 0.9 + ph));
    c.fillStyle = i % 5 === 0 ? K.yellow : K.cyan;
    c.beginPath(); c.arc(x, y, 1 + r() * 1.4, 0, Math.PI * 2); c.fill();
  }
  c.restore();
  const v = c.createRadialGradient(w / 2, h / 2, h * 0.35, w / 2, h / 2, h * 0.95);
  v.addColorStop(0, 'rgba(0,0,0,0)');
  v.addColorStop(1, 'rgba(0,0,0,0.62)');
  c.fillStyle = v;
  c.fillRect(0, 0, w, h);
}

/** Small uppercase chip label. */
export function chip(c: C, s: string, x: number, y: number, color: string, alpha = 1, fill = 'rgba(10,20,40,0.6)') {
  const w = measure(c, s, 15, 700, true) + s.length * 1.2 + 28;
  c.save();
  c.globalAlpha *= alpha;
  roundRect(c, x, y, w, 30, 15);
  c.fillStyle = fill; c.fill();
  c.strokeStyle = color; c.lineWidth = 1.4; c.stroke();
  c.restore();
  text(c, s, x + 14, y + 20.5, 15, {color, weight: 700, mono: true, alpha, spacing: 1.2});
  return w;
}

/** Lower-third caption with a soft backing bar; wraps onto two balanced lines when too wide. */
export function caption(c: C, s: string, w: number, h: number, alpha: number) {
  if (alpha <= 0) return;
  const size = 36, max = w - 240;
  let lines = [s];
  if (measure(c, s, size, 600) > max) {
    const words = s.split(' ');
    let best = 1, bestDiff = Infinity;
    for (let i = 1; i < words.length; i++) {
      const d = Math.abs(measure(c, words.slice(0, i).join(' '), size, 600) - measure(c, words.slice(i).join(' '), size, 600));
      if (d < bestDiff) { bestDiff = d; best = i; }
    }
    lines = [words.slice(0, best).join(' '), words.slice(best).join(' ')];
  }
  const tw = Math.max(...lines.map(l => measure(c, l, size, 600)));
  const bh = 22 + lines.length * 46;
  c.save();
  c.globalAlpha = alpha;
  roundRect(c, w / 2 - tw / 2 - 28, h - 58 - bh, tw + 56, bh, 14);
  c.fillStyle = 'rgba(4,10,22,0.74)';
  c.fill();
  c.restore();
  lines.forEach((l, i) => text(c, l, w / 2, h - 58 - bh + 46 + i * 46, size, {weight: 600, align: 'center', alpha}));
}
