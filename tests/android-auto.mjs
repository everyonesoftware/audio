import { chromium } from 'playwright';
import { existsSync } from 'node:fs';
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const edge = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const browser = await chromium.launch(existsSync(edge) ? { executablePath: edge } : {});
const page = await browser.newPage({ viewport: { width: 1600, height: 1150 } });
const errors = [], external = [], checks = [];
page.on('pageerror', e => errors.push(e.message));
page.on('request', r => { if (/^https?:/.test(r.url())) external.push(r.url()); });
const source = new URL('../mockups/android-auto-v1.html', import.meta.url).href;
const out = new URL('../test-results/', import.meta.url);
await mkdir(out, { recursive: true });
const car = page.locator('#car-content');
const phone = page.locator('#phone-content');
const click = (root, name) => root.getByRole('button', { name, exact: true }).click();
const settle = () => page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));
const capture = name => page.screenshot({ path: fileURLToPath(new URL(name, out)), fullPage: true });
async function fits() {
  await settle();
  const result = await page.evaluate(() => {
    const rect = el => el.getBoundingClientRect();
    const devices = [...document.querySelectorAll('.car,.phone')].filter(el => el.getBoundingClientRect().width > 0);
    return {
      width: innerWidth,
      view: document.querySelector('.workspace').dataset.view,
      screen: document.querySelector('#screen').value,
      outside: [...document.querySelectorAll('body *')].filter(el => {
        const r = rect(el);
        return r.width > 0 && (r.right > innerWidth + 1 || r.left < -1) && !el.closest('.car,.phone');
      }).map(el => ({ name: el.className || el.tagName, right: rect(el).right, width: rect(el).width })),
      horizontal: document.documentElement.scrollWidth > innerWidth,
      devices: devices.map(d => {
        const r = rect(d);
        const content = d.querySelector('.car-body,.phone-main');
        const buttons = [...content.querySelectorAll('button')].filter(b => !b.closest('.rows'));
        return {
          name: d.className,
          overflow: content.scrollHeight > content.clientHeight + 1,
          controlsOutside: buttons.some(b => { const br = rect(b); return br.bottom > r.bottom + 1 || br.right > r.right + 1; }),
          brokenImages: [...d.querySelectorAll('img')].some(img => !img.complete || img.naturalWidth === 0),
        };
      }),
    };
  });
  assert.equal(result.horizontal, false, JSON.stringify(result));
  for (const d of result.devices) {
    assert.equal(d.overflow, false, JSON.stringify(d));
    assert.equal(d.controlsOutside, false, JSON.stringify(d));
    assert.equal(d.brokenImages, false, JSON.stringify(d));
  }
}
try {
  await page.goto(source);
  await car.locator('.song-title').waitFor();
  await fits();
  assert.equal(await car.getByRole('button', { name: 'Resume', exact: true }).count(), 1);
  assert.equal(await car.getByRole('button', { name: /Rate song/ }).count(), 0);
  assert.deepEqual(await car.locator('.transport button').evaluateAll(bs => bs.map(b => b.getAttribute('aria-label'))), ['Open queue', 'Previous track', 'Resume', 'Next track', 'Shuffle']);
  await capture('audio-auto-desktop-and-phone.png');
  checks.push('Paused initial player, paired layout, car action order, no car rating popup');

  await click(car, 'Resume');
  assert.equal(await phone.getByRole('button', { name: 'Pause', exact: true }).count(), 1);
  await click(phone, 'Pause');
  await click(car, 'Next track');
  assert.equal(await car.locator('.song-title').innerText(), 'Two rivers');
  assert.equal(await phone.locator('.phone-title h2').innerText(), 'Two rivers');
  checks.push('Car and phone share playback state and current song');

  await click(car, 'Open queue');
  await fits();
  assert.equal(await car.locator('.media-row.active').count(), 1);
  await car.locator('.row-main').filter({ hasText: 'Still here' }).click();
  assert.equal(await car.locator('.song-title').innerText(), 'Still here');
  await click(car, 'Pause');
  const before = await page.evaluate(() => JSON.stringify(state.order));
  await page.locator('#screen').selectOption('playlists');
  await car.locator('.row-main').filter({ hasText: 'Daily rotation' }).click();
  assert.equal(await page.evaluate(() => JSON.stringify(state.order)), before);
  await click(car, 'Back to playlists');
  await click(car, 'Shuffle and play Daily rotation');
  assert.equal(await page.locator('#screen').inputValue(), 'player');
  assert.equal(await car.getByRole('button', { name: 'Pause', exact: true }).count(), 1);
  assert.equal(await page.evaluate(() => state.playlist), 1);
  assert.equal(await page.evaluate(() => new Set(state.order).size === songs.length), true);
  checks.push('Queue selection, playlist inspection preserves queue, shuffle starts playlist and returns to player');

  await click(car, 'Pause');
  await phone.getByRole('button', { name: /^Rate song/ }).click();
  await click(page.locator('#rating'), '4 stars');
  assert.equal(await phone.getByRole('button', { name: 'Rate song · 4 stars' }).count(), 1);
  assert.equal(await page.evaluate(() => document.activeElement.getAttribute('data-action')), 'rating');
  await phone.getByRole('button', { name: /^Rate song/ }).click();
  await page.keyboard.press('Escape');
  assert.equal(await page.locator('#rating').evaluate(d => d.open), false);
  checks.push('Phone-only rating, simulated save, Escape dismissal, focus return');

  await page.locator('#connection').selectOption('unavailable');
  await fits();
  await click(car, 'Try again');
  assert.equal(await car.getByRole('button', { name: 'Connecting…' }).isDisabled(), true);
  await car.locator('.song-title').waitFor();
  assert.equal(await page.locator('#connection').inputValue(), 'connected');
  checks.push('Unavailable connection, disabled pending state, retry recovery');

  for (const width of [1600, 1024, 390, 320]) {
    await page.setViewportSize({ width, height: 1150 });
    for (const view of ['Compare both', 'Car display', 'Phone']) {
      await page.getByRole('button', { name: view, exact: true }).click();
      for (const screen of ['player', 'playlists', 'queue']) {
        await page.locator('#screen').selectOption(screen);
        await fits();
      }
    }
    checks.push(`Layout matrix: ${width}px, all three screens and all three review modes`);
  }
  await page.setViewportSize({ width: 1600, height: 1150 });
  await page.getByRole('button', { name: 'Compare both', exact: true }).click();
  await page.getByRole('button', { name: 'Reset demo', exact: true }).click();
  await page.locator('#screen').selectOption('playlists');
  await capture('audio-auto-playlists.png');
  await page.getByRole('button', { name: 'Reset demo', exact: true }).click();
  await page.getByRole('button', { name: 'Phone', exact: true }).click();
  await page.setViewportSize({ width: 390, height: 1100 });
  await fits();
  await capture('audio-auto-phone.png');
  await click(phone, 'Resume');
  await click(phone, 'Pause');
  await click(phone, 'Playlists');
  await click(phone, 'Shuffle and play The long way home');
  assert.equal(await phone.getByRole('button', { name: 'Pause', exact: true }).count(), 1);
  await fits();
  checks.push('Primary phone click path at 390px');
  await page.setViewportSize({ width: 1600, height: 1150 });
  await page.getByRole('button', { name: 'Compare both', exact: true }).click();
  await page.getByRole('button', { name: 'Reset demo', exact: true }).click();
  await capture('audio-auto-desktop-and-phone.png');
  assert.deepEqual(errors, []);
  assert.deepEqual(external, []);
  await writeFile(new URL('audio-auto-report.json', out), JSON.stringify({ checks, errors, external }, null, 2));
  console.log(JSON.stringify({ passed: checks.length, checks, errors, external }, null, 2));
} finally {
  await browser.close();
}
