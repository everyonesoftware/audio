// V5-only presentation and dummy-form conveniences; the earlier versions stay intact.
let ratingPopupOpen = false;
let ratingPopupTarget = null;
const previousRender = render;

function compactRatingButton(t) {
  const value = model.ratings[t.key] || 0;
  const job = ratingJobs.get(t.key);
  const label = job?.pending ? 'Saving' : job?.error ? 'Retry' : value ? `${value}/5` : 'Rate';
  return `<button class="rating-trigger ${value ? 'rated' : ''}" data-action="open-rating" aria-label="Rate song, ${value ? value + ' of 5 stars' : 'not rated'}" aria-haspopup="dialog" aria-expanded="${ratingPopupOpen}">${icon('star')}<span>${label}</span></button>`;
}

playerPage = function readablePlayerPage() {
  const c = active(), t = currentSong();
  if (!t) return playlistsPage();
  return `<div class="app-top player-top"><div class="context"><span class="kicker">Listening to</span><strong>${esc(t.playlist.name)}</strong></div>${button('settings', 'Server and libraries', 'settings')}</div>
    <div class="player-visual"><img class="player-art" src="${t.album.art}" alt="${esc(t.album.title)} — illustrative artwork"></div>
    <div class="song-heading player-song"><div><h3>${esc(t.title)}</h3><p>${esc(t.album.artist)}</p></div>${compactRatingButton(t)}</div>
    <div class="player-seek"><input class="seek" type="range" min="0" max="258" value="${c.session.position}" aria-label="Playback position"><div class="times"><span class="elapsed">${time(c.session.position)}</span><span>4:18</span></div></div>
    <div class="play-controls"><button class="icon-button ${c.session.shuffle ? 'active' : ''}" aria-label="Shuffle" aria-pressed="${c.session.shuffle}" data-action="shuffle">${icon('shuffle')}</button>${button('previous', 'Previous track', 'previous')}<button class="icon-button big" data-action="resume" aria-label="${playing ? 'Pause' : 'Resume playback'}">${icon(playing ? 'pause' : 'play')}</button>${button('next', 'Next track', 'next')}<button class="icon-button ${c.session.repeat ? 'active' : ''}" aria-label="Repeat" aria-pressed="${c.session.repeat}" data-action="repeat">${icon('repeat')}</button></div>
    <button class="change-playlist" data-action="playlists">${icon('library')}<span><strong>Change playlist</strong><small>Recent first · shuffle and play</small></span>${icon('down')}</button>
    <div class="secondary-row"><button class="text-button" data-action="queue">${icon('queue')}Queue</button><span class="source-note">${playing ? 'PLAYING · DEMO' : 'READY TO RESUME'}</span></div>`;
};

function popupMarkup(t) {
  const value = model.ratings[t.key] || 0;
  const job = ratingJobs.get(t.key);
  const message = job?.pending ? 'Saving rating… (simulated)' : job?.error ? job.error : job?.saved ? 'Saved to Navidrome · simulated' : 'Choose a rating for this song.';
  return `<dialog class="rating-dialog" aria-labelledby="rating-title" aria-describedby="rating-song rating-help">
    <h3 id="rating-title">Rate this song</h3>
    <p class="rating-song" id="rating-song">${esc(t.title)}</p><p class="rating-artist">${esc(t.album.artist)}</p>
    <p class="rating-value">Your rating: ${value ? value + ' of 5 stars' : 'Not rated'}</p>
    <div class="rating-choices" role="radiogroup" aria-label="Song rating">${[1, 2, 3, 4, 5].map(n => `<button class="rating-choice ${n <= value ? 'filled' : ''}" role="radio" aria-checked="${n === value}" aria-label="${n} ${n === 1 ? 'star' : 'stars'}" data-rating="${n}" ${job?.pending ? 'disabled' : ''}>${icon('star')}<span>${n}</span></button>`).join('')}</div>
    <div class="rating-error-line"><span class="rating-status ${job?.error ? 'error' : ''}" role="status">${esc(message)}</span>${job?.error ? '<button class="text-button rating-retry" data-action="retry-rating">Retry</button>' : ''}</div>
    <p class="rating-help" id="rating-help">Tap a star to save. Clear removes your rating. This preview does not send changes to a server.</p>
    <div class="rating-dialog-actions"><button class="text-button clear-rating" data-rating="0" ${job?.pending || !value ? 'disabled' : ''}>Clear rating</button><button class="primary" data-action="close-rating">Done</button></div>
  </dialog>`;
}

function closeRatingPopup() {
  ratingPopupOpen = false;
  ratingPopupTarget = null;
  const dialog = device.querySelector('.rating-dialog');
  if (dialog?.open) dialog.close();
  dialog?.remove();
  const trigger = device.querySelector('[data-action="open-rating"]');
  trigger?.setAttribute('aria-expanded', 'false');
  trigger?.focus({ preventScroll: true });
}

render = function readableRender() {
  if (screen !== 'player' || currentSong()?.key !== ratingPopupTarget) {
    ratingPopupOpen = false;
    ratingPopupTarget = null;
  }
  previousRender();
  if (screen === 'libraries') device.querySelector('.disclosure')?.remove();
  if (screen === 'connect') {
    const demo = {
      'server-name': 'Demo home',
      'server-url': 'https://music.example.com',
      'server-user': 'demo-listener',
      'server-password': 'demo-only',
    };
    for (const [id, value] of Object.entries(demo)) {
      const input = device.querySelector('#' + id);
      if (input && !input.value) input.value = value;
    }
    device.querySelector('.hint').textContent = 'Demo values are filled in. Tap Connect to explore—nothing is sent.';
    device.querySelector('.demo-note').textContent = 'Mockup only: sample account, no real sign-in or password storage.';
  }
  if (ratingPopupOpen && currentSong()?.key === ratingPopupTarget) {
    const t = currentSong();
    device.insertAdjacentHTML('beforeend', popupMarkup(t));
    const dialog = device.querySelector('.rating-dialog');
    dialog.addEventListener('cancel', event => { event.preventDefault(); closeRatingPopup(); });
    dialog.addEventListener('click', event => {
      if (event.target !== dialog) return;
      const r = dialog.getBoundingClientRect();
      if (event.clientX < r.left || event.clientX > r.right || event.clientY < r.top || event.clientY > r.bottom) closeRatingPopup();
    });
    dialog.showModal();
    const selected = dialog.querySelector('[aria-checked="true"]:not(:disabled)') || dialog.querySelector('[role="radio"]:not(:disabled)') || dialog.querySelector('[data-action="close-rating"]');
    selected?.focus({ preventScroll: true });
  }
};

device.addEventListener('click', event => {
  const action = event.target.closest('button')?.dataset.action;
  if (action !== 'open-rating' && action !== 'close-rating') return;
  event.preventDefault();
  event.stopImmediatePropagation();
  if (action === 'close-rating') closeRatingPopup();
  else {
    ratingPopupOpen = true;
    ratingPopupTarget = currentSong().key;
    render();
  }
}, true);

device.addEventListener('keydown', event => {
  if (event.key === 'Escape' && ratingPopupOpen) {
    event.preventDefault();
    event.stopImmediatePropagation();
    closeRatingPopup();
  }
}, true);

descriptions.player = ['06', 'Player & rating popup', 'One small star. A bigger decision surface.', 'The player uses its full height: artwork above, readable song details, and playback and playlist controls below. The star beside the song opens a large rating popup.', 'The five choices are deliberately not packed into the player. Their popup has large individual targets, Clear, save feedback, Retry, and Done. Escape restores focus to the star.', 'Tap the star beside the title, choose a rating, then Done. Try offline or save failure using the preview outcome selector.'];
descriptions.libraries[5] = 'Choose the libraries to browse, then Continue. The extra playlist explanation is omitted so the setup action fits without scrolling.';
descriptions.connect = ['02', 'Connect to Navidrome', 'Preview the flow without typing.', 'All four fields are prefilled with fictional values in this mockup. Click Connect & discover libraries to continue. The earlier validation-focused versions are preserved.', 'This convenience is preview-only. No account is authenticated and no password is sent or persisted. Error scenarios remain available in the outcome menu.', 'Click Connect immediately—no data entry needed. Failure and retry use the same dummy values.'];
