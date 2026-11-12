import http from 'node:http';
import { readFile } from 'node:fs/promises';

const routes = new Map([
  ['/auto.html', 'android-auto-v1.html'],
  ['/android-auto-v1.html', 'android-auto-v1.html'],
  ['/', 'first-install-v5-readable.html'],
  ['/index.html', 'first-install-v5-readable.html'],
  ['/first-install-v5-readable.html', 'first-install-v5-readable.html'],
  ['/v4.html', 'first-install-v4-phone-size.html'],
  ['/first-install-v4-phone-size.html', 'first-install-v4-phone-size.html'],
  ['/v3.html', 'first-install-v3.html'],
  ['/first-install-v3.html', 'first-install-v3.html'],
  ['/v2.html', 'playlist-first-v2.html'],
  ['/playlist-first-v2.html', 'playlist-first-v2.html'],
  ['/v1.html', 'index.html'],
]);
const server = http.createServer(async (request, response) => {
  const filename = routes.get(request.url.split('?')[0]);
  if (!filename) {
    response.writeHead(404).end('Not found');
    return;
  }
  try {
    const file = new URL('../mockups/' + filename, import.meta.url);
    const html = await readFile(file);
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
    response.end(html);
  } catch {
    response.writeHead(500).end('Could not read the mockup.');
  }
});
server.listen(4173, '127.0.0.1', () => console.log('Audio mockups: http://127.0.0.1:4173'));
