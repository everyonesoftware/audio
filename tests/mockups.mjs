import { chromium } from 'playwright';
import { mkdir, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const edge = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const browser = await chromium.launch(existsSync(edge) ? { executablePath: edge } : {});
const page = await browser.newPage({ viewport: { width: 1440, height: 1240 }, deviceScaleFactor: 1 });
const source = new URL('../mockups/index.html', import.meta.url).href;
const output = new URL('../test-results/', import.meta.url);
await mkdir(output, { recursive: true });
const errors = [];
const externalRequests = [];
page.on('pageerror', e => errors.push(e.message));
page.on('request', request => {
  if (/^https?:/.test(request.url())) externalRequests.push(request.url());
});
const directions = ['evergreen', 'sleeve', 'nightshift'];
const screens = ['library', 'album', 'player', 'queue', 'offline', 'server'];
const checks = [];
async function load(hash) {
  await page.goto(source + '#' + hash);
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}
try {
  for (const width of [1440, 390, 320]) {
    await page.setViewportSize({ width, height: 1240 });
    for (const direction of directions) {
      for (const screen of screens) {
        await load(direction + '/' + screen);
        const geometry = await page.evaluate((direction) => {
          const doc = document.documentElement;
          const device = document.querySelector('.device.' + direction);
          const content = device.querySelector('.app-content');
          return {
            outerOverflow: doc.scrollWidth > doc.clientWidth,
            innerOverflow: content.scrollWidth > content.clientWidth,
            brokenImages: [...device.querySelectorAll('img')].filter(i => !i.complete || i.naturalWidth === 0).length,
            playerFooterClipped: !!device.querySelector('.player-footer') && device.querySelector('.player-footer').getBoundingClientRect().bottom > content.getBoundingClientRect().bottom,

            controlsOutside: [...device.querySelectorAll('.playback button, .bottom-nav button')].filter(b => {
              const r = b.getBoundingClientRect(), d = device.getBoundingClientRect();
              return r.right > d.right + 1 || r.left < d.left - 1;
            }).length
          };
        }, direction);
        assert.deepEqual(geometry, { outerOverflow: false, innerOverflow: false, brokenImages: 0, playerFooterClipped: false, controlsOutside: 0 }, `${direction}/${screen} at ${width}: ${JSON.stringify(geometry)}`);
        checks.push(`${width}: ${direction}/${screen} geometry and artwork`);
      }
    }
  }
  await page.setViewportSize({ width: 1440, height: 1240 });
  await load('all/library');
  await page.screenshot({ path: fileURLToPath(new URL('audio-library-comparison.png', output)), fullPage: true });
  await load('all/player');
  await page.screenshot({ path: fileURLToPath(new URL('audio-player-comparison.png', output)), fullPage: true });
  for (const direction of directions) {
    const device = page.locator('.device.' + direction);
    await load(direction + '/library');
    const search = device.getByRole('searchbox');
    await search.fill('no-such-album');
    assert.match(await device.innerText(), /No music found/);
    await search.fill('Blue Hours');
    await device.getByRole('button', { name: 'Open Blue Hours by June Marlow' }).click();
    assert.equal(await device.locator('.detail-title').innerText(), 'Blue Hours');
    await device.getByRole('button', { name: 'Play album', exact: true }).click();
    assert.equal(await device.locator('.player-title').innerText(), 'Last train home');
    await device.getByRole('button', { name: 'Next track', exact: true }).click();
    assert.equal(await device.locator('.player-title').innerText(), 'Blue hours');
    await device.getByRole('button', { name: 'Pause', exact: true }).click();
    assert.equal(await device.getByRole('button', { name: 'Play', exact: true }).count(), 1);
    await device.getByRole('button', { name: 'Add to favorites' }).click();
    assert.equal(await device.getByRole('button', { name: 'Remove from favorites' }).getAttribute('aria-pressed'), 'true');
    await device.locator('.seek').fill('70');
    assert.equal(await device.locator('.elapsed').innerText(), '3:01');
    await device.getByRole('button', { name: 'Up next', exact: true }).click();
    assert.equal(await device.locator('.track').count(), 6);
    await device.getByRole('button', { name: 'Play Room with a view' }).click();
    await device.getByRole('button', { name: 'Open now playing' }).click();
    assert.equal(await device.locator('.player-title').innerText(), 'Room with a view');
    await device.getByRole('button', { name: 'Close player' }).click();
    await device.getByRole('button', { name: 'Offline', exact: true }).click();
    await device.getByRole('button', { name: 'Pause downloads' }).click();
    assert.equal(await device.getByRole('button', { name: 'Resume downloads' }).count(), 1);
    await device.getByRole('button', { name: 'Server', exact: true }).click();
    await device.getByRole('button', { name: 'Preview connection' }).click();
    await device.locator('.form-result').filter({ hasText: 'Simulated success' }).waitFor();
    await device.getByLabel('Preview outcome').selectOption('error');
    await device.getByRole('button', { name: 'Preview connection' }).click();
    await device.locator('.form-result.error').waitFor();
    assert.match(await device.locator('.form-result').innerText(), /Simulated failure/);
    assert.doesNotMatch(await device.locator('.form-result').innerText(), /success/);
    checks.push(`${direction}: search, album, playback, favorites, seeking, queue, offline, connection success/failure`);
  }
  await page.setViewportSize({ width: 390, height: 1240 });
  await load('sleeve/player');
  await page.screenshot({ path: fileURLToPath(new URL('audio-sleeve-phone.png', output)), fullPage: true });
  assert.deepEqual(errors, [], 'No JavaScript errors');
  assert.deepEqual(externalRequests, [], 'Portable preview must not send requests');
  await writeFile(new URL('verification.json', output), JSON.stringify({ passed: checks.length, checks, errors, externalRequests }, null, 2));
  console.log(`PASS: ${checks.length} checks, no JavaScript errors, no external requests.`);
  console.log('Screenshots: test-results/audio-library-comparison.png, audio-player-comparison.png, audio-sleeve-phone.png');
} finally {
  await browser.close();
}
