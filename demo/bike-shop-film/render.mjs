// Render the Motion Canvas project to H.264 by streaming PNG frames from headless Chromium into FFmpeg.
// Usage: node render.mjs <out.mp4> [seconds=20] [fps=30] [audio.wav]
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {existsSync, mkdirSync} from 'node:fs';
import {dirname, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {chromium} from 'playwright-core';
import {createServer} from 'vite';

process.chdir(dirname(fileURLToPath(import.meta.url)));
const [out, secondsArg = '20', fpsArg = '30', audio, fromArg = '0'] = process.argv.slice(2);
assert.ok(out, 'usage: node render.mjs <out.mp4> [seconds] [fps] [audio.wav]');
const destination = resolve(out), seconds = Number(secondsArg), fps = Number(fpsArg);
mkdirSync(dirname(destination), {recursive: true});
let browser, server, encoder;
try {
  server = await createServer({server: {host: '127.0.0.1', port: 0}, logLevel: 'error'});
  await server.listen();
  browser = await chromium.launch({executablePath: process.env.CHROMIUM_PATH, headless: true});
  const page = await browser.newPage();
  page.on('pageerror', e => console.error(e));
  page.on('console', m => { if (m.type() === 'error') console.error(m.text()); });
  const audioArgs = audio && audio !== '-' && existsSync(audio) ? ['-i', resolve(audio), '-map', '0:v', '-map', '1:a', '-c:a', 'aac', '-b:a', '192k', '-shortest'] : [];
  encoder = spawn('ffmpeg', ['-y', '-v', 'error', '-f', 'image2pipe', '-framerate', String(fps), '-i', 'pipe:0', ...audioArgs,
    '-c:v', 'libx264', '-crf', '16', '-preset', 'slow', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', destination],
    {stdio: ['pipe', 'inherit', 'inherit']});
  const done = once(encoder, 'close');
  let count = 0;
  await page.exposeFunction('encodeFrame', async (index, image) => {
    assert.equal(index, count++);
    await new Promise((res, rej) => encoder.stdin.write(Buffer.from(image.split(',')[1], 'base64'), e => (e ? rej(e) : res())));
    if (count % 60 === 0) process.stdout.write(`\rframe ${count}`);
  });
  await page.goto(`http://127.0.0.1:${server.httpServer.address().port}/export.html`);
  await page.waitForFunction(() => typeof window.exportScene === 'function', null, {timeout: 60000});
  const result = await page.evaluate(async ([s, f, a]) => await window.exportScene(s, f, a), [seconds, fps, Number(fromArg)]);
  assert.equal(result, 0, 'Motion Canvas export failed');
  encoder.stdin.end();
  assert.equal((await done)[0], 0, 'FFmpeg encode failed');
  console.log(`\n${destination} (${count} frames)`);
} finally {
  encoder?.kill(); await browser?.close(); await server?.close();
}
