// xterm.js talking to Proxmox's termproxy (container consoles and the node shell).
// Protocol, as in Proxmox's own xterm page: the app sends "user:ticket\n" first, the server answers
// "OK", then input is "0:<utf-8 length>:<data>", a resize is "1:<cols>:<rows>:" and "2" is a ping.
(function () {
  'use strict';
  const sock = window.ProxChannel;
  const term = new Terminal({
    cursorBlink: true,
    fontSize: Number(new URLSearchParams(location.search).get('font')) || 13,
    fontFamily: 'monospace',
    scrollback: 5000,
    allowProposedApi: true,
    theme: { background: '#0d0f12', foreground: '#eceef2', cursor: '#ffb37a', selectionBackground: '#5c3416' },
  });
  const fit = new FitAddon.FitAddon();
  term.loadAddon(fit);
  term.open(document.getElementById('term'));
  fit.fit();

  let connected = false;
  const mods = { ctrl: false, alt: false };
  const encoder = new TextEncoder();

  function send(data) {
    if (!connected) return;
    sock.send('0:' + encoder.encode(data).length + ':' + data);
  }
  function sendSize() {
    if (connected) sock.send('1:' + term.cols + ':' + term.rows + ':');
  }
  function applyMods(data) {
    if (mods.ctrl && data.length === 1) {
      const c = data.toUpperCase().charCodeAt(0);
      if (c >= 64 && c <= 95) data = String.fromCharCode(c - 64);
      else if (data === ' ') data = '\x00';
    }
    if (mods.alt) data = '\x1b' + data;
    if (mods.ctrl || mods.alt) {
      mods.ctrl = mods.alt = false;
      ProxBridge.modsReleased();
    }
    return data;
  }

  sock.onopen = function () { term.write('\x1b[2m…\x1b[0m'); };
  sock.onmessage = function (ev) {
    const bytes = new Uint8Array(ev.data);
    if (connected) {
      term.write(bytes);
    } else if (bytes[0] === 79 && bytes[1] === 75) { // "OK"
      connected = true;
      term.reset();
      term.write(bytes.slice(2));
      requestAnimationFrame(() => { fit.fit(); sendSize(); term.focus(); });
      ProxBridge.connected();
    } else {
      ProxBridge.failed(new TextDecoder().decode(bytes).slice(0, 200));
    }
  };
  sock.onclose = function () { connected = false; };

  term.onData(function (data) { send(applyMods(data)); });
  term.onResize(sendSize);
  setInterval(function () { if (connected) sock.send('2'); }, 30000);
  let resizeTimer;
  window.addEventListener('resize', function () {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(function () { fit.fit(); }, 150);
  });

  // Called by the app's extra-keys bar.
  window.pcSetMods = function (ctrl, alt) { mods.ctrl = ctrl; mods.alt = alt; };
  window.pcKey = function (seq) { send(applyMods(seq)); term.focus(); };
  window.pcPaste = function (text) { term.paste(text); term.focus(); };
  window.pcFocus = function () { term.focus(); };
  window.pcFontSize = function (size) { term.options.fontSize = size; fit.fit(); };

  ProxBridge.pageReady();
})();
