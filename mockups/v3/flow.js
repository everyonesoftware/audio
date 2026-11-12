icons.star = '<path d="m12 3 2.8 5.7 6.3.9-4.5 4.4 1 6.2-5.6-3-5.6 3 1-6.2L1.9 9.6l6.3-.9Z"/>';
icons.eye = '<path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/>';
delete icons.heart;
const icon = name => svg(name);
const device = document.querySelector('.device');
const storeKey = 'audio-first-install-v3';
const librarySamples = [
  { id: 'music', name: 'Music', detail: 'Your main music collection' },
  { id: 'lossless', name: 'Lossless', detail: 'High-quality albums and recordings' },
  { id: 'books', name: 'Audiobooks', detail: 'Spoken-word collection' },
];
const descriptions = {
  welcome: ['01', 'Fresh install', 'An obvious place to start.', 'No empty library and no unexplained player. The first screen says what the app needs and gives one primary action: connect your server.', 'There is no separate Audio account. Navidrome remains the source of your music, playlists, and personal ratings.', 'Tap Connect to Navidrome, then use the dummy values in the connection form.'],
  connect: ['02', 'Connect to Navidrome', 'Use the account you already have.', 'Enter the server’s base address, your Navidrome username, and password. Testing the connection also discovers the libraries that account can access.', 'This is a simulated username/password flow, not a made-up OAuth provider. No password is stored in this prototype.', 'Fill demo values. The Preview outcome menu lets you try an incorrect password or an unreachable server.'],
  libraries: ['03', 'Choose libraries', 'Choose your browsing scope.', 'These are libraries returned for the signed-in account. Select which to browse here; this does not create folders, grant permission, or delete anything.', 'Playlists can span accessible libraries. A browsing preference must not silently rewrite a playlist or replace the current queue.', 'Uncheck a library, try selecting none, then save. Open the disclosure to see how playlists are handled.'],
  ready: ['04', 'Ready to listen', 'Setup ends before the music begins.', 'Review the connection and library choice, then go directly to playlists. There is no empty recent-session player on a first install.', 'Connection and library discovery are separate from fetching playlists. The short loading states in this prototype are visibly simulated.', 'Choose a playlist, tap its shuffle control, and land in the full player.'],
  playlists: ['05', 'Choose a playlist', 'Choose the thing you came to hear.', 'Your playlists appear before album browsing. Once you have listened, recent playlists get priority. A shuffle action opens the full player immediately.', 'This screen is not restricted to songs in your browse-only selection. Cross-library playlists stay intact within your account permissions.', 'Start a playlist, then use Reopen app to restore that listening session. The gear opens server and library settings.'],
  player: ['06', 'Player & song rating', 'Five stars, not a favorite heart.', 'Rate the current song from one to five stars, or clear its rating. The value belongs to this song and Navidrome account, not the playlist or album.', 'The five-star rating is distinct from Navidrome’s binary favorite. A failed save keeps the previous confirmed value and offers Retry.', 'Set four stars. Try a rating-save failure or offline state, then retry. Change tracks to confirm each song keeps its own rating.'],
  settings: ['07', 'Server & library management', 'A clear home for connections.', 'Choose a saved server, add another, change the libraries you browse, or refresh the library list. Your listening session is kept separately for each connection.', 'Creating libraries, assigning folder paths, and changing user access belong in Navidrome’s web administration. Removing a connection affects this device only.', 'Change libraries and cancel to preserve the old selection. Add a second demo server, switch back, or inspect the remove confirmation.'],
  queue: ['08', 'Saved listening queue', 'Your place is part of the session.', 'The shuffled order, current song, and position survive a cold launch in the browser demo. Returning to a playlist does not automatically create a fresh shuffle.', 'Changing the browsing library selection does not erase the saved queue.', 'Tap a song to jump within the queue, or return to the player.'],
  admin: ['07', 'Navidrome administration', 'Keep server administration on the server.', 'In the real client, this action would open the connected server in a browser. This prototype explains the handoff instead of navigating away.', 'Library paths, scans, and access permissions are server concerns. The regular account may need an administrator’s help.', 'Go back to settings without changing your library selection.'],
  remove: ['07', 'Remove connection', 'Remove access here, not the music.', 'The confirmation names the exact connection and makes the scope explicit. Server music, playlists, ratings, and accounts are not deleted.', 'Cancel is the safe initial choice; Escape also cancels. This mockup only removes fictional local connection state.', 'Cancel first. Then try removing the connection to inspect the resulting state.'],
};
const blank = () => ({ connections: [], active: null, ratings: {} });
let model = blank();
let storageOK = true;
try {
  const saved = JSON.parse(localStorage.getItem(storeKey));
  if (saved && Array.isArray(saved.connections) && saved.ratings && saved.connections.every(c => c.id && Array.isArray(c.libraries) && Array.isArray(c.selected))) model = saved;
} catch { storageOK = false; }
let screen = 'welcome';
let playing = false;
let query = '';
let libraryDraft = [];
let libraryMode = 'setup';
let connecting = false;
let loading = false;
let connectError = '';
let libraryError = '';
let feedback = '';
let editId = null;
let removeId = null;
let generation = 0;
const ratingJobs = new Map();
function invalidateAsync() {
  generation += 1;
  for (const [key, job] of ratingJobs) if (job.pending) ratingJobs.delete(key);
}
function active() { return model.connections.find(c => c.id === model.active); }
function persist() {
  try { localStorage.setItem(storeKey, JSON.stringify(model)); }
  catch { storageOK = false; }
  if (!storageOK) document.querySelector('#storage-note').textContent = 'Browser storage unavailable. Reopen simulation works; reload may not remember. No credentials are stored.';
}
function outcome() { return document.querySelector('#preview-outcome').value; }
function button(name, label, action, extra = '') {
  return `<button class="icon-button ${extra}" data-action="${action}" aria-label="${esc(label)}" title="${esc(label)}">${icon(name)}</button>`;
}
function header(title, label, back = 'back', settings = false) {
  return `<div class="app-top">${button('back', 'Back', back)}<div class="context"><span class="kicker">${label}</span><strong>${esc(title)}</strong></div>${settings ? button('settings', 'Server and libraries', 'settings') : ''}</div>`;
}
function goto(next) {
  screen = next;
  feedback = '';
  render();
}
function demoConnection(id = 'home', name = 'Home music', account = 'demo-listener', url = 'https://music.example.com') {
  return { id, name, account, url, libraries: librarySamples.map(l => ({ ...l })), selected: ['music', 'lossless'], session: null, recents: [] };
}
function seedConnection() {
  if (!active()) {
    const c = demoConnection();
    model.connections.push(c);
    model.active = c.id;
  }
  return active();
}
function makeSession(playlist = 'long-way') {
  const p = playlists.find(p => p.id === playlist) || playlists[0];
  const order = p.songs.map((_, i) => i);
  for (let i = order.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [order[i], order[j]] = [order[j], order[i]];
  }
  return { playlist: p.id, order, index: 0, position: 0, shuffle: true, repeat: false };
}
function currentSong(connection = active()) {
  if (!connection?.session) return null;
  const session = connection.session;
  const p = playlists.find(p => p.id === session.playlist);
  if (!p) return null;
  const pair = p.songs[session.order[session.index]];
  if (!pair) return null;
  const [ai, ti] = pair;
  return { ai, ti, title: albums[ai].tracks[ti], album: albums[ai], playlist: p, key: `${connection.id}:${ai}:${ti}` };
}
function time(n) { return `${Math.floor(n / 60)}:${String(n % 60).padStart(2, '0')}`; }
function mosaic(p) {
  return `<span class="playlist-cover" aria-hidden="true">${p.albums.map(i => `<img src="${albums[i].art}" alt="">`).join('')}</span>`;
}
function welcomePage() {
  return `<div class="page-body">
    <span class="kicker">Welcome to audio</span>
    <div class="welcome-mark" aria-hidden="true"><i></i><i></i><i></i><i></i></div>
    <h3 class="welcome-title">Your music.<br>Right where<br>you left it.</h3>
    <p class="welcome-copy">A personal player for the music on your Navidrome server.</p>
    <div class="benefit"><strong>Bring your playlists</strong><p>Choose your usual rotation. Shuffle once and start listening.</p></div>
    <div class="benefit"><strong>Keep your place</strong><p>Next time, open right to the player and continue your session.</p></div>
  </div><div class="page-actions"><button class="primary" data-action="connect">Connect to Navidrome</button>
    <p class="demo-note">Already have Navidrome? Use your existing account.<br><strong>Preview only: use dummy credentials.</strong></p></div>`;
}
function connectPage() {
  const c = editId && model.connections.find(c => c.id === editId);
  return `${header(c ? 'Reconnect' : 'Connect your server', 'Navidrome account', 'cancel-connect')}
    <div class="page-body">
      <p class="hint">Use your Navidrome account. You don’t need to create an Audio account.</p>
      <form class="connect-form" novalidate>
        <fieldset ${connecting ? 'disabled' : ''}>
          <div class="form-field"><label for="server-name">Connection name</label><input class="field-input" id="server-name" name="label" value="${esc(c?.name || '')}" placeholder="Home music" autocomplete="off"></div>
          <div class="form-field"><label for="server-url">Server address</label><input class="field-input" id="server-url" name="url" value="${esc(c?.url || '')}" placeholder="https://music.example.com" type="url" autocomplete="off" inputmode="url" aria-describedby="url-help url-error"><small class="field-help" id="url-help">Use the server’s base address, including a subpath if needed.</small><div class="inline-error" id="url-error"></div></div>
          <div class="form-field"><label for="server-user">Username</label><input class="field-input" id="server-user" name="username" value="${esc(c?.account || '')}" placeholder="demo-listener" autocomplete="off"></div>
          <div class="form-field"><label for="server-password">Password</label><div class="password-field"><input class="field-input" id="server-password" name="password" type="password" placeholder="Use any dummy password" autocomplete="off"><button type="button" class="icon-button" data-action="show-password" aria-label="Show password">${icon('eye')}</button></div></div>
          <div class="inline-error" id="auth-error" role="alert">${esc(connectError)}</div>
          <button class="primary" type="submit">${connecting ? 'Checking connection…' : 'Connect & discover libraries'}</button>
        </fieldset>
        <div class="form-status" role="status">${connecting ? 'Simulated sign-in and library discovery. Nothing is sent.' : ''}</div>
      </form>
      <p class="demo-note"><strong>Dummy values only.</strong> No password is sent, logged, or saved by this prototype. The real app would need secure device storage.</p>
    </div>`;
}
function libraryChoices(c) {
  return c.libraries.map(l => `<label class="library-choice"><input type="checkbox" value="${l.id}" ${libraryDraft.includes(l.id) ? 'checked' : ''}><span><strong>${esc(l.name)}</strong><small>${esc(l.detail)}</small></span></label>`).join('');
}
function librariesPage() {
  const c = active();
  return `${header('Choose libraries', libraryMode === 'setup' ? 'Setup / 2 of 2' : 'Browsing preferences', libraryMode === 'setup' ? 'settings' : 'cancel-libraries')}
    <div class="page-body">
      <div class="connected-summary">${icon('check')}<span><strong>${esc(c.name)}</strong><small>${esc(c.account)} · Signed in (simulated)</small></span></div>
      <h3 class="picker-heading">What do you want to browse?</h3>
      <p class="hint">Libraries this account can access on your server. Choose one or more. You can change this later.</p>
      ${c.libraries.length ? `<div class="library-options">${libraryChoices(c)}</div><p class="library-count">${libraryDraft.length} of ${c.libraries.length} selected</p>` : `<div class="empty-card no-access"><strong>No libraries available to this account.</strong><p>Ask your Navidrome administrator to grant access, then refresh. You don’t need to reconnect the whole app.</p><button class="primary secondary" data-action="refresh-access">Refresh access</button></div>`}
      <div class="inline-error" role="alert">${esc(libraryError)}</div>
      <details class="disclosure"><summary>What happens to playlists?</summary><p>This preference controls library browsing, not access permissions. Playlists may contain songs from several libraries you can access. We keep those playlists and your existing queue intact rather than silently removing songs.</p></details>
      <p class="demo-note">Missing a library? Its creation and user access are managed in Navidrome, not here.</p>
    </div><div class="page-actions"><button class="primary" data-action="save-libraries" ${!libraryDraft.length ? 'disabled' : ''}>${libraryMode === 'setup' ? 'Continue' : 'Save library selection'}</button>${libraryMode === 'manage' ? '<button class="text-button" data-action="cancel-libraries">Cancel</button>' : ''}</div>`;
}
function readyPage() {
  const c = active();
  return `${header('You’re connected', 'Setup complete', 'libraries')}
    <div class="page-body"><div class="ready-check">${icon('check')}</div>
    <h3 class="picker-heading">Let’s find your<br>first playlist.</h3><p class="hint">No listening history yet. Once you start, the next launch will remember your place.</p>
    <div class="summary-row"><span>Server</span><strong>${esc(c.name)}</strong></div>
    <div class="summary-row"><span>Account</span><strong>${esc(c.account)}</strong></div>
    <div class="summary-row"><span>Browse libraries</span><strong>${esc(c.libraries.filter(l => c.selected.includes(l.id)).map(l => l.name).join(', '))}</strong></div>
    <p class="demo-note">Sign-in and discovery are simulated. Your playlists have not been loaded yet.</p></div>
    <div class="page-actions"><button class="primary" data-action="load-playlists" ${loading ? 'disabled' : ''}>${loading ? 'Loading playlists…' : 'Choose a playlist'}</button><p class="form-status" role="status">${loading ? 'Loading sample playlists. No request is sent.' : ''}</p></div>`;
}
function playlistResults() {
  const c = active();
  const found = playlists.filter(p => p.name.toLowerCase().includes(query.toLowerCase()));
  if (!found.length) return '<div class="empty" role="status">No playlists match. Try another name.</div>';
  const recent = c.recents.map(id => found.find(p => p.id === id)).filter(Boolean);
  const rest = found.filter(p => !recent.includes(p));
  function rows(items) { return items.map(p => `<div class="playlist-row"><button class="playlist-info" data-playlist="${p.id}" aria-label="View ${esc(p.name)}">${mosaic(p)}<span class="playlist-copy"><strong>${esc(p.name)}</strong><small>${p.songs.length} sample songs · mixed libraries</small></span></button><div class="row-action"><button class="quick-start" data-start="${p.id}" aria-label="${c.session?.playlist === p.id ? 'Resume' : 'Shuffle and play'} ${esc(p.name)}">${icon(c.session?.playlist === p.id ? 'play' : 'shuffle')}</button><span>${c.session?.playlist === p.id ? 'Resume' : 'Shuffle'}</span></div></div>`).join(''); }
  return `${recent.length ? '<div class="section-label">Recently played</div>' + rows(recent) : ''}${rest.length ? '<div class="section-label">Your playlists</div>' + rows(rest) : ''}`;
}
let inspectedPlaylist = null;
function playlistsPage() {
  const c = active();
  if (inspectedPlaylist) {
    const p = playlists.find(p => p.id === inspectedPlaylist);
    return `${header(p.name, 'Playlist details', 'close-details', true)}<p class="hint">${esc(p.description)} ${p.songs.length} sample songs. Inspecting does not replace your session.</p><button class="primary" data-start="${p.id}" data-fresh="true">${icon('shuffle')}Shuffle & play</button>${p.songs.map(([ai, ti]) => `<div class="song-row"><img src="${albums[ai].art}" alt=""><span><strong>${esc(albums[ai].tracks[ti])}</strong><small>${esc(albums[ai].artist)}</small></span></div>`).join('')}`;
  }
  return `${header('Your playlists', c.name, c.session ? 'player' : 'settings', true)}
    <div class="page-body"><h3 class="picker-heading">${c.session ? 'What’s next?' : 'Start with a playlist.'}</h3><p class="hint">Tap shuffle to start it in the full player. Next time, you’ll open right to the music.</p>
    <label class="search">${icon('search')}<input type="search" aria-label="Search playlists" placeholder="Find a playlist" value="${esc(query)}"></label><div class="playlist-results">${playlistResults()}</div>
    <p class="demo-note">Playlists can cross your accessible libraries. Browsing preferences won’t rewrite them.</p></div>`;
}
function ratingMarkup(t) {
  const value = model.ratings[t.key] || 0;
  const job = ratingJobs.get(t.key);
  return `<div class="rating-block" data-rating-key="${t.key}">
    <div class="rating-meta"><span>Your song rating · ${value ? value + '/5' : 'Not rated'}</span><button class="text-button clear-rating" data-rating="0" ${job?.pending || !value ? 'disabled' : ''}>Clear</button></div>
    <div class="rating-options" role="radiogroup" aria-label="Song rating">${[1, 2, 3, 4, 5].map(n => `<button class="icon-button star-button ${n <= value ? 'selected' : ''}" role="radio" aria-checked="${n === value}" aria-label="${n} ${n === 1 ? 'star' : 'stars'}" data-rating="${n}" ${job?.pending ? 'disabled' : ''}>${icon('star')}</button>`).join('')}</div>
    <div class="rating-error-line"><span class="rating-status ${job?.error ? 'error' : ''}" role="status">${job?.pending ? 'Saving rating… (simulated)' : job?.error ? esc(job.error) : job?.saved ? 'Saved to Navidrome · simulated' : 'Navidrome song rating · sample value'}</span>${job?.error ? '<button class="text-button rating-retry" data-action="retry-rating">Retry</button>' : ''}</div>
  </div>`;
}
function playerPage() {
  const c = active(), t = currentSong();
  if (!t) return playlistsPage();
  return `<div class="app-top player-top"><div class="context"><span class="kicker">Listening to</span><strong>${esc(t.playlist.name)}</strong><small>${esc(c.name)} · ${esc(c.account)}</small></div>${button('settings', 'Server and libraries', 'settings')}</div>
    <img class="player-art" src="${t.album.art}" alt="${esc(t.album.title)} — illustrative artwork">
    <div class="song-heading"><div><h3>${esc(t.title)}</h3><p>${esc(t.album.artist)}</p></div></div>
    ${ratingMarkup(t)}
    <input class="seek" type="range" min="0" max="258" value="${c.session.position}" aria-label="Playback position"><div class="times"><span class="elapsed">${time(c.session.position)}</span><span>4:18</span></div>
    <div class="play-controls"><button class="icon-button ${c.session.shuffle ? 'active' : ''}" aria-label="Shuffle" aria-pressed="${c.session.shuffle}" data-action="shuffle">${icon('shuffle')}</button>${button('previous', 'Previous track', 'previous')}<button class="icon-button big" data-action="resume" aria-label="${playing ? 'Pause' : 'Resume playback'}">${icon(playing ? 'pause' : 'play')}</button>${button('next', 'Next track', 'next')}<button class="icon-button ${c.session.repeat ? 'active' : ''}" aria-label="Repeat" aria-pressed="${c.session.repeat}" data-action="repeat">${icon('repeat')}</button></div>
    <button class="change-playlist" data-action="playlists">${icon('library')}<span><strong>Change playlist</strong><small>Recent first · shuffle and play</small></span>${icon('down')}</button>
    <div class="secondary-row"><button class="text-button" data-action="queue">${icon('queue')}Queue</button><span class="source-note">${playing ? 'PLAYING · DEMO' : 'READY TO RESUME'}</span></div>`;
}
function settingsPage() {
  const c = active();
  return `${header('Server & libraries', 'Connection settings', 'close-settings')}
    <div class="page-body"><p class="hint">Connections on this device. Listening sessions stay separate for each server.</p>
    ${model.connections.map(x => `<div class="connection-row ${x.id === c?.id ? 'active-connection' : ''}"><span class="connection-copy"><strong>${esc(x.name)}</strong><small>${esc(x.account)}<br>${esc(x.url)}</small></span>${x.id === c?.id ? '<span class="badge">ACTIVE</span>' : `<button class="text-button" data-switch="${x.id}">Switch</button>`}</div>`).join('')}
    <button class="text-button" data-action="add-server">Add another server</button>
    ${c ? `<div class="settings-section"><h4>Browse libraries</h4><p class="hint">${esc(c.libraries.filter(l => c.selected.includes(l.id)).map(l => l.name).join(', ') || 'None selected')}</p><div class="settings-actions"><button class="primary secondary" data-action="manage-libraries">Choose libraries</button><button class="primary secondary" data-action="refresh-libraries" ${loading ? 'disabled' : ''}>${loading ? 'Refreshing…' : 'Refresh list'}</button></div><p class="form-status" role="status">${esc(feedback)}</p><p class="demo-note">Refresh reads library access and names; it does not scan server disks. Existing playlists and queue are unchanged.</p></div>
    <div class="settings-section"><h4>Server administration</h4><p class="hint">Create libraries, set folder paths, or grant access in Navidrome’s web interface. Administrator access may be required.</p><button class="primary secondary" data-action="admin">Open Navidrome administration</button></div>
    <div class="settings-section settings-bottom"><button class="text-button" data-action="reconnect">Reconnect account</button><button class="text-button danger" data-action="remove">Remove connection</button></div>` : ''}</div>`;
}
function adminPage() {
  const c = active();
  return `${header('Navidrome administration', 'Browser handoff · preview', 'settings')}<div class="page-body"><h3 class="picker-heading">Manage the server<br>in Navidrome.</h3><p class="hint">In the real app this opens your server’s web interface, not an embedded admin login.</p><div class="connected-summary"><span><strong>${esc(c.name)}</strong><small>${esc(c.url)}</small></span></div><div class="benefit"><strong>Create or edit libraries</strong><p>In Navidrome, open Settings → Libraries as an administrator. Name the library and set its server-side folder path.</p></div><div class="benefit"><strong>Change account access</strong><p>Use Settings → Users to grant this account the libraries it should be able to access.</p></div><div class="benefit"><strong>Return here and refresh</strong><p>Back in Audio, refresh the library list to see newly accessible libraries.</p></div><p class="demo-note"><strong>Simulated handoff.</strong> No browser is opened and no server configuration changes.</p></div><div class="page-actions"><button class="primary secondary" data-action="settings">Back to server settings</button></div>`;
}
function removePage() {
  const c = model.connections.find(x => x.id === removeId) || active();
  return `${header('Remove connection?', 'This device only', 'cancel-remove')}<div class="page-body"><h3 class="picker-heading">Remove ${esc(c.name)}?</h3><div class="alert-box"><p>This forgets the connection and its listening session on this device. It does not delete music, playlists, accounts, or ratings from Navidrome.</p></div><p class="hint">${esc(c.account)}<br>${esc(c.url)}</p><p class="demo-note">In this prototype, only fictional local demo data changes.</p></div><div class="page-actions"><button class="primary secondary" data-action="cancel-remove">Cancel</button><button class="text-button danger" data-action="confirm-remove">Remove connection from this device</button></div>`;
}
function queuePage() {
  const c = active(), t = currentSong();
  if (!t) return playlistsPage();
  return `${header('Your saved queue', t.playlist.name, 'player')}<p class="hint">Your order is kept when you resume. Browsing preferences do not remove songs from this queue.</p>${c.session.order.map((n, i) => {
    const [ai, ti] = t.playlist.songs[n];
    return `<div class="song-row ${i === c.session.index ? 'current' : ''}"><button data-queue-index="${i}"><span class="number">${i + 1}</span><img src="${albums[ai].art}" alt=""><span><strong>${esc(albums[ai].tracks[ti])}</strong><small>${esc(albums[ai].artist)}</small></span></button></div>`;
  }).join('')}`;
}
function mini() {
  const t = currentSong();
  if (!t || !['settings', 'libraries', 'playlists', 'queue'].includes(screen)) return '';
  return `<div class="mini"><button class="mini-open" data-action="player" aria-label="Open full player"><img src="${t.album.art}" alt=""><span><strong>${esc(t.title)}</strong><small>${esc(active().name)} · ${esc(t.playlist.name)}</small></span></button>${button(playing ? 'pause' : 'play', playing ? 'Pause' : 'Resume playback', 'mini-play')}</div>`;
}
function render() {
  if (!active() && !['welcome', 'connect'].includes(screen)) screen = 'welcome';
  const meta = descriptions[screen];
  document.querySelector('#screen-number').textContent = meta[0];
  document.querySelector('#screen-label').textContent = meta[1];
  document.querySelector('#explain-title').textContent = meta[2];
  document.querySelector('#explain-copy').textContent = meta[3];
  document.querySelector('#explain-decision').textContent = meta[4];
  document.querySelector('#explain-try').textContent = meta[5];
  document.querySelector('#preview-screen').value = ['admin', 'remove'].includes(screen) ? 'settings' : screen;
  device.classList.toggle('player-mode', screen === 'player');
  device.dataset.screen = screen;
  const renderer = { welcome: welcomePage, connect: connectPage, libraries: librariesPage, ready: readyPage, playlists: playlistsPage, player: playerPage, settings: settingsPage, queue: queuePage, admin: adminPage, remove: removePage }[screen];
  device.innerHTML = `<div class="camera" aria-hidden="true"></div><div class="status"><span>9:41</span><span>${icon('signal')}${icon('wifi')}${icon('battery')}</span></div><div class="screen">${renderer()}</div>${mini()}<div class="gesture" aria-hidden="true"></div>`;
  if (screen === 'remove') device.querySelector('.page-actions [data-action="cancel-remove"]')?.focus({ preventScroll: true });
  if (!storageOK) document.querySelector('#storage-note').textContent = 'Browser storage unavailable. Demo changes may not survive reload. No credentials stored.';
}
function openLibraries(mode) {
  libraryMode = mode;
  libraryDraft = [...active().selected];
  libraryError = '';
  goto('libraries');
}
function enterPreview(target) {
  invalidateAsync();
  connecting = false;
  loading = false;
  connectError = '';
  inspectedPlaylist = null;
  editId = null;
  if (!['welcome', 'connect'].includes(target)) {
    const c = seedConnection();
    if (['player', 'queue'].includes(target) && !c.session) {
      c.session = { playlist: 'long-way', order: playlists[0].songs.map((_, i) => i), index: 0, position: 81, shuffle: true, repeat: false };
      c.recents = ['long-way', 'daily'];
      model.ratings[`${c.id}:0:1`] ??= 3;
    }
    if (target === 'libraries') {
      libraryMode = 'setup';
      libraryDraft = [...c.selected];
      libraryError = '';
    }
    persist();
  }
  goto(target);
}
function startPlaylist(id, fresh = false) {
  const c = active();
  if (!c.session || c.session.playlist !== id || fresh) c.session = makeSession(id);
  c.recents = [id, ...c.recents.filter(x => x !== id)];
  playing = true;
  inspectedPlaylist = null;
  persist();
  goto('player');
}
function rate(value) {
  const t = currentSong();
  if (!t || ratingJobs.get(t.key)?.pending) return;
  const target = t.key;
  const gen = generation;
  const mode = outcome();
  ratingJobs.set(target, { pending: true, value });
  render();
  setTimeout(() => {
    if (gen !== generation) return;
    if (mode === 'offline' || mode === 'rating-error') {
      ratingJobs.set(target, { value, error: mode === 'offline' ? 'Not saved · offline. Previous rating kept.' : 'Save failed. Previous rating kept.' });
    } else {
      model.ratings[target] = value;
      ratingJobs.set(target, { value, saved: true });
      persist();
    }
    if (screen === 'player' && currentSong()?.key === target) {
      render();
      device.querySelector(`[data-rating="${value || 1}"]`)?.focus({ preventScroll: true });
    }
  }, 450);
}
function validateAddress(raw) {
  try {
    const url = new URL(raw);
    if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash) return null;
    return url.href.replace(/\/$/, '');
  } catch { return null; }
}
device.addEventListener('submit', event => {
  if (!event.target.matches('.connect-form')) return;
  event.preventDefault();
  if (connecting) return;
  const form = event.target;
  const url = validateAddress(form.elements.url.value.trim());
  const user = form.elements.username.value.trim();
  const password = form.elements.password.value;
  const label = form.elements.label.value.trim() || 'My Navidrome';
  form.querySelector('#url-error').textContent = '';
  form.querySelector('#auth-error').textContent = '';
  if (!url) {
    form.querySelector('#url-error').textContent = 'Enter an HTTPS base address, without embedded credentials, query parameters, or a fragment.';
    form.elements.url.focus();
    return;
  }
  if (!user || !password) {
    form.querySelector('#auth-error').textContent = 'Enter a username and a dummy password to preview sign-in.';
    (!user ? form.elements.username : form.elements.password).focus();
    return;
  }
  const mode = outcome();
  const currentGeneration = generation;
  const reconnectTarget = editId;
  // Capture only non-secret display metadata. The supplied password is cleared now.
  form.elements.password.value = '';
  connecting = true;
  connectError = '';
  form.querySelector('fieldset').disabled = true;
  form.querySelector('button[type="submit"]').textContent = 'Checking connection…';
  form.querySelector('.form-status').textContent = 'Simulated sign-in and library discovery. No network request is sent.';
  setTimeout(() => {
    if (currentGeneration !== generation || screen !== 'connect') return;
    connecting = false;
    if (['auth', 'network', 'offline'].includes(mode)) {
      connectError = mode === 'auth' ? 'Sign-in failed (simulated). Check your username and password, then try again.' : 'Server unreachable (simulated). Check the address and network, then retry.';
      // Keep non-secret drafts, never the password, after a simulated failure.
      render();
      device.querySelector('#server-name').value = label;
      device.querySelector('#server-url').value = url;
      device.querySelector('#server-user').value = user;
      return;
    }
    let c = model.connections.find(c => c.id === reconnectTarget);
    if (!c) {
      c = demoConnection(`demo-${Date.now()}-${model.connections.length}`, label, user, url);
      model.connections.push(c);
    } else {
      const changedIdentity = c.account !== user || c.url !== url;
      if (changedIdentity) {
        // A different account or endpoint is a new connection, not a retargeted session.
        c = demoConnection(`demo-${Date.now()}-${model.connections.length}`, label, user, url);
        model.connections.push(c);
      }
    }
    c.libraries = mode === 'no-libraries' ? [] : librarySamples.map(l => ({ ...l }));
    c.selected = c.selected.filter(id => c.libraries.some(l => l.id === id));
    if (!c.selected.length) c.selected = c.libraries.map(l => l.id);
    model.active = c.id;
    playing = false;
    editId = null;
    persist();
    openLibraries(reconnectTarget ? 'manage' : 'setup');
  }, 500);
});
device.addEventListener('input', event => {
  if (event.target.matches('input[type="search"]')) {
    query = event.target.value;
    device.querySelector('.playlist-results').innerHTML = playlistResults();
  }
  if (event.target.matches('.seek')) {
    active().session.position = Number(event.target.value);
    device.querySelector('.elapsed').textContent = time(active().session.position);
    persist();
  }
});
device.addEventListener('change', event => {
  if (!event.target.matches('.library-choice input')) return;
  libraryDraft = [...device.querySelectorAll('.library-choice input:checked')].map(x => x.value);
  device.querySelector('.library-count').textContent = `${libraryDraft.length} of ${active().libraries.length} selected`;
  device.querySelector('[data-action="save-libraries"]').disabled = !libraryDraft.length;
});
device.addEventListener('click', event => {
  const b = event.target.closest('button');
  if (!b || b.disabled) return;
  const c = active();
  if (b.dataset.rating !== undefined) { rate(Number(b.dataset.rating)); return; }
  if (b.dataset.start) { startPlaylist(b.dataset.start, b.dataset.fresh === 'true'); return; }
  if (b.dataset.playlist) { inspectedPlaylist = b.dataset.playlist; goto('playlists'); return; }
  if (b.dataset.switch) {
    model.active = b.dataset.switch;
    playing = false;
    persist();
    goto(active().session ? 'player' : 'playlists');
    return;
  }
  if (b.dataset.queueIndex !== undefined) {
    c.session.index = Number(b.dataset.queueIndex);
    c.session.position = 0;
    playing = true;
    persist();
    goto('player');
    return;
  }
  const action = b.dataset.action;
  if (['player', 'playlists', 'settings', 'queue', 'admin'].includes(action)) {
    inspectedPlaylist = null;
    query = '';
    goto(action);
  } else if (action === 'connect' || action === 'add-server' || action === 'reconnect') {
    invalidateAsync();
    editId = action === 'reconnect' ? c.id : null;
    connecting = false;
    connectError = '';
    goto('connect');
  } else if (action === 'cancel-connect') {
    invalidateAsync();
    connecting = false;
    editId = null;
    connectError = '';
    goto(c ? 'settings' : 'welcome');
  } else if (action === 'show-password') {
    const input = device.querySelector('#server-password');
    input.type = input.type === 'password' ? 'text' : 'password';
    b.setAttribute('aria-label', input.type === 'password' ? 'Show password' : 'Hide password');
  } else if (action === 'libraries') openLibraries('setup');
  else if (action === 'manage-libraries') openLibraries('manage');
  else if (action === 'cancel-libraries') goto('settings');
  else if (action === 'save-libraries') {
    if (!libraryDraft.length) { libraryError = 'Select at least one library.'; render(); return; }
    c.selected = [...libraryDraft];
    persist();
    goto(libraryMode === 'setup' ? 'ready' : 'settings');
  } else if (action === 'load-playlists') {
    loading = true;
    render();
    const gen = generation;
    setTimeout(() => {
      if (generation !== gen || screen !== 'ready') return;
      loading = false;
      query = '';
      goto('playlists');
    }, 400);
  } else if (action === 'refresh-access') {
    if (['network', 'offline'].includes(outcome())) {
      libraryError = 'Refresh failed (simulated). Your account access is unchanged.';
      render();
    } else {
      c.libraries = librarySamples.map(l => ({ ...l }));
      libraryDraft = c.libraries.map(l => l.id);
      persist();
      render();
    }
  } else if (action === 'refresh-libraries') {
    const target = c.id, gen = generation, mode = outcome();
    loading = true;
    feedback = '';
    render();
    setTimeout(() => {
      if (generation !== gen || active()?.id !== target || screen !== 'settings') return;
      loading = false;
      if (['network', 'offline'].includes(mode)) feedback = 'Refresh failed (simulated). Previous library selection kept.';
      else {
        c.libraries = librarySamples.map(l => ({ ...l }));
        feedback = 'Library list refreshed · simulated. Your listening queue is unchanged.';
        persist();
      }
      render();
    }, 400);
  } else if (action === 'remove') { removeId = c.id; goto('remove'); }
  else if (action === 'cancel-remove') {
    removeId = null;
    goto('settings');
    device.querySelector('[data-action="remove"]')?.focus({ preventScroll: true });
  } else if (action === 'confirm-remove') {
    invalidateAsync();
    const target = removeId;
    model.connections = model.connections.filter(c => c.id !== target);
    model.active = model.connections[0]?.id || null;
    removeId = null;
    playing = false;
    // Song ratings are server-owned in the real product. This demo does not delete them.
    persist();
    goto(active() ? 'settings' : 'welcome');
  } else if (action === 'close-settings' || action === 'back') goto(c?.session ? 'player' : c ? 'playlists' : 'welcome');
  else if (action === 'close-details') { inspectedPlaylist = null; goto('playlists'); }
  else if (action === 'resume' || action === 'mini-play') {
    playing = !playing;
    if (action === 'mini-play') screen = 'player';
    render();
  } else if (action === 'next' || action === 'previous') {
    c.session.index = (c.session.index + (action === 'next' ? 1 : c.session.order.length - 1)) % c.session.order.length;
    c.session.position = 0;
    persist();
    render();
  } else if (action === 'repeat') { c.session.repeat = !c.session.repeat; persist(); render(); }
  else if (action === 'shuffle') {
    const current = c.session.order[c.session.index];
    c.session.shuffle = !c.session.shuffle;
    const rest = c.session.order.filter(n => n !== current);
    if (c.session.shuffle) rest.sort(() => Math.random() - .5);
    else rest.sort((a, b) => a - b);
    c.session.order = [current, ...rest];
    c.session.index = 0;
    persist();
    render();
  } else if (action === 'retry-rating') {
    const target = currentSong().key;
    rate(ratingJobs.get(target).value);
  }
});
device.addEventListener('keydown', event => {
  const radio = event.target.closest('[data-rating][role="radio"]');
  if (radio && ['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) {
    event.preventDefault();
    const current = Number(radio.dataset.rating);
    const next = event.key === 'Home' ? 1 : event.key === 'End' ? 5 : Math.max(1, Math.min(5, current + (event.key === 'ArrowRight' ? 1 : -1)));
    rate(next);
  }
  if (event.key === 'Escape') {
    if (screen === 'remove') { removeId = null; goto('settings'); device.querySelector('[data-action="remove"]')?.focus(); }
    else if (screen === 'libraries' && libraryMode === 'manage') goto('settings');
    else if (screen === 'connect') { invalidateAsync(); connecting = false; goto(active() ? 'settings' : 'welcome'); }
    else if (active()) goto(active().session ? 'player' : 'playlists');
  }
});
document.querySelector('#preview-screen').addEventListener('change', event => {
  const target = event.target.value;
  history.replaceState(null, '', '#' + target);
  enterPreview(target);
});
document.querySelector('#preview-outcome').addEventListener('change', () => {
  // Review controls never change confirmed data on their own.
});
document.querySelector('#reset').addEventListener('click', () => {
  invalidateAsync();
  model = blank();
  connecting = false;
  loading = false;
  playing = false;
  inspectedPlaylist = null;
  editId = null;
  removeId = null;
  query = '';
  ratingJobs.clear();
  document.querySelector('#preview-outcome').value = 'success';
  history.replaceState(null, '', location.pathname);
  persist();
  goto('welcome');
});
document.querySelector('#reopen').addEventListener('click', () => {
  invalidateAsync();
  connecting = false;
  loading = false;
  playing = false;
  for (const [key, job] of ratingJobs) if (job.pending) ratingJobs.delete(key);
  history.replaceState(null, '', location.pathname);
  goto(active()?.session ? 'player' : active() ? 'playlists' : 'welcome');
});
window.addEventListener('hashchange', () => {
  const target = location.hash.slice(1);
  if (descriptions[target]) enterPreview(target);
});
const initialPreview = location.hash.slice(1);
if (descriptions[initialPreview]) enterPreview(initialPreview);
else goto(active()?.session ? 'player' : active() ? 'playlists' : 'welcome');
