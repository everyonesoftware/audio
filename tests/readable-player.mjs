import { chromium } from 'playwright';
import { existsSync } from 'node:fs';
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const edge = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const browser = await chromium.launch(existsSync(edge) ? { executablePath: edge } : {});
const page = await browser.newPage({ viewport: { width: 1440, height: 1200 } });
const errors = [], external = [], checks = [];
page.on('pageerror', error => errors.push(error.message));
page.on('request', r => { if (/^https?:/.test(r.url())) external.push(r.url()); });
const source = new URL('../mockups/first-install-v5-readable.html', import.meta.url).href;
const output = new URL('../test-results/', import.meta.url);
await mkdir(output, { recursive: true });
const app = page.frameLocator('#phone-app');
const d = app.locator('.device');
const settle = () => page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));
const click = name => app.getByRole('button', { name, exact: true }).click();
async function ready() { await page.waitForFunction(() => !document.querySelector('#preview-screen').disabled); await settle(); }
async function preview(name) {
  await page.getByLabel('Preview screen', { exact: true }).selectOption(name);
  await app.locator(`.device[data-screen="${name}"]`).waitFor();
  await settle();
}
async function outcome(name) { await page.getByLabel('Simulated server outcome').selectOption(name); }
async function saved() { return await app.locator('body').evaluate(() => JSON.parse(localStorage.getItem('audio-first-install-v5'))); }
async function chooseSpecificSampleSong() {
  await preview('playlists');
  await click('Shuffle and play A quieter kind of evening');
  await click('Queue');
  await app.locator('.song-row').filter({ hasText: 'In the middle' }).locator('button').click();
}
async function playerGeometry() {
  const geometry = await d.evaluate(el => {
    const screen = el.querySelector('.screen'), r = screen.getBoundingClientRect();
    const footer = el.querySelector('.secondary-row').getBoundingClientRect();
    const art = el.querySelector('.player-art').getBoundingClientRect();
    const trigger = el.querySelector('.rating-trigger').getBoundingClientRect();
    return {
      width: innerWidth, height: innerHeight,
      horizontal: screen.scrollWidth > screen.clientWidth,
      vertical: screen.scrollHeight > screen.clientHeight,
      footerGap: r.bottom - footer.bottom,
      artWidth: art.width, artHeight: art.height,
      titleFont: getComputedStyle(el.querySelector('.player-song h3')).fontSize,
      artistFont: getComputedStyle(el.querySelector('.player-song p')).fontSize,
      triggerWidth: trigger.width,
      inlineRatingRows: el.querySelectorAll('.rating-options').length,
    };
  });
  assert.equal(geometry.width, 390);
  assert.equal(geometry.height, 875);
  assert.equal(geometry.horizontal, false, JSON.stringify(geometry));
  assert.equal(geometry.vertical, false, JSON.stringify(geometry));
  assert.ok(geometry.footerGap >= 0 && geometry.footerGap < 24, JSON.stringify(geometry));
  assert.ok(Math.abs(geometry.artWidth - geometry.artHeight) < 1, JSON.stringify(geometry));
  assert.ok(geometry.artWidth >= 180, JSON.stringify(geometry));
  assert.equal(geometry.titleFont, '32px');
  assert.equal(geometry.artistFont, '20px');
  assert.ok(geometry.triggerWidth >= 56);
  assert.equal(geometry.inlineRatingRows, 0);
  return geometry;
}
try {
  await page.goto(source);
  await ready();
  await page.getByRole('button', { name: 'Your size · 47%' }).click();
  await page.getByRole('button', { name: 'Restart first-install demo' }).click();
  // One-click dummy registration; no typing, no production validation weakened.
  await click('Connect to Navidrome');
  assert.equal(await app.getByLabel('Connection name', { exact: true }).inputValue(), 'Demo home');
  assert.equal(await app.getByLabel('Server address', { exact: true }).inputValue(), 'https://music.example.com');
  assert.equal(await app.getByLabel('Username', { exact: true }).inputValue(), 'demo-listener');
  assert.equal(await app.getByLabel('Password', { exact: true }).inputValue(), 'demo-only');
  await click('Connect & discover libraries');
  await app.locator('.device[data-screen="libraries"]').waitFor();
  assert.equal(await d.locator('.disclosure').count(), 0);
  assert.ok(!(await d.innerText()).includes('What happens to playlists?'));
  const libraryLayout = await d.evaluate(el => {
    const screen = el.querySelector('.screen');
    const action = el.querySelector('[data-action="save-libraries"]');
    const viewport = screen.getBoundingClientRect();
    const button = action.getBoundingClientRect();
    return {
      scrollTop: screen.scrollTop,
      overflow: screen.scrollHeight > screen.clientHeight,
      visible: button.top >= viewport.top && button.bottom <= viewport.bottom,
      height: button.height,
    };
  });
  assert.equal(libraryLayout.scrollTop, 0);
  assert.equal(libraryLayout.overflow, false, JSON.stringify(libraryLayout));
  assert.equal(libraryLayout.visible, true, JSON.stringify(libraryLayout));
  assert.ok(libraryLayout.height >= 56);
  await page.locator('.phone-stage').screenshot({ path: fileURLToPath(new URL('audio-v5-libraries-no-scroll.png', output)) });
  checks.push('Library setup: playlist explanation removed; Continue fully visible at initial scroll');
  await click('Continue');
  await click('Choose a playlist');
  await click('Shuffle and play Daily rotation');
  assert.ok(!JSON.stringify(await saved()).includes('demo-only'));
  checks.push('Prefilled demo registration completes without typing; no password persisted');
  await playerGeometry();
  const physical = await page.locator('.calibrated-frame').boundingBox();
  assert.ok(Math.abs(physical.width - 410 * .47) < .1);
  checks.push('Original 47% physical calibration; full-height player, larger text, compact rating trigger');
  for (const width of [1440, 1024, 390, 320]) {
    await page.setViewportSize({ width, height: 1200 });
    for (const screen of ['welcome', 'connect', 'libraries', 'ready', 'playlists', 'player', 'settings', 'queue']) {
      await preview(screen);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false);
      assert.equal(await d.locator('.screen').evaluate(e => e.scrollWidth > e.clientWidth), false);
      if (screen === 'player') await playerGeometry();
      checks.push(`${width}px ${screen}: no horizontal overflow; fixed logical phone`);
    }
  }
  await page.setViewportSize({ width: 1440, height: 1200 });
  await chooseSpecificSampleSong();
  const beforeOpen = await saved();
  await app.locator('.rating-trigger').click();
  await app.getByRole('dialog', { name: 'Rate this song' }).waitFor();
  const popup = app.getByRole('dialog');
  const popupBounds = await popup.boundingBox();
  assert.ok(popupBounds.width > 140); // visual, at the unchanged 47% scale
  const targets = await popup.locator('[role="radio"]').evaluateAll(nodes => nodes.map(n => ({ width:n.getBoundingClientRect().width, height:n.getBoundingClientRect().height })));
  assert.equal(targets.length, 5);
  assert.ok(targets.every(t => t.width >= 56 && t.height >= 76));
  assert.equal(await popup.getByRole('radio', { name: '4 stars' }).count(), 1);
  await page.keyboard.press('Escape');
  assert.equal(await popup.count(), 0);
  assert.deepEqual(await saved(), beforeOpen);
  assert.equal(await app.locator('.rating-trigger').evaluate(e => e === document.activeElement), true);
  checks.push('Large native modal: five 56×80 targets, Escape cancels without mutation and restores focus');
  await app.locator('.rating-trigger').click();
  await popup.getByRole('radio', { name: '4 stars' }).click();
  await popup.getByRole('status').filter({ hasText: 'Saved to Navidrome' }).waitFor();
  assert.equal(await popup.getByRole('radio', { name: '4 stars' }).getAttribute('aria-checked'), 'true');
  await click('Done');
  assert.equal(await app.locator('.rating-trigger span').innerText(), '4/5');
  assert.equal(await app.locator('.rating-trigger').evaluate(e => e === document.activeElement), true);
  await app.locator('.rating-trigger').click();
  await outcome('rating-error');
  await popup.getByRole('radio', { name: '5 stars' }).click();
  await popup.getByRole('status').filter({ hasText: 'Save failed' }).waitFor();
  assert.equal(await popup.getByRole('radio', { name: '4 stars' }).getAttribute('aria-checked'), 'true');
  await outcome('offline');
  await click('Retry');
  await popup.getByRole('status').filter({ hasText: 'offline' }).waitFor();
  await outcome('success');
  await click('Retry');
  await popup.getByRole('status').filter({ hasText: 'Saved to Navidrome' }).waitFor();
  assert.equal(await popup.getByRole('radio', { name: '5 stars' }).getAttribute('aria-checked'), 'true');
  await click('Clear rating');
  await popup.getByRole('status').filter({ hasText: 'Saved to Navidrome' }).waitFor();
  assert.equal(await popup.locator('[role="radio"][aria-checked="true"]').count(), 0);
  await popup.getByRole('radio', { name: '1 star', exact: true }).focus();
  await page.keyboard.press('End');
  await popup.getByRole('status').filter({ hasText: 'Saved to Navidrome' }).waitFor();
  assert.equal(await popup.getByRole('radio', { name: '5 stars' }).getAttribute('aria-checked'), 'true');
  await click('Done');
  checks.push('Popup selection, save feedback, retained confirmed rating on failure/offline, retry, clear, keyboard');
  // Closing during a request must not attach its rating to the next song.
  await app.locator('.rating-trigger').click();
  await popup.getByRole('radio', { name: '3 stars' }).click();
  await click('Done');
  await click('Next track');
  await page.waitForTimeout(550);
  assert.notEqual(await app.locator('.rating-trigger span').innerText(), '3/5');
  await click('Previous track');
  assert.equal(await app.locator('.rating-trigger span').innerText(), '3/5');
  await app.locator('.seek').fill('129');
  const stored = await saved();
  await page.getByRole('button', { name: 'Reopen app', exact: true }).click();
  assert.equal(await app.locator('.elapsed').innerText(), '2:09');
  assert.deepEqual(await saved(), stored);
  checks.push('Close while saving + next track isolates rating; session and position restore unchanged');
  // Error retry needs no re-entry of any demo credentials.
  await preview('connect');
  await outcome('auth');
  await click('Connect & discover libraries');
  await app.locator('#auth-error').filter({ hasText: 'Sign-in failed' }).waitFor();
  assert.equal(await app.getByLabel('Password', { exact: true }).inputValue(), 'demo-only');
  await outcome('success');
  await click('Connect & discover libraries');
  await app.locator('.device[data-screen="libraries"]').waitFor();
  checks.push('Mockup sign-in failure and retry remain usable without typing');
  // Final captures at the same real-size calibration. Also keep a legible detail capture.
  await chooseSpecificSampleSong();
  await page.getByRole('button', { name: 'Your size · 47%' }).click();
  await settle();
  await page.screenshot({ path: fileURLToPath(new URL('audio-v5-desktop.png', output)), fullPage:true });
  await page.locator('.phone-stage').screenshot({ path: fileURLToPath(new URL('audio-v5-player-47.png', output)) });
  await app.locator('.rating-trigger').click();
  await page.locator('.phone-stage').screenshot({ path: fileURLToPath(new URL('audio-v5-rating-47.png', output)) });
  await click('Done');
  await page.getByRole('button', { name: 'Large detail view' }).click();
  await settle();
  await page.locator('.phone-stage').screenshot({ path: fileURLToPath(new URL('audio-v5-player-detail.png', output)) });
  await app.locator('.rating-trigger').click();
  await page.locator('.phone-stage').screenshot({ path: fileURLToPath(new URL('audio-v5-rating-detail.png', output)) });
  await click('Done');
  await page.getByRole('button', { name: 'Your size · 47%' }).click();
  await preview('connect');
  await page.locator('.phone-stage').screenshot({ path: fileURLToPath(new URL('audio-v5-connect-47.png', output)) });
  // Same-song before capture at the same 47% calibration for a fair comparison.
  await page.goto(new URL('../mockups/first-install-v4-phone-size.html', import.meta.url).href);
  await ready();
  await page.getByRole('button', { name: 'Your size · 47%' }).click();
  await chooseSpecificSampleSong();
  await page.locator('.phone-stage').screenshot({ path: fileURLToPath(new URL('audio-v5-before-47.png', output)) });
  assert.deepEqual(errors, []);
  assert.deepEqual(external, []);
  await writeFile(new URL('readable-player-verification.json', output), JSON.stringify({ checks, targets, errors, externalRequests:external }, null, 2));
  console.log(`PASS: ${checks.length} readable-player groups; same 47% size, no JavaScript errors or external requests.`);
} finally {
  await browser.close();
}
