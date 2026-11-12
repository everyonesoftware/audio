import { chromium } from 'playwright';
import { mkdir, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const edge = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const browser = await chromium.launch(existsSync(edge) ? { executablePath: edge } : {});
const context = await browser.newContext({ viewport: { width: 1440, height: 1230 } });
let page = await context.newPage();
const source = new URL('../mockups/playlist-first-v2.html', import.meta.url).href;
const out = new URL('../test-results/', import.meta.url);
await mkdir(out, { recursive: true });
const errors = [], requests = [], checks = [];
context.on('page', p => p.on('pageerror', e => errors.push(e.message)));
page.on('pageerror', e => errors.push(e.message));
context.on('request', r => { if (/^https?:/.test(r.url())) requests.push(r.url()); });
const device = id => page.locator(`[data-device="${id}"]`);
const saved = id => page.evaluate(id => JSON.parse(localStorage.getItem('audio-playlist-workflow-v2-' + id)), id);
const settle = () => page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));
async function choose(id) {
  await page.getByLabel('Preview layout').selectOption(id);
  await settle();
}
async function reset() {
  await page.getByRole('button', { name: 'Reset demo', exact: true }).click();
  await settle();
}
async function geometry(id, player = false) {
  const g = await device(id).evaluate(d => {
    const screen = d.querySelector('.screen'), bounds = screen.getBoundingClientRect();
    const final = d.querySelector('.secondary-row');
    return {
      pageOverflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
      contentOverflow: screen.scrollWidth > screen.clientWidth,
      clippedPlayer: !!final && final.getBoundingClientRect().bottom > bounds.bottom,
      imagesBroken: [...d.querySelectorAll('img')].some(i => !i.complete || !i.naturalWidth),
      narrowTargets: [...d.querySelectorAll('button, input')].filter(b => b.getBoundingClientRect().height < 44).length
    };
  });
  assert.equal(g.pageOverflow, false);
  assert.equal(g.contentOverflow, false, JSON.stringify(g));
  if (player) assert.equal(g.clippedPlayer, false, JSON.stringify(g));
  assert.equal(g.imagesBroken, false);
  assert.equal(g.narrowTargets, 0, JSON.stringify(g));
}
try {
  await page.goto(source);
  await reset();
  await page.screenshot({ path: fileURLToPath(new URL('audio-workflow-v2-desktop.png', out)), fullPage: true });
  for (const width of [1440, 1024, 390, 320]) {
    await page.setViewportSize({ width, height: 1230 });
    for (const id of ['returning', 'switching', 'first']) {
      await choose(id);
      await reset();
      const d = device(id);
      await geometry(id, id === 'returning');
      if (id === 'returning') {
        assert.equal(await d.getByRole('button', { name: 'Resume playback', exact: true }).count(), 1);
        assert.equal(await d.locator('.elapsed').innerText(), '1:21');
        await d.getByRole('button', { name: 'Resume playback', exact: true }).click();
        const before = await saved(id);
        await page.getByRole('button', { name: 'Reopen app', exact: true }).click();
        assert.deepEqual((await saved(id)).session, before.session);
        assert.equal(await d.getByRole('button', { name: 'Resume playback', exact: true }).count(), 1);
        await d.locator('.change-playlist').click();
      }
      if (id === 'first') assert.doesNotMatch(await d.innerText(), /Recently played/);
      await d.getByRole('searchbox', { name: 'Search playlists' }).fill('no match for this');
      assert.match(await d.innerText(), /No playlists match/);
      await d.getByRole('searchbox').fill('Daily rotation');
      await d.getByRole('button', { name: 'Shuffle and play Daily rotation', exact: true }).click();
      assert.equal(await d.getAttribute('data-screen'), 'player');
      assert.equal(await d.getByRole('button', { name: 'Pause', exact: true }).count(), 1);
      assert.equal(await d.getByRole('button', { name: 'Shuffle', exact: true }).getAttribute('aria-pressed'), 'true');
      await geometry(id, true);
      const newSession = (await saved(id)).session;
      assert.equal(newSession.playlist, 'daily');
      assert.equal(newSession.order.length, 8);
      assert.equal(new Set(newSession.order).size, 8);
      await d.locator('.seek').fill('142');
      await d.getByRole('button', { name: 'Next track' }).click();
      await d.locator('.seek').fill('72');
      const last = (await saved(id)).session;
      await page.getByRole('button', { name: 'Reopen app', exact: true }).click();
      assert.equal(await d.locator('.elapsed').innerText(), '1:12');
      assert.deepEqual((await saved(id)).session, last);
      await d.locator('.change-playlist').click();
      await d.getByRole('button', { name: 'Back to player', exact: true }).click();
      assert.deepEqual((await saved(id)).session, last);
      await d.getByRole('button', { name: 'Queue', exact: true }).click();
      assert.equal(await d.locator('.song-row').count(), 8);
      await d.getByRole('button', { name: 'Back to player', exact: true }).click();
      await d.locator('.change-playlist').click();
      await d.getByRole('button', { name: 'View A quieter kind of evening', exact: true }).click();
      assert.equal(await d.getAttribute('data-screen'), 'details');
      assert.equal((await saved(id)).session.playlist, 'daily', 'Inspecting must not start playback');
      await d.getByRole('button', { name: 'Back to playlists', exact: true }).click();
      await d.getByRole('button', { name: 'Browse library', exact: true }).click();
      await geometry(id);
      await d.getByRole('button', { name: 'Back to playlists', exact: true }).click();
      await page.keyboard.press('Escape');
      assert.equal(await d.getAttribute('data-screen'), 'player');
      checks.push(`${width}px ${id}: geometry, resume, direct playlist shuffle, inspect without playback, queue, search/empty, secondary library, Escape`);
    }
    await choose('all');
    await reset();
    for (const id of ['returning', 'switching', 'first']) await geometry(id, id === 'returning');
    checks.push(`${width}px: all three workflow frames fit independently`);
  }
  // Cold reload and a completely new tab prove saved session restoration.
  await choose('returning');
  await reset();
  await device('returning').locator('.change-playlist').click();
  await device('returning').getByRole('button', { name: 'Shuffle and play Daily rotation' }).click();
  await device('returning').getByRole('button', { name: 'Next track' }).click();
  await device('returning').locator('.seek').fill('163');
  await device('returning').getByRole('button', { name: 'Shuffle', exact: true }).click();
  const previous = await saved('returning');
  await page.reload();
  await settle();
  assert.deepEqual(await saved('returning'), previous);
  assert.equal(await device('returning').getAttribute('data-screen'), 'player');
  assert.equal(await device('returning').locator('.elapsed').innerText(), '2:43');
  assert.equal(await device('returning').getByRole('button', { name: 'Shuffle', exact: true }).getAttribute('aria-pressed'), 'false');
  assert.equal(await device('returning').getByRole('button', { name: 'Resume playback', exact: true }).count(), 1);
  await page.close();
  page = await context.newPage();
  await page.goto(source + '#returning');
  await settle();
  assert.equal(await device('returning').locator('.elapsed').innerText(), '2:43');
  assert.equal(await device('returning').getAttribute('data-screen'), 'player');
  assert.equal(await device('returning').getByRole('button', { name: 'Resume playback', exact: true }).count(), 1);
  checks.push('Cold reload and new tab: exact playlist, track, position, queue, shuffle restored; playback paused');
  // Current-playlist Resume never regenerates the queue.
  const current = await saved('returning');
  await device('returning').locator('.change-playlist').click();
  await device('returning').getByRole('searchbox').fill('Daily');
  await device('returning').getByRole('button', { name: 'Resume Daily rotation', exact: true }).click();
  assert.deepEqual((await saved('returning')).session, current.session);
  checks.push('Current playlist one-tap Resume preserves queue and position');
  // First-use history becomes an ordinary returning session.
  await choose('first');
  await device('first').getByRole('button', { name: 'Shuffle and play Deep focus', exact: true }).click();
  await page.reload();
  await settle();
  assert.equal(await device('first').getAttribute('data-screen'), 'player');
  assert.equal((await saved('first')).session.playlist, 'focus');
  checks.push('First listen becomes a restored full-player session after browser reload');
  // Simulated server failure keeps the session and cannot claim it is playing.
  await choose('returning');
  const beforeFailure = await saved('returning');
  await page.evaluate(() => window.audioDemo.unavailable());
  assert.equal(await device('returning').getByRole('button', { name: 'Resume playback', exact: true }).isDisabled(), true);
  await device('returning').getByRole('button', { name: 'Retry playlist', exact: true }).click();
  assert.deepEqual(await saved('returning'), beforeFailure);
  checks.push('Unavailable playlist: resume disabled, retry keeps exact session');
  // Persisted state is isolated between frames.
  const firstSession = await saved('first');
  await device('returning').getByRole('button', { name: 'Next track' }).click();
  assert.deepEqual(await saved('first'), firstSession);
  checks.push('Independent scenario state');
  // Final captures, reset to intentionally chosen workflow entry points.
  await page.setViewportSize({ width: 1440, height: 1230 });
  await choose('all');
  await reset();
  await page.screenshot({ path: fileURLToPath(new URL('audio-workflow-v2-desktop.png', out)), fullPage: true });
  await page.setViewportSize({ width: 390, height: 1150 });
  await choose('returning');
  await page.locator('[data-study="returning"] .phone').screenshot({ path: fileURLToPath(new URL('audio-workflow-v2-phone.png', out)) });
  await device('returning').locator('.change-playlist').click();
  await page.locator('[data-study="returning"] .phone').screenshot({ path: fileURLToPath(new URL('audio-workflow-v2-switch-phone.png', out)) });
  assert.deepEqual(errors, []);
  assert.deepEqual(requests, []);
  await writeFile(new URL('playlist-workflow-verification.json', out), JSON.stringify({ passed: checks.length, checks, errors, externalRequests: requests }, null, 2));
  console.log(`PASS: ${checks.length} workflow groups; no JavaScript errors or external requests.`);
} finally {
  await browser.close();
}
