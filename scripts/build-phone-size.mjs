import { readFile, writeFile } from 'node:fs/promises';

const inputFile = process.argv[2] || '../mockups/first-install-v3.html';
const outputFile = process.argv[3] || '../mockups/first-install-v4-phone-size.html';
const version = process.argv[4] || '4';
const source = await readFile(new URL(inputFile, import.meta.url), 'utf8');
const innerStyles = `
html, body { width:100%; height:100%; margin:0; padding:0; overflow:hidden; }
body > .board { width:390px; max-width:none; margin:0; padding:0; }
.mast, .intro, .toolbar, .journey, .explanation, .footer, .device-column > .label { display:none !important; }
.workbench { display:block !important; margin:0; padding:0; }
.device-column { width:390px; }
.phone { width:390px !important; height:875.333333px !important; max-width:none; margin:0; padding:0 !important; border:0; border-radius:38px; box-shadow:none; background:transparent; }
.device { border-radius:38px; }
`;
// The outer review page owns preview URL state; about:srcdoc cannot use the original file URL.
const embedded = source
  .replace('</style>', innerStyles + '\n</style>')
  .replace(/history\.replaceState\(null, '', [^\n;]+\);/g, '/* Preview URL is owned by the outer review page. */');
const outerStyles = `
.calibration { display:flex; flex-wrap:wrap; align-items:center; gap:10px 14px; padding:14px 0; border-bottom:1px solid var(--line); }
.calibration label { font-size:12px; font-weight:600; }
.calibration input { width:200px; max-width:100%; min-height:44px; margin:0; accent-color:#526b47; cursor:ew-resize; }
.calibration output { font-size:12px; min-width:36px; font-variant-numeric:tabular-nums; }
.calibration p { flex-basis:100%; font-size:11px; line-height:1.7; color:var(--muted); margin:0; }
.calibration .board-button[aria-pressed=true] { background:#dce6d5; border-color:#9bad90; }
.phone-viewport { width:100%; overflow:auto; padding:0 4px 12px; }
.phone-stage { position:relative; width:var(--scaled-width,192.7px); height:var(--scaled-height,420.80666651px); margin:0 auto; }
.calibrated-frame { position:absolute; inset:0 auto auto 0; box-sizing:border-box; width:410px; height:895.333333px; padding:9px; border:1px solid #88918f; border-radius:48px; background:#171a19; box-shadow:inset 0 0 0 2px #414745,0 10px 24px #1c2d2320; transform:scale(var(--device-scale,.47)); transform-origin:top left; }
.calibrated-frame::before { content:''; position:absolute; top:115px; right:-3px; width:3px; height:42px; background:#5b6460; border-radius:0 2px 2px 0; }
.calibrated-frame::after { content:''; position:absolute; top:177px; right:-3px; width:3px; height:74px; background:#5b6460; border-radius:0 2px 2px 0; }
.phone-panel { position:relative; width:390px; height:875.333333px; border-radius:38px; overflow:hidden; }
#phone-app { display:block; width:390px; height:875.333333px; border:0; background:#1c211f; }
.size-caption { color:var(--muted); font-size:11px; line-height:1.7; text-align:center; margin:10px 0 0; }
@media(min-width:1151px) { .workbench { grid-template-columns:minmax(180px,1fr) var(--review-phone-width,380px) minmax(220px,1fr); } }
@media(min-width:801px) and (max-width:1150px) { .workbench { grid-template-columns:var(--review-phone-width,380px) minmax(220px,1fr); } }
@media(max-width:800px) { .calibration { gap:8px 12px; } .calibration input { flex:1; min-width:120px; } }
`;
const calibration = `
  <section class="calibration" aria-label="Phone physical-size calibration">
    <label for="phone-scale">Phone size on this monitor</label>
    <input id="phone-scale" type="range" min="25" max="100" step="1" value="47">
    <output id="scale-label" for="phone-scale">47%</output>
    <button class="board-button" id="your-size" aria-pressed="true">Your size · 47%</button>
    <button class="board-button" id="detail-size" aria-pressed="false">Large detail view</button>
    <p>Same Pixel 10 frame and 47% calibration as Services. Match the outer width against your real phone on this monitor. The slider is saved; detail view is temporary. Recalibrate if browser zoom or display scaling changes. Frame and system chrome are illustrative.</p>
  </section>
`;
const frameMarkup = `<div class="phone-viewport"><div class="phone-stage"><div class="calibrated-frame"><div class="phone-panel"><iframe id="phone-app" title="Audio interactive phone prototype"></iframe></div></div></div></div><p class="size-caption">390px logical screen · scaled as a whole, not squeezed</p>`;
let outer = source.slice(0, source.indexOf('<script>'));
outer = outer
  .replace('Audio — first install, libraries & ratings · v3', `Audio — ${version === '5' ? 'readable player & rating popup' : 'your phone size'} · v${version}`)
  .replace('Android / First-install study 003', `Android / ${version === '5' ? 'Readable flow study 005' : 'Calibrated phone study 004'}`)
  .replace('</style>', outerStyles + '\n</style>')
  .replace('  <section class="workbench">', calibration + '  <section class="workbench">')
  .replace('<div class="phone"><section class="device" aria-label="Audio first-install prototype"></section></div>', frameMarkup)
  .replace('v1 and v2 are preserved.', 'v1, v2, and v3 are preserved.');
const appJson = JSON.stringify(embedded).replaceAll('<', '\\u003c');
const wrapperScript = `
'use strict';
(() => {
  const frame = document.querySelector('#phone-app');
  const stage = document.querySelector('.phone-stage');
  const slider = document.querySelector('#phone-scale');
  const scaleLabel = document.querySelector('#scale-label');
  const calibrationKey = 'audio-pixel10-preview-scale-v4';
  let calibration = 47;
  let ready = false;
  try { const saved = Number(localStorage.getItem(calibrationKey)); if (saved >= 25 && saved <= 100) calibration = saved; } catch {}
  function applyScale(percent, detail = false) {
    const scale = percent / 100;
    document.documentElement.style.setProperty('--review-phone-width', Math.max(380, 410 * scale + 8) + 'px');
    stage.style.setProperty('--device-scale', scale);
    stage.style.setProperty('--scaled-width', (410 * scale) + 'px');
    stage.style.setProperty('--scaled-height', (895.333333 * scale) + 'px');
    slider.value = percent;
    scaleLabel.value = percent + '%';
    document.querySelector('#your-size').setAttribute('aria-pressed', !detail && percent === 47);
    document.querySelector('#detail-size').setAttribute('aria-pressed', detail);
  }
  function saveCalibration(percent) {
    calibration = percent;
    try { localStorage.setItem(calibrationKey, percent); } catch {}
    applyScale(percent);
  }
  slider.addEventListener('input', () => saveCalibration(Number(slider.value)));
  document.querySelector('#your-size').addEventListener('click', () => saveCalibration(47));
  document.querySelector('#detail-size').addEventListener('click', () => applyScale(100, true));
  applyScale(calibration);
  function sync() {
    if (!ready) return;
    const doc = frame.contentDocument;
    for (const id of ['screen-number', 'screen-label', 'explain-title', 'explain-copy', 'explain-decision', 'explain-try', 'storage-note']) {
      document.getElementById(id).textContent = doc.getElementById(id).textContent;
    }
    document.querySelector('#preview-screen').value = doc.querySelector('#preview-screen').value;
  }
  function changeScreen(value) {
    if (!ready) return;
    const input = frame.contentDocument.querySelector('#preview-screen');
    input.value = value;
    input.dispatchEvent(new frame.contentWindow.Event('change', { bubbles:true }));
    history.replaceState(null, '', '#' + value);
    sync();
  }
  for (const id of ['preview-screen', 'preview-outcome', 'reopen', 'reset']) document.getElementById(id).disabled = true;
  frame.addEventListener('load', () => {
    const doc = frame.contentDocument;
    if (!doc.querySelector('.device')) return;
    ready = true;
    for (const id of ['preview-screen', 'preview-outcome', 'reopen', 'reset']) document.getElementById(id).disabled = false;
    new MutationObserver(sync).observe(doc.querySelector('.device'), { attributes:true, childList:true, subtree:true, characterData:true });
    const requested = location.hash.slice(1);
    if ([...document.querySelector('#preview-screen').options].some(o => o.value === requested)) changeScreen(requested);
    else sync();
  });
  document.querySelector('#preview-screen').addEventListener('change', e => changeScreen(e.target.value));
  document.querySelector('#preview-outcome').addEventListener('change', e => {
    if (!ready) return;
    const input = frame.contentDocument.querySelector('#preview-outcome');
    input.value = e.target.value;
    input.dispatchEvent(new frame.contentWindow.Event('change', { bubbles:true }));
  });
  for (const id of ['reopen', 'reset']) document.getElementById(id).addEventListener('click', () => {
    if (!ready) return;
    frame.contentDocument.getElementById(id).click();
    history.replaceState(null, '', location.pathname);
    if (id === 'reset') document.querySelector('#preview-outcome').value = 'success';
    sync();
  });
  window.addEventListener('hashchange', () => {
    const value = location.hash.slice(1);
    if ([...document.querySelector('#preview-screen').options].some(o => o.value === value)) changeScreen(value);
  });
  frame.srcdoc = ${appJson};
})();
`;
const html = outer + '<script>\n' + wrapperScript + '\n</script>\n</body>\n</html>\n';
await writeFile(new URL(outputFile, import.meta.url), html);
console.log(`Built calibrated v${version} at Services 47% size; earlier versions preserved.`);
