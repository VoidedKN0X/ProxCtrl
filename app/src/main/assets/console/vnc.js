// noVNC for VM consoles, fed by the app's WebSocket through bridge.js.
import RFB from '../novnc/core/rfb.js';
import * as Log from '../novnc/core/util/logging.js';
if (new URLSearchParams(location.search).get('debug')) Log.initLogging('debug');
import KeyTable from '../novnc/core/input/keysym.js';
import keysyms from '../novnc/core/input/keysymdef.js';

// The VNC password comes from Proxmox's vncproxy call, which the app makes after this page is
// ready, so it's asked for only when the server wants it.
const rfb = new RFB(document.getElementById('screen'), window.ProxChannel, { shared: true });
rfb.scaleViewport = true;
rfb.resizeSession = false;
rfb.clipViewport = false;
rfb.focusOnClick = false;
rfb.background = '#000';

rfb.addEventListener('connect', () => ProxBridge.connected());
rfb.addEventListener('disconnect', (e) => ProxBridge.disconnected(e.detail && e.detail.clean ? 'clean' : 'lost'));
rfb.addEventListener('credentialsrequired', () => rfb.sendCredentials({ password: ProxBridge.vncPassword() }));
rfb.addEventListener('securityfailure', (e) => ProxBridge.failed((e.detail && e.detail.reason) || 'security'));
rfb.addEventListener('desktopname', (e) => ProxBridge.title(e.detail.name || ''));

// Sticky modifiers from the app's extra-keys bar: held down until the next key.
const held = { ctrl: false, alt: false, meta: false };
function setHeld(ctrl, alt, meta) {
  if (ctrl !== held.ctrl) rfb.sendKey(KeyTable.XK_Control_L, 'ControlLeft', ctrl);
  if (alt !== held.alt) rfb.sendKey(KeyTable.XK_Alt_L, 'AltLeft', alt);
  if (meta !== held.meta) rfb.sendKey(KeyTable.XK_Super_L, 'MetaLeft', meta);
  held.ctrl = ctrl; held.alt = alt; held.meta = meta;
}
function releaseHeld() {
  if (held.ctrl || held.alt || held.meta) {
    setHeld(false, false, false);
    ProxBridge.modsReleased();
  }
}
function tap(keysym, code) {
  rfb.sendKey(keysym, code || null);
  releaseHeld();
}
function typeChar(ch) {
  const cp = ch.codePointAt(0);
  if (ch === '\n') return tap(KeyTable.XK_Return, 'Enter');
  if (ch === '\t') return tap(KeyTable.XK_Tab, 'Tab');
  tap(keysyms.lookup(cp));
}

// Soft keyboard: Android sends text through "input" events rather than reliable key events.
const kbd = document.getElementById('kbd');
kbd.addEventListener('beforeinput', (e) => {
  if (e.inputType === 'deleteContentBackward') { tap(KeyTable.XK_BackSpace, 'Backspace'); e.preventDefault(); }
  else if (e.inputType === 'insertLineBreak' || e.inputType === 'insertParagraph') { tap(KeyTable.XK_Return, 'Enter'); e.preventDefault(); }
});
kbd.addEventListener('input', () => {
  const text = kbd.value;
  kbd.value = '';
  for (const ch of text) typeChar(ch);
});
kbd.addEventListener('keydown', (e) => {
  // Hardware or IME keys that do arrive as key events.
  const map = { Enter: KeyTable.XK_Return, Backspace: KeyTable.XK_BackSpace, Escape: KeyTable.XK_Escape, Tab: KeyTable.XK_Tab,
    ArrowLeft: KeyTable.XK_Left, ArrowRight: KeyTable.XK_Right, ArrowUp: KeyTable.XK_Up, ArrowDown: KeyTable.XK_Down,
    Delete: KeyTable.XK_Delete, Home: KeyTable.XK_Home, End: KeyTable.XK_End, PageUp: KeyTable.XK_Page_Up, PageDown: KeyTable.XK_Page_Down };
  if (map[e.key] !== undefined) { tap(map[e.key], e.code || null); e.preventDefault(); }
});

const KEYS = {
  Escape: [KeyTable.XK_Escape, 'Escape'], Tab: [KeyTable.XK_Tab, 'Tab'],
  Left: [KeyTable.XK_Left, 'ArrowLeft'], Right: [KeyTable.XK_Right, 'ArrowRight'],
  Up: [KeyTable.XK_Up, 'ArrowUp'], Down: [KeyTable.XK_Down, 'ArrowDown'],
  Home: [KeyTable.XK_Home, 'Home'], End: [KeyTable.XK_End, 'End'],
  PageUp: [KeyTable.XK_Page_Up, 'PageUp'], PageDown: [KeyTable.XK_Page_Down, 'PageDown'],
  Delete: [KeyTable.XK_Delete, 'Delete'],
};
for (let i = 1; i <= 12; i++) KEYS['F' + i] = [KeyTable.XK_F1 + i - 1, 'F' + i];

// Called by the app.
window.pcSetMods = (ctrl, alt, meta) => setHeld(ctrl, alt, !!meta);
window.pcKey = (name) => { const k = KEYS[name]; if (k) tap(k[0], k[1]); };
window.pcCtrlAltDel = () => { rfb.sendCtrlAltDel(); releaseHeld(); };
window.pcPaste = (text) => { for (const ch of text.slice(0, 4000)) typeChar(ch); };
window.pcKeyboard = (show) => { if (show) { kbd.focus(); } else { kbd.blur(); } };
window.pcFocus = () => rfb.focus();

ProxBridge.pageReady();
