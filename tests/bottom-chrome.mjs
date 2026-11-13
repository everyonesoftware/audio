import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
const edge = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const browser = await chromium.launch(existsSync(edge) ? { executablePath: edge } : {});
const page = await browser.newPage({ viewport: { width: 1240, height: 1200 }, deviceScaleFactor: 1 });
const errors = [];
page.on('pageerror', e => errors.push(e.message));
const output = new URL('../test-results/', import.meta.url);
await mkdir(output, { recursive: true });
const checks = [];
try {
  await page.goto(new URL('../mockups/bottom-chrome-options.html', import.meta.url).href);
  await page.evaluate(() => { for (const s of document.querySelectorAll('script')) new Function(s.textContent); });
  for (const width of [1240, 390, 320]) {
    await page.setViewportSize({ width, height: 1200 });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    for (const option of ['divider', 'tonal', 'floating']) {
      await page.locator(`button[data-option="${option}"]`).click();
      assert.equal(await page.locator(`button[data-option="${option}"]`).getAttribute('aria-pressed'), 'true');
      assert.equal(await page.locator(`.desktop.${option}`).count(), 1);
      for (const selector of [`.phone.${option}`, '.desktop']) {
        const frame = page.locator(selector);
        const geometry = await frame.evaluate(el => {
          const d = el.getBoundingClientRect();
          const n = el.querySelector('.tabs').getBoundingClientRect();
          return { left: Math.abs(n.left - d.left - 1), right: Math.abs(d.right - 1 - n.right), broken: [...el.querySelectorAll('img')].filter(i => !i.complete || !i.naturalWidth).length, overflow: el.scrollWidth > el.clientWidth };
        });
        assert.ok(geometry.left < 1 && geometry.right < 1, JSON.stringify(geometry));
        assert.equal(geometry.broken, 0);
        assert.equal(geometry.overflow, false);
        const button = frame.locator('.play');
        await button.click();
        assert.equal(await button.getAttribute('aria-label'), 'Preview pause');
        await button.click();
        assert.equal(await button.getAttribute('aria-label'), 'Preview play');
        checks.push(`${width}/${option}/${selector}: full-width tabs, loaded artwork, no overflow, play/pause`);
      }
    }
  }
  await page.setViewportSize({ width: 1240, height: 1200 });
  await page.locator('button[data-option="tonal"]').click();
  await page.screenshot({ path: fileURLToPath(new URL('bottom-chrome-comparison.png', output)), fullPage: true });
  await page.locator('.desktop-section').screenshot({ path: fileURLToPath(new URL('bottom-chrome-desktop.png', output)) });
  await page.locator('.phones').screenshot({ path: fileURLToPath(new URL('bottom-chrome-phones.png', output)) });
  await page.setViewportSize({ width: 390, height: 1200 });
  await page.locator('.phone.tonal').screenshot({ path: fileURLToPath(new URL('bottom-chrome-phone-tonal.png', output)) });
  assert.deepEqual(errors, []);
  await writeFile(new URL('bottom-chrome-verification.json', output), JSON.stringify({ checks, errors }, null, 2));
  console.log(`PASS: ${checks.length} device/viewport checks; desktop selector; inline JS syntax; no browser errors.`);
} finally { await browser.close(); }
