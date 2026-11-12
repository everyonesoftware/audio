import { readFile, writeFile } from 'node:fs/promises';

const read = path => readFile(new URL(path, import.meta.url), 'utf8');
const previous = await read('../mockups/playlist-first-v2.html');
const baseStyles = previous.match(/<style>([\s\S]*?)<\/style>/)[1];
const assets = previous.slice(previous.indexOf('const icons = {'), previous.indexOf('const icon = name'));
const shell = await read('../mockups/v3/shell.html');
const extraStyles = await read('../mockups/v3/flow.css');
const script = await read('../mockups/v3/flow.js');
const output = shell
  .replace('/* BASE_STYLES */', baseStyles.replaceAll('}', '}\n'))
  .replace('/* FLOW_STYLES */', extraStyles)
  .replace('/* SAMPLE_ASSETS */', assets)
  .replace('/* FLOW_SCRIPT */', script);
if (/\/\* (BASE_STYLES|FLOW_STYLES|SAMPLE_ASSETS|FLOW_SCRIPT) \*\//.test(output)) {
  throw new Error('Unfilled template marker');
}
await writeFile(new URL('../mockups/first-install-v3.html', import.meta.url), output);
console.log('Built self-contained mockups/first-install-v3.html');
