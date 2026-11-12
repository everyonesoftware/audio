import { readFile, writeFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const source = await readFile(new URL('../mockups/first-install-v3.html', import.meta.url), 'utf8');
const css = await readFile(new URL('../mockups/v5/readable.css', import.meta.url), 'utf8');
const js = await readFile(new URL('../mockups/v5/interaction.js', import.meta.url), 'utf8');
const marker = 'const initialPreview = location.hash.slice(1);';
if (source.split(marker).length !== 2) throw new Error('Expected exactly one app initialization marker');
const readable = source
  .replace('</style>', css + '\n</style>')
  .replace("const storeKey = 'audio-first-install-v3';", "const storeKey = 'audio-first-install-v5';")
  .replace(marker, js + '\n' + marker);
await writeFile(new URL('../mockups/first-install-v5-inner.html', import.meta.url), readable);
const result = spawnSync(process.execPath, [
  fileURLToPath(new URL('./build-phone-size.mjs', import.meta.url)),
  '../mockups/first-install-v5-inner.html',
  '../mockups/first-install-v5-readable.html',
  '5',
], { stdio: 'inherit' });
if (result.status !== 0) throw new Error('Could not build calibrated v5 wrapper');
