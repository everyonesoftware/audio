import { chromium } from 'playwright';
import { mkdir, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const edge = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const browser = await chromium.launch(existsSync(edge) ? { executablePath: edge } : {});
const context = await browser.newContext({ viewport: { width: 1440, height: 1250 } });
const page = await context.newPage();
const source = new URL('../mockups/first-install-v3.html', import.meta.url).href;
const output = new URL('../test-results/', import.meta.url);
await mkdir(output, { recursive: true });
const errors = [], external = [], checks = [];
page.on('pageerror', e => errors.push(e.message));
context.on('request', r => { if (/^https?:/.test(r.url())) external.push(r.url()); });
const d = page.locator('.device');
const click = name => d.getByRole('button', { name, exact: true }).click();
const model = () => page.evaluate(() => JSON.parse(localStorage.getItem('audio-first-install-v3')));
const settle = () => page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));
async function waitScreen(screen) { await page.waitForFunction(s => document.querySelector('.device').dataset.screen === s, screen); await settle(); }
async function preview(screen) { await page.getByLabel('Preview screen', { exact: true }).selectOption(screen); await waitScreen(screen); }
async function outcome(value) { await page.getByLabel('Simulated server outcome').selectOption(value); }
async function reset() { await page.getByRole('button', { name: 'Restart first-install demo' }).click(); await waitScreen('welcome'); }
async function fillConnection({ label = 'My home server', url = 'https://music.example.com/navidrome', user = 'demo-listener' } = {}) {
  await d.getByLabel('Connection name', { exact: true }).fill(label);
  await d.getByLabel('Server address', { exact: true }).fill(url);
  await d.getByLabel('Username', { exact: true }).fill(user);
  await d.getByLabel('Password', { exact: true }).fill('dummy-password-do-not-save');
}
async function connect(config = {}) {
  await fillConnection(config);
  await click('Connect & discover libraries');
  await waitScreen('libraries');
}
async function geometry() {
  await settle();
  const data = await d.evaluate(device => {
    const s = device.querySelector('.screen');
    const footer = device.querySelector('.secondary-row');
    return {
      outerOverflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
      innerOverflow: s.scrollWidth > s.clientWidth,
      footerClipped: !!footer && footer.getBoundingClientRect().bottom > s.getBoundingClientRect().bottom,
      brokenImages: [...device.querySelectorAll('img')].filter(i => !i.complete || !i.naturalWidth).length,
      shortButtons: [...device.querySelectorAll('button')].filter(b => b.getBoundingClientRect().height < 44).map(b => b.textContent),
    };
  });
  assert.deepEqual(data, { outerOverflow: false, innerOverflow: false, footerClipped: false, brokenImages: 0, shortButtons: [] }, JSON.stringify(data));
  assert.equal(await d.locator('[data-action="like"]').count(), 0);
}
try {
  await page.goto(source);
  await reset();
  assert.equal(await d.getByRole('button', { name: 'Connect to Navidrome' }).count(), 1);
  assert.equal((await model()).connections.length, 0);
  // Exercise the real first-install path at desktop and both phone widths.
  for (const width of [1440, 390, 320]) {
    await page.setViewportSize({ width, height: 1250 });
    await reset();
    await geometry();
    await click('Connect to Navidrome');
    await fillConnection({ url: 'http://music.example.com' });
    await click('Connect & discover libraries');
    assert.match(await d.locator('#url-error').innerText(), /HTTPS/);
    await d.getByLabel('Server address', { exact: true }).fill('https://music.example.com/navidrome');
    await click('Show password');
    assert.equal(await d.locator('#server-password').getAttribute('type'), 'text');
    await click('Hide password');
    await click('Connect & discover libraries');
    assert.equal(await d.locator('#server-password').inputValue(), '');
    await waitScreen('libraries');
    for (const input of await d.locator('.library-choice input').all()) await input.uncheck();
    assert.equal(await d.getByRole('button', { name: 'Continue', exact: true }).isDisabled(), true);
    await d.getByRole('checkbox', { name: /Music Your main/ }).check();
    await geometry();
    await click('Continue');
    await waitScreen('ready');
    await click('Choose a playlist');
    await waitScreen('playlists');
    assert.doesNotMatch(await d.innerText(), /Recently played/);
    await d.getByRole('searchbox').fill('not-a-playlist');
    assert.match(await d.innerText(), /No playlists match/);
    await d.getByRole('searchbox').fill('Daily rotation');
    await click('Shuffle and play Daily rotation');
    await waitScreen('player');
    await geometry();
    await d.getByRole('radio', { name: '4 stars', exact: true }).click();
    await d.getByRole('status').filter({ hasText: 'Saved to Navidrome' }).waitFor();
    assert.equal(await d.getByRole('radio', { name: '4 stars' }).getAttribute('aria-checked'), 'true');
    await d.locator('.seek').fill('137');
    const before = await model();
    await page.getByRole('button', { name: 'Reopen app', exact: true }).click();
    await waitScreen('player');
    assert.equal(await d.locator('.elapsed').innerText(), '2:17');
    assert.deepEqual(await model(), before);
    assert.equal(await d.getByRole('button', { name: 'Resume playback', exact: true }).count(), 1);
    assert.ok(!JSON.stringify(await model()).includes('dummy-password'));
    checks.push(`${width}px: full first-install path, validation, password visibility/clearing, libraries, first playlist, full player, rating, resume`);
  }
  // Every review shortcut must render and fit at wide, intermediate, and phone sizes.
  for (const width of [1440, 1024, 390, 320]) {
    await page.setViewportSize({ width, height: 1250 });
    for (const screen of ['welcome', 'connect', 'libraries', 'ready', 'playlists', 'player', 'settings', 'queue']) {
      await preview(screen);
      await geometry();
      checks.push(`${width}px: ${screen} shortcut and geometry`);
    }
  }
  // Failure and retry: don't create a successful connection on a failed sign-in.
  await reset();
  await click('Connect to Navidrome');
  await outcome('auth');
  await fillConnection();
  await click('Connect & discover libraries');
  await d.locator('#auth-error').filter({ hasText: 'Sign-in failed' }).waitFor();
  assert.equal((await model()).connections.length, 0);
  assert.equal(await d.locator('#server-password').inputValue(), '');
  assert.equal(await d.locator('.connected-summary').count(), 0);
  await outcome('network');
  await fillConnection();
  await click('Connect & discover libraries');
  await d.locator('#auth-error').filter({ hasText: 'Server unreachable' }).waitFor();
  assert.equal((await model()).connections.length, 0);
  await outcome('no-libraries');
  await connect();
  assert.match(await d.innerText(), /No libraries available/);
  assert.equal(await d.getByRole('button', { name: 'Continue', exact: true }).isDisabled(), true);
  await outcome('success');
  await click('Refresh access');
  assert.equal(await d.getByRole('checkbox').count(), 3);
  await click('Continue');
  await click('Choose a playlist');
  await waitScreen('playlists');
  await click('Shuffle and play The long way home');
  checks.push('Wrong password, unreachable server, no-access state, refresh and recovery');
  // Library edits have save/cancel semantics and preserve the listening queue.
  await click('Server and libraries');
  const session = (await model()).connections[0].session;
  const selection = (await model()).connections[0].selected;
  await click('Choose libraries');
  await d.getByRole('checkbox', { name: /Audiobooks/ }).uncheck();
  await click('Cancel');
  assert.deepEqual((await model()).connections[0].selected, selection);
  await click('Choose libraries');
  await d.getByRole('checkbox', { name: /Audiobooks/ }).uncheck();
  await click('Save library selection');
  assert.ok(!(await model()).connections[0].selected.includes('books'));
  assert.deepEqual((await model()).connections[0].session, session);
  await outcome('offline');
  await click('Refresh list');
  await d.getByRole('status').filter({ hasText: 'Refresh failed' }).waitFor();
  assert.deepEqual((await model()).connections[0].session, session);
  await outcome('success');
  await click('Open Navidrome administration');
  assert.match(await d.innerText(), /Settings → Libraries/);
  assert.match(await d.innerText(), /Settings → Users/);
  assert.match(await d.innerText(), /No browser is opened/);
  await click('Back to server settings');
  checks.push('Library cancel/save, queue preservation, refresh failure, server-admin handoff');
  // Connection identity and rating state stay independent for two servers/accounts.
  await click('Add another server');
  await connect({ label: 'Archive server', url: 'https://archive.example.com', user: 'demo-archive' });
  await click('Continue');
  await click('Choose a playlist');
  await waitScreen('playlists');
  await click('Shuffle and play Daily rotation');
  await d.getByRole('radio', { name: '2 stars' }).click();
  await d.getByRole('status').filter({ hasText: 'Saved to Navidrome' }).waitFor();
  const archiveModel = await model();
  const archiveId = archiveModel.active;
  await click('Server and libraries');
  await click('Switch');
  await waitScreen('player');
  assert.notEqual((await model()).active, archiveId);
  assert.deepEqual((await model()).connections[0].session, session);
  assert.equal(await d.getByRole('radio', { name: '2 stars' }).getAttribute('aria-checked'), 'false');
  checks.push('Two-server connection switching isolates listening sessions and ratings');
  // Ratings: success, clear, failure, offline, retry, per-song, and keyboard.
  await outcome('success');
  await d.getByRole('radio', { name: '3 stars' }).click();
  await d.getByRole('status').filter({ hasText: 'Saved to Navidrome' }).waitFor();
  await outcome('rating-error');
  await d.getByRole('radio', { name: '5 stars' }).click();
  assert.equal(await d.getByRole('radio', { name: '5 stars' }).isDisabled(), true);
  await d.getByRole('status').filter({ hasText: 'Save failed' }).waitFor();
  assert.equal(await d.getByRole('radio', { name: '3 stars' }).getAttribute('aria-checked'), 'true');
  await outcome('offline');
  await click('Retry');
  await d.getByRole('status').filter({ hasText: 'offline' }).waitFor();
  assert.equal(await d.getByRole('radio', { name: '3 stars' }).getAttribute('aria-checked'), 'true');
  await outcome('success');
  await click('Retry');
  await d.getByRole('status').filter({ hasText: 'Saved to Navidrome' }).waitFor();
  assert.equal(await d.getByRole('radio', { name: '5 stars' }).getAttribute('aria-checked'), 'true');
  await click('Clear');
  await d.getByRole('status').filter({ hasText: 'Saved to Navidrome' }).waitFor();
  assert.equal(await d.locator('[role="radio"][aria-checked="true"]').count(), 0);
  await d.getByRole('radio', { name: '1 star', exact: true }).focus();
  await page.keyboard.press('End');
  await d.getByRole('status').filter({ hasText: 'Saved to Navidrome' }).waitFor();
  assert.equal(await d.getByRole('radio', { name: '5 stars' }).getAttribute('aria-checked'), 'true');
  const trackTitle = await d.locator('.song-heading h3').innerText();
  await click('Next track');
  assert.notEqual(await d.locator('.song-heading h3').innerText(), trackTitle);
  assert.equal(await d.getByRole('radio', { name: '5 stars' }).getAttribute('aria-checked'), 'false');
  await click('Previous track');
  assert.equal(await d.getByRole('radio', { name: '5 stars' }).getAttribute('aria-checked'), 'true');
  // In-flight rating is bound to original track even if navigation changes immediately.
  await d.getByRole('radio', { name: '4 stars' }).click();
  await click('Next track');
  await page.waitForTimeout(550);
  assert.equal(await d.getByRole('radio', { name: '4 stars' }).getAttribute('aria-checked'), 'false');
  await click('Previous track');
  assert.equal(await d.getByRole('radio', { name: '4 stars' }).getAttribute('aria-checked'), 'true');
  checks.push('Rating 1–5, clear, pending, rollback, offline/retry, keyboard, song identity, in-flight navigation');
  // Browser reload returns to saved player, not to onboarding, without autostart.
  await d.locator('.seek').fill('161');
  await page.getByRole('button', { name: 'Reopen app', exact: true }).click();
  const beforeReload = await model();
  await page.reload();
  await waitScreen('player');
  assert.deepEqual(await model(), beforeReload);
  assert.equal(await d.locator('.elapsed').innerText(), '2:41');
  checks.push('Cold browser reload restores saved player, queue, position, rating and account');
  // Remove confirmation cancellation, Escape/focus, local-only scope.
  await click('Server and libraries');
  await click('Remove connection');
  await waitScreen('remove');
  assert.match(await d.innerText(), /does not delete music, playlists, accounts, or ratings/);
  assert.equal(await d.getByRole('button', { name: 'Cancel', exact: true }).evaluate(el => el === document.activeElement), true);
  await page.keyboard.press('Escape');
  assert.equal((await model()).connections.length, 2);
  assert.equal(await d.getByRole('button', { name: 'Remove connection', exact: true }).evaluate(el => el === document.activeElement), true);
  await click('Remove connection');
  await click('Remove connection from this device');
  assert.equal((await model()).connections.length, 1);
  assert.deepEqual((await model()).ratings, beforeReload.ratings);
  checks.push('Named removal confirmation, safe focus, Escape cancel, local-only removal');
  // Review captures start clean and use the same fixed palette.
  await reset();
  await page.setViewportSize({ width: 1440, height: 1250 });
  await page.screenshot({ path: fileURLToPath(new URL('audio-v3-desktop.png', output)), fullPage: true });
  await page.setViewportSize({ width: 390, height: 1250 });
  for (const screen of ['welcome', 'connect', 'libraries', 'playlists', 'player', 'settings']) {
    if (screen !== 'welcome') await preview(screen);
    if (screen === 'connect') await fillConnection();
    await settle();
    await page.locator('.phone').screenshot({ path: fileURLToPath(new URL(`audio-v3-phone-${screen}.png`, output)) });
  }
  assert.deepEqual(errors, []);
  assert.deepEqual(external, []);
  await writeFile(new URL('first-install-verification.json', output), JSON.stringify({ passed: checks.length, checks, errors, externalRequests: external }, null, 2));
  console.log(`PASS: ${checks.length} onboarding/library/rating groups; no JavaScript errors; no external requests.`);
} finally {
  await browser.close();
}
