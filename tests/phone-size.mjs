import { chromium } from 'playwright';
import { existsSync } from 'node:fs';
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const edge = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const browser = await chromium.launch(existsSync(edge) ? { executablePath: edge } : {});
const page = await browser.newPage({ viewport: { width: 1440, height: 1150 } });
const errors = [], external = [], checks = [];
page.on('pageerror', error => errors.push(error.message));
page.on('request', request => { if (/^https?:/.test(request.url())) external.push(request.url()); });
const source = new URL('../mockups/first-install-v4-phone-size.html', import.meta.url).href;
const output = new URL('../test-results/', import.meta.url);
await mkdir(output, { recursive: true });
const app = page.frameLocator('#phone-app');
const settle = () => page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
async function ready() { await page.waitForFunction(() => !document.querySelector('#preview-screen').disabled); await settle(); }
async function screen(name) {
  await page.getByLabel('Preview screen', { exact: true }).selectOption(name);
  await app.locator(`.device[data-screen="${name}"]`).waitFor();
  await settle();
}
async function measure() {
  const outer = await page.locator('.calibrated-frame').evaluate(el => {
    const r = el.getBoundingClientRect();
    return { width: r.width, height: r.height };
  });
  const inner = await app.locator('.device').evaluate(el => ({
    width: innerWidth,
    height: innerHeight,
    font: getComputedStyle(el.querySelector('.screen')).fontSize,
    overflow: el.querySelector('.screen').scrollWidth > el.querySelector('.screen').clientWidth,
    errors: document.querySelectorAll('[data-action="like"]').length,
  }));
  return { outer, inner };
}
try {
  await page.goto(source);
  await ready();
  assert.equal(await page.locator('#scale-label').innerText(), '47%');
  const baseline = await measure();
  assert.ok(Math.abs(baseline.outer.width - 410 * .47) < .1);
  assert.ok(Math.abs(baseline.outer.height - 895.333333 * .47) < .1);
  assert.equal(baseline.inner.width, 390);
  assert.equal(baseline.inner.height, 875);
  checks.push('Same 410 × 895.333 frame at 47% as Services');
  await page.getByRole('button', { name: 'Large detail view' }).click();
  assert.equal(await page.locator('#scale-label').innerText(), '100%');
  const detail = await measure();
  assert.equal(detail.outer.width, 410);
  assert.deepEqual(detail.inner, baseline.inner);
  await page.reload();
  await ready();
  assert.equal(await page.locator('#scale-label').innerText(), '47%');
  await page.locator('#phone-scale').fill('60');
  await page.reload();
  await ready();
  assert.equal(await page.locator('#scale-label').innerText(), '60%');
  await page.getByRole('button', { name: 'Your size · 47%' }).click();
  await page.reload();
  await ready();
  assert.equal(await page.locator('#scale-label').innerText(), '47%');
  checks.push('Detail changes only visual scale; slider persists; calibrated restore works');
  for (const width of [1440, 1024, 390, 320]) {
    await page.setViewportSize({ width, height: 1150 });
    for (const name of ['welcome', 'connect', 'libraries', 'ready', 'playlists', 'player', 'settings', 'queue']) {
      await screen(name);
      const measurement = await measure();
      assert.equal(measurement.inner.width, 390);
      assert.equal(measurement.inner.height, 875);
      assert.equal(measurement.inner.overflow, false);
      assert.equal(measurement.inner.errors, 0);
      assert.ok(Math.abs(measurement.outer.width - baseline.outer.width) < .1);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false);
      checks.push(`${width}px ${name}: fixed logical phone, calibrated size, no horizontal overflow`);
    }
  }
  // Actual clicks inside the scaled phone, not direct script invocation.
  await page.setViewportSize({ width: 1440, height: 1150 });
  await page.getByRole('button', { name: 'Restart first-install demo' }).click();
  await app.getByRole('button', { name: 'Connect to Navidrome', exact: true }).click();
  await app.getByLabel('Connection name', { exact: true }).fill('Demo home');
  await app.getByLabel('Server address', { exact: true }).fill('https://music.example.com');
  await app.getByLabel('Username', { exact: true }).fill('demo-listener');
  await app.getByLabel('Password', { exact: true }).fill('dummy-preview-only');
  await app.getByRole('button', { name: 'Connect & discover libraries' }).click();
  await app.locator('.device[data-screen="libraries"]').waitFor();
  await app.getByRole('button', { name: 'Continue', exact: true }).click();
  await app.getByRole('button', { name: 'Choose a playlist', exact: true }).click();
  await app.getByRole('button', { name: 'Shuffle and play Daily rotation', exact: true }).click();
  await app.getByRole('radio', { name: '4 stars', exact: true }).click();
  await app.getByRole('status').filter({ hasText: 'Saved to Navidrome' }).waitFor();
  assert.equal(await app.getByRole('radio', { name: '4 stars', exact: true }).getAttribute('aria-checked'), 'true');
  await app.locator('.seek').fill('147');
  await page.getByRole('button', { name: 'Reopen app', exact: true }).click();
  assert.equal(await app.locator('.elapsed').innerText(), '2:27');
  assert.equal(await app.getByRole('button', { name: 'Resume playback', exact: true }).count(), 1);
  checks.push('Scaled iframe: complete onboarding, library selection, playlist shuffle, stars and restored session');
  await page.getByRole('button', { name: 'Restart first-install demo' }).click();
  await settle();
  await page.screenshot({ path: fileURLToPath(new URL('audio-v4-calibrated-desktop.png', output)), fullPage: true });
  await page.locator('.phone-stage').screenshot({ path: fileURLToPath(new URL('audio-v4-calibrated-phone.png', output)) });
  await screen('player');
  await page.screenshot({ path: fileURLToPath(new URL('audio-v4-calibrated-player.png', output)), fullPage: true });
  assert.deepEqual(errors, []);
  assert.deepEqual(external, []);
  await writeFile(new URL('phone-size-verification.json', output), JSON.stringify({ checks, measuredAt47Percent: baseline, errors, externalRequests: external }, null, 2));
  console.log(`PASS: ${checks.length} phone-size groups. Measured: ${JSON.stringify(baseline)}`);
} finally {
  await browser.close();
}
