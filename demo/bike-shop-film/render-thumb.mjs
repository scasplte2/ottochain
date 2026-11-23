// node render-thumb.mjs <out.png> — renders thumb.html's canvas to a 1280×720 PNG via headless Chromium.
import {writeFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {chromium} from 'playwright-core';
import {createServer} from 'vite';
const out = resolve(process.argv[2] ?? 'output/thumbnail.png');
const server = await createServer({configFile: 'vite.board.config.ts', server: {host: '127.0.0.1', port: 0}, logLevel: 'error'});
await server.listen();
const browser = await chromium.launch({executablePath: process.env.CHROMIUM_PATH});
try {
  const page = await browser.newPage();
  page.on('pageerror', e => console.error(e));
  await page.goto(`http://127.0.0.1:${server.httpServer.address().port}/thumb.html`);
  await page.waitForFunction(() => typeof window.renderThumb === 'function', null, {timeout: 60000});
  const url = await page.evaluate(() => window.renderThumb());
  writeFileSync(out, Buffer.from(url.split(',')[1], 'base64'));
  console.log(out);
} finally { await browser.close(); await server.close(); }
