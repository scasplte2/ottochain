// Style frame — "The agent was fooled. The mandate wasn't." (~20 s)
// Every fact on screen comes from PR #218's recorded four-wallet run, via the replay-verified
// demo/mandate-circuit/scene-data.json (gate verdicts are the pinned JLVM's per-clause replay).
import {Img, Node, makeScene2D} from '@motion-canvas/2d';
import {createRef, createSignal, tween} from '@motion-canvas/core';
import facts from '../../mandate-circuit/scene-data.json';
import ottoSrc from '../assets/otto.png';
import {
  C, K, Pt, background, burst, caption, check, chip, clamp, comet, cross, easeOutBack, easeOutExpo, measure,
  panel, ping, polyline, ramp, roundRect, smooth, text, trace, window01,
} from './kit';

const W = 1920, H = 1080, DURATION = 20;

// ── timeline (seconds) ─────────────────────────────────────────────────────────────────────────
const T = {
  invoiceIn: 0.6, stamp: 2.9, launch: 3.7, arrive: 5.2,
  gate0: 5.5, gateStep: 0.55, verdict: 10.4, budget: 11.0, ledger: 11.5, otto: 13.6, end: DURATION,
};
const failIndex = facts.gates.findIndex(g => !g.pass);
const gateTime = (i: number) => T.gate0 + i * T.gateStep + (i > failIndex ? 0.35 : 0);

// ── layout ─────────────────────────────────────────────────────────────────────────────────────
const agent = {x: 110, y: 360, w: 430, h: 310};
const gates = {x: 760, y: 214, w: 410, rowH: 76};
const spineX = gates.x - 46;
const andNode: Pt = [1300, gates.y + 3.5 * gates.rowH + 24];
const desk = {x: 1450, y: 560, w: 370, h: 170};
const gauge = {x: 1636, y: 300, r: 96};
const strip = {y: 868, x0: 90, x1: 1830};

const launchPath: Pt[] = [[agent.x + agent.w, agent.y + 150], [640, agent.y + 150], [640, gates.y - 30], [spineX, gates.y - 30], [spineX, gates.y + 10]];
const spineFull: Pt[] = [[spineX, gates.y + 10], [spineX, gates.y + (facts.gates.length - 0.5) * gates.rowH + 24]];
const rowY = (i: number) => gates.y + i * gates.rowH + 24;
const toDesk: Pt[] = [[andNode[0] + 34, andNode[1]], [1385, andNode[1]], [1385, desk.y + 85], [desk.x, desk.y + 85]];

function header(c: C, t: number) {
  const a = ramp(t, 0.1, 0.6);
  text(c, 'OTTOCHAIN', 72, 86, 30, {weight: 800, spacing: 4, alpha: a});
  chip(c, 'ACT III · THE MANDATE', 300, 63, K.yellow, a);
  text(c, `RECORDED RUN · PR #218 · SNAPSHOT #${facts.ordinal}`, W - 72, 84, 16, {mono: true, color: K.muted, align: 'right', alpha: a, spacing: 1});
  polyline(c, [[72, 112], [W - 72, 112]], 'rgba(120,190,255,0.14)', 1, a);
}

function agentModule(c: C, t: number) {
  const a = ramp(t, 0.2, 0.7);
  const signed = ramp(t, T.stamp, 0.25);
  panel(c, agent.x, agent.y, agent.w, agent.h, {alpha: a, accent: K.yellow, glow: signed * (1 - ramp(t, T.stamp + 1.2, 1.2)) + 0.25 * signed});
  text(c, 'PURCHASING AGENT', agent.x + 28, agent.y + 44, 16, {mono: true, weight: 700, color: K.muted, alpha: a, spacing: 2});
  text(c, 'open-weight model · local', agent.x + 28, agent.y + 70, 15, {color: K.dim, alpha: a});
  // "eye" of the agent: pulsing ring
  const ex = agent.x + agent.w - 62, ey = agent.y + 56, pulse = 0.5 + 0.5 * Math.sin(t * 3);
  c.save(); c.globalAlpha = a; c.shadowColor = K.cyan; c.shadowBlur = 18 * pulse;
  c.strokeStyle = K.cyan; c.lineWidth = 3; c.beginPath(); c.arc(ex, ey, 16, 0, Math.PI * 2); c.stroke();
  c.fillStyle = K.cyan; c.beginPath(); c.arc(ex, ey, 6 + 2 * pulse, 0, Math.PI * 2); c.fill(); c.restore();

  // the invoice card slides in, tilts, settles
  const u = easeOutBack(ramp(t, T.invoiceIn, 1.1) * 1.0);
  const cx = agent.x + 28 + (1 - u) * -520, cy = agent.y + 96;
  c.save();
  c.translate(cx + 182, cy + 92);
  c.rotate((1 - smooth(ramp(t, T.invoiceIn, 1.6))) * -0.12);
  c.translate(-(cx + 182), -(cy + 92));
  c.globalAlpha = a;
  c.shadowColor = 'rgba(0,0,0,0.6)'; c.shadowBlur = 30; c.shadowOffsetY = 12;
  roundRect(c, cx, cy, 364, 184, 12); c.fillStyle = K.paper; c.fill();
  c.restore();
  if (u > 0.02) {
    text(c, 'INVOICE', cx + 22, cy + 34, 14, {mono: true, weight: 700, color: '#6a7488', spacing: 2});
    text(c, `$${(facts.request.amountCents / 100).toFixed(2)}`, cx + 20, cy + 92, 56, {weight: 800, color: K.ink});
    text(c, 'PAY TO', cx + 22, cy + 128, 13, {mono: true, weight: 700, color: '#6a7488', spacing: 2});
    text(c, `“${facts.request.payee}”`, cx + 22, cy + 162, 30, {weight: 800, color: '#c0263a'});
    text(c, `req #${facts.request.requestNumber}`, cx + 342, cy + 34, 14, {mono: true, color: '#6a7488', align: 'right'});
  }
  // signature stamp
  if (signed > 0) {
    const s = 1 + 0.6 * (1 - easeOutExpo(ramp(t, T.stamp, 0.35)));
    const sx = cx + 268, sy = cy + 132;
    c.save(); c.translate(sx, sy); c.rotate(-0.18); c.scale(s, s);
    c.globalAlpha = signed * 0.95;
    c.shadowColor = K.yellow; c.shadowBlur = 18;
    c.strokeStyle = K.yellow; c.lineWidth = 3;
    roundRect(c, -64, -24, 128, 48, 8); c.stroke();
    c.restore();
    c.save(); c.translate(sx, sy); c.rotate(-0.18); c.scale(s, s);
    text(c, 'SIGNED', 0, 8, 22, {weight: 900, color: K.yellow, align: 'center', alpha: signed, spacing: 3});
    c.restore();
    ping(c, sx, sy, ramp(t, T.stamp, 0.9), K.yellow, 90);
  }
}

function gateModule(c: C, t: number) {
  const a = ramp(t, 1.2, 0.8);
  text(c, 'MANDATE · authorize', gates.x, gates.y - 52, 16, {mono: true, weight: 700, color: K.muted, alpha: a, spacing: 2});
  text(c, 'every rule must pass', gates.x + gates.w, gates.y - 52, 16, {color: K.dim, align: 'right', alpha: a});
  // spine
  trace(c, spineFull, 0);
  const spineEnergy = ramp(t, T.arrive, 0.3) * (1 - ramp(t, T.verdict + 0.3, 0.8) * 0.6);
  const reach = clamp((t - T.gate0 + 0.2) / ((facts.gates.length - 1) * T.gateStep + 0.7));
  if (spineEnergy > 0) {
    const end: Pt = [spineX, spineFull[0][1] + (spineFull[1][1] - spineFull[0][1]) * reach];
    trace(c, [spineFull[0], end], spineEnergy, K.cyan);
  }
  facts.gates.forEach((g, i) => {
    const y = rowY(i), when = gateTime(i);
    const appear = ramp(t, 1.3 + i * 0.07, 0.5);
    const lit = ramp(t, when, 0.25);
    const color = g.pass ? K.mint : K.red;
    const branch: Pt[] = [[spineX, y], [gates.x, y]];
    trace(c, branch, lit * (g.pass ? 0.8 : 1), color, 2.5);
    comet(c, branch, (t - when + 0.3) / 0.3, K.cyan, {size: 5, tail: 0.5});
    const failAccent = !g.pass ? ramp(t, when, 0.15) : 0;
    c.save();
    c.translate(gates.x + gates.w / 2, y);
    const shake = !g.pass && t > when && t < when + 0.5 ? Math.sin((t - when) * 90) * 6 * (1 - (t - when) / 0.5) : 0;
    c.translate(shake, 0);
    c.translate(-(gates.x + gates.w / 2), -y);
    panel(c, gates.x, y - 24, gates.w, 48, {alpha: appear, r: 24, accent: color, glow: lit * (g.pass ? 0.55 : 1.2)});
    if (lit > 0) {
      c.save(); c.globalAlpha = lit * (g.pass ? 0.13 : 0.3);
      roundRect(c, gates.x, y - 24, gates.w, 48, 24); c.fillStyle = color; c.fill(); c.restore();
    }
    const wireColor = g.wire === 'signature' ? K.yellow : g.wire === 'payee' ? '#ff8fb3' : g.wire === 'amount' ? '#ffb15e' : K.cyan;
    c.save(); c.globalAlpha = appear; c.fillStyle = wireColor; c.shadowColor = wireColor; c.shadowBlur = 10;
    c.beginPath(); c.arc(gates.x + 26, y, 6, 0, Math.PI * 2); c.fill(); c.restore();
    text(c, g.label, gates.x + 48, y + 8, 22, {weight: 600, color: lit > 0.5 ? K.white : K.muted, alpha: appear});
    if (g.pass) check(c, gates.x + gates.w - 34, y, 9, K.mint, ramp(t, when + 0.05, 0.25));
    else cross(c, gates.x + gates.w - 34, y, 9, K.red, ramp(t, when + 0.05, 0.3));
    c.restore();
    if (g.pass) ping(c, gates.x + gates.w - 34, y, ramp(t, when, 0.6), K.mint, 30);
    else burst(c, gates.x + gates.w - 34, y, ramp(t, when, 1.1), K.red, 11, 1.1);
  });
  // AND collector
  const verdict = ramp(t, T.verdict, 0.3);
  facts.gates.forEach((g, i) => {
    const y = rowY(i);
    polyline(c, [[gates.x + gates.w, y], [andNode[0] - 40, y], [andNode[0] - 40, andNode[1]], [andNode[0] - 34, andNode[1]]], K.trace, 2, a);
    if (t > gateTime(i)) polyline(c, [[gates.x + gates.w, y], [andNode[0] - 40, y]], g.pass ? K.mint : K.red, 2, 0.5);
  });
  c.save(); c.globalAlpha = a;
  c.shadowColor = verdict ? K.red : 'transparent'; c.shadowBlur = 40 * verdict;
  c.beginPath(); c.arc(andNode[0], andNode[1], 34, 0, Math.PI * 2);
  c.fillStyle = verdict ? `rgba(255,77,94,${0.25 + 0.2 * verdict})` : 'rgba(14,30,58,0.9)'; c.fill();
  c.lineWidth = 2.5; c.strokeStyle = verdict ? K.red : K.panelEdge; c.stroke(); c.restore();
  text(c, 'ALL', andNode[0], andNode[1] + 7, 19, {weight: 800, align: 'center', color: verdict ? K.red : K.white, alpha: a, spacing: 2});
  burst(c, andNode[0], andNode[1], ramp(t, T.verdict, 1.2), K.red, 23, 1.0);
  if (verdict > 0) {
    const w = measure(c, 'REFUSED', 40, 900) + 40;
    c.save(); c.globalAlpha = verdict; c.translate(andNode[0], andNode[1] - 92); c.scale(0.7 + 0.3 * easeOutBack(verdict), 0.7 + 0.3 * easeOutBack(verdict));
    roundRect(c, -w / 2, -34, w, 56, 10); c.fillStyle = 'rgba(255,77,94,0.16)'; c.fill();
    c.strokeStyle = K.red; c.lineWidth = 2.5; c.shadowColor = K.red; c.shadowBlur = 22; c.stroke(); c.restore();
    c.save(); c.translate(andNode[0], andNode[1] - 92); c.scale(0.7 + 0.3 * easeOutBack(verdict), 0.7 + 0.3 * easeOutBack(verdict));
    text(c, 'REFUSED', 0, 8, 40, {weight: 900, color: K.red, align: 'center', alpha: verdict, spacing: 4, glow: K.red});
    c.restore();
  }
}

function deskModule(c: C, t: number) {
  const a = ramp(t, 1.6, 0.8);
  trace(c, toDesk, 0);
  panel(c, desk.x, desk.y, desk.w, desk.h, {alpha: a * 0.85, dashed: true});
  text(c, 'SUPPLIER ORDER DESK', desk.x + 26, desk.y + 44, 16, {mono: true, weight: 700, color: K.muted, alpha: a, spacing: 2});
  text(c, 'waiting for orders', desk.x + 26, desk.y + 80, 22, {color: K.dim, alpha: a});
  const nr = ramp(t, T.verdict + 0.6, 0.5);
  if (nr > 0) {
    text(c, 'never reached', desk.x + 26, desk.y + 130, 30, {weight: 800, color: K.white, alpha: nr});
    // barrier on the line
    const bx = 1385, by = andNode[1] + 40;
    c.save(); c.globalAlpha = nr; c.strokeStyle = K.red; c.lineWidth = 5; c.shadowColor = K.red; c.shadowBlur = 16;
    c.beginPath(); c.moveTo(bx - 26, by - 16); c.lineTo(bx + 26, by - 16); c.stroke();
    c.beginPath(); c.moveTo(bx - 26, by + 16); c.lineTo(bx + 26, by + 16); c.stroke(); c.restore();
  }
}

function gaugeModule(c: C, t: number) {
  const a = ramp(t, 1.8, 0.8);
  const frac = facts.balance / facts.startBudget, hl = window01(t, T.budget, T.budget + 3.2, 0.4);
  c.save(); c.globalAlpha = a;
  c.lineWidth = 14; c.lineCap = 'round';
  c.strokeStyle = 'rgba(120,190,255,0.12)';
  c.beginPath(); c.arc(gauge.x, gauge.y, gauge.r, -Math.PI / 2, Math.PI * 1.5); c.stroke();
  c.shadowColor = K.cyan; c.shadowBlur = 16 + 24 * hl; c.strokeStyle = K.cyan;
  c.beginPath(); c.arc(gauge.x, gauge.y, gauge.r, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * frac); c.stroke();
  c.restore();
  text(c, `$${facts.balance}`, gauge.x, gauge.y + 18, 54, {weight: 800, align: 'center', alpha: a});
  text(c, 'BUDGET LEFT', gauge.x, gauge.y + gauge.r + 46, 15, {mono: true, weight: 700, color: K.muted, align: 'center', alpha: a, spacing: 2});
  text(c, 'unchanged', gauge.x, gauge.y + gauge.r + 76, 22, {weight: 700, color: K.mint, align: 'center', alpha: hl, glow: K.mint});
}

function ledgerStrip(c: C, t: number) {
  const a = ramp(t, 2, 0.8);
  text(c, 'LEDGER · SNAPSHOTS', strip.x0, strip.y - 34, 15, {mono: true, weight: 700, color: K.muted, alpha: a, spacing: 2});
  polyline(c, [[strip.x0, strip.y], [strip.x1, strip.y]], K.trace, 3, a);
  const spacing = 120, base = facts.ordinal - 7, drift = Math.min(t, T.ledger) * 6;
  for (let k = 0; k < 16; k++) {
    const n = base + k, x = strip.x0 + 40 + k * spacing - drift;
    if (x < strip.x0 || x > strip.x1) continue;
    const isRef = n === facts.ordinal;
    c.save(); c.globalAlpha = a * (isRef ? 1 : 0.6);
    c.fillStyle = K.dim; c.beginPath(); c.arc(x, strip.y, 4, 0, Math.PI * 2); c.fill(); c.restore();
    text(c, `#${n}`, x, strip.y + 34, 15, {mono: true, color: K.dim, align: 'center', alpha: a});
    if (isRef) {
      const d = ramp(t, T.ledger, 0.5);
      if (d > 0) {
        const yy = strip.y - 90 * (1 - easeOutBack(d));
        c.save(); c.globalAlpha = d; c.shadowColor = K.red; c.shadowBlur = 20; c.fillStyle = K.red;
        c.beginPath(); c.moveTo(x, yy - 4); c.lineTo(x - 12, yy - 26); c.lineTo(x + 12, yy - 26); c.closePath(); c.fill(); c.restore();
        text(c, 'REFUSAL RECEIPT', x, yy - 40, 15, {mono: true, weight: 800, color: K.red, align: 'center', alpha: d, spacing: 1});
        ping(c, x, strip.y, ramp(t, T.ledger + 0.4, 0.9), K.red, 40);
      }
    }
  }
}

function launch(c: C, t: number) {
  trace(c, launchPath, ramp(t, T.launch, 0.2) * (1 - ramp(t, T.arrive + 0.8, 1)), K.yellow);
  comet(c, launchPath, (t - T.launch) / (T.arrive - T.launch), K.yellow, {size: 8, tail: 0.25});
}

const captions: [number, number, string][] = [
  [0.6, 3.6, 'A signed order names a supplier the owner never approved.'],
  [3.7, 6.4, 'The agent’s signature is valid. It goes to the network.'],
  [6.5, 10.2, 'Every rule in the mandate is checked, one by one.'],
  [10.3, 12.4, 'One rule fails: this supplier is not on the list.'],
  [12.5, 13.8, `Nothing moves. The budget stays at $${facts.balance}.`],
];

class Board extends Node {
  constructor(private readonly time: () => number) { super({}); }
  protected draw(c: C) {
    const t = this.time();
    c.save();
    c.translate(-W / 2, -H / 2);
    background(c, W, H, t);
    // slow push-in + impact shake
    const zoom = 1 + 0.035 * smooth(t / DURATION);
    const shake = t > T.verdict && t < T.verdict + 0.45 ? Math.sin((t - T.verdict) * 70) * 7 * (1 - (t - T.verdict) / 0.45) : 0;
    c.translate(W / 2 + shake, H / 2);
    c.scale(zoom, zoom);
    c.translate(-W / 2, -H / 2);
    header(c, t);
    deskModule(c, t);
    gateModule(c, t);
    agentModule(c, t);
    launch(c, t);
    gaugeModule(c, t);
    ledgerStrip(c, t);
    c.restore();
    c.save();
    c.translate(-W / 2, -H / 2);
    for (const [a, b, s] of captions) caption(c, s, W, H, window01(t, a, b, 0.3));
    // Otto's line: dim the board, then the line lands centre-stage
    const o = ramp(t, T.otto + 0.3, 0.5);
    if (o > 0) {
      c.save(); c.globalAlpha = 0.86 * ramp(t, T.otto, 0.6); c.fillStyle = '#02060e'; c.fillRect(0, 0, W, H); c.restore();
      const ox = 470, oy = H / 2 + 10;
      c.save(); c.globalAlpha = o; c.shadowColor = K.yellow; c.shadowBlur = 40; c.strokeStyle = K.yellow; c.lineWidth = 6;
      c.beginPath(); c.arc(ox, oy, 128, 0, Math.PI * 2); c.stroke(); c.restore();
      ping(c, ox, oy, ramp(t, T.otto + 0.2, 1.2), K.yellow, 120);
      text(c, 'OTTO', ox, oy + 180, 18, {mono: true, weight: 800, color: K.yellow, align: 'center', alpha: o, spacing: 6});
      text(c, 'The agent was fooled.', 660, oy - 14, 72, {weight: 800, alpha: o});
      text(c, 'The mandate wasn’t.', 660, oy + 72, 72, {weight: 800, color: K.yellow, alpha: ramp(t, T.otto + 1.1, 0.5), glow: K.yellow});
      text(c, 'Signed, valid, and still refused — the owner’s rules said no.', 664, oy + 136, 26, {color: K.muted, alpha: ramp(t, T.otto + 2.0, 0.6)});
    }
    text(c, 'Animation of recorded ledger events · local devnet, one operator', W - 72, H - 34, 14, {mono: true, color: K.dim, align: 'right'});
    c.restore();
  }
}

export default makeScene2D(function* (view) {
  const time = createSignal(0);
  view.add(new Board(time));
  const otto = createRef<Img>();
  const ring = createRef<Node>();
  view.add(
    <Node ref={ring} x={-W / 2 + 470} y={10} opacity={0} scale={0.6}>
      <Img ref={otto} src={ottoSrc} width={244} height={244} radius={122} clip />
    </Node>,
  );
  yield* tween(T.otto, v => time(v * T.otto));
  yield* tween(0.45, v => {
    time(T.otto + v * 0.45);
    ring().opacity(clamp(v * 1.6));
    ring().scale(0.6 + 0.4 * easeOutBack(v));
  });
  yield* tween(T.end - T.otto - 0.45, v => {
    const t = T.otto + 0.45 + v * (T.end - T.otto - 0.45);
    time(t);
    ring().rotation(Math.sin(t * 2.2) * 3);
  });
});
