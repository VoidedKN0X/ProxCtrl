// Shared by the console pages. The WebSocket itself lives in the app (it needs the API token
// and the pinned certificate, which a WebView can't add); this file turns the app's calls into
// a WebSocket-like object and back. `ProxBridge` is the app's JavaScript interface.
(function () {
  'use strict';
  function b64ToBytes(b64) {
    const bin = atob(b64);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }
  function bytesToB64(bytes) {
    let bin = '';
    const chunk = 0x8000;
    for (let i = 0; i < bytes.length; i += chunk) {
      bin += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
    }
    return btoa(bin);
  }

  // Looks enough like a WebSocket for xterm and noVNC: send/close, readyState, on* handlers.
  const channel = {
    binaryType: 'arraybuffer',
    protocol: '',
    readyState: 'connecting',
    onopen: null,
    onmessage: null,
    onclose: null,
    onerror: null,
    send(data) {
      if (this.readyState !== 'open') return;
      if (typeof data === 'string') {
        ProxBridge.sendText(data);
      } else {
        const bytes = data instanceof Uint8Array ? data : new Uint8Array(data.buffer || data);
        ProxBridge.sendBinary(bytesToB64(bytes));
      }
    },
    close() {
      if (this.readyState === 'closed') return;
      this.readyState = 'closing';
      ProxBridge.close();
    },
  };

  // Called by the app.
  window.pcOpen = function () {
    channel.readyState = 'open';
    if (channel.onopen) channel.onopen({ type: 'open' });
  };
  window.pcRecv = function (b64) {
    if (channel.onmessage) channel.onmessage({ data: b64ToBytes(b64).buffer });
  };
  window.pcClosed = function (code, reason) {
    channel.readyState = 'closed';
    if (channel.onclose) channel.onclose({ code: code, reason: reason, wasClean: code === 1000 });
  };
  window.pcError = function (msg) {
    if (channel.onerror) channel.onerror({ type: 'error', message: msg });
  };

  window.ProxChannel = channel;
})();
