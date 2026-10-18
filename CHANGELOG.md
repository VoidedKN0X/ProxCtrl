# Changelog

What changed in each ProxCtrl release. Every release on the [Releases page](https://github.com/VoidedKN0X/ProxCtrl/releases) also lists the exact changes in that build.

To publish a new version: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a section here, and push to `main`. Pushes without a version bump only build; they don't make a release.

## v0.2.0 (2026-10-19)

ProxCtrl now connects to your Proxmox VE server. The tabs still show placeholders; they fill in over the next versions, starting with Guests.

### New

- **Add your Proxmox server** on first start. Type the node's address, like `192.168.1.10` (ProxCtrl adds port 8006 for you) or a reverse-proxy address like `https://pve.example.com` (port 443). The screen shows the exact address it will connect to before you tap Connect
- **Sign in with an API token** (recommended), using the token ID and secret
- **Or sign in with username and password**, with a realm picker that lists the realms your server offers (Linux PAM, Proxmox VE, LDAP and so on). You can also type `user@realm` directly. The login session is renewed automatically while you use the app, so without two-factor sign-in you don't have to sign in again every two hours
- **Two-factor sign-in**: accounts with an authenticator app (TOTP) are asked for the 6-digit code, and a recovery key can be used instead. Accounts that only have a security key (WebAuthn) or Yubico OTP need an API token
- **Self-signed certificates**: when your node uses the certificate Proxmox created on install, ProxCtrl shows its SHA-256 fingerprint for you to compare (Datacenter › your node › System › Certificates, or `pvenode cert info`) and trusts only that exact certificate from then on. Nothing is sent to the server until you've confirmed it. Certificates your phone already trusts (for example from Let's Encrypt through Nginx Proxy Manager) just work
- **What this sign-in can do**: after connecting, ProxCtrl lists what your token or user is allowed to do (see guests, node status, storage, guest agent data, power actions, consoles, snapshots, backups, node shell, updates and services, node reboot) and the Proxmox privilege behind each. This list also appears on the Account page
- **Read-only mode** per server hides everything that changes something. It's switched on for you when the sign-in can only look; you can toggle it on the Account page or under Servers
- **Several servers**: add, switch, edit and remove servers under Account › Servers. Editing lets you change the address, re-enter the token or password, or trust a renewed certificate
- **Clear connection errors** on every tab, saying whether the server can't be reached, its certificate changed, the token or password was rejected, the session needs a new sign-in, or the address isn't a Proxmox server, with a button to retry or fix it
- The profile picture shows the first letter of the server you're using, and the Account page shows its name, Proxmox VE version and how you're signed in

### Security

- Token secrets, passwords and login sessions are encrypted with a key kept in the phone's secure hardware (Android Keystore) and never written anywhere in plain text
- A password is only saved when it can renew the session on its own. With two-factor sign-in it isn't stored at all; you sign in again when the session ends
- If a pinned certificate ever changes, ProxCtrl stops connecting to that server and asks you to check the new fingerprint, so a sign-in is never sent to a server pretending to be yours
- Redirects are never followed, so a token or session can't be passed to another server
- Removing a server deletes its saved token, password and session from the phone

## v0.1.0 (2026-10-19)

The first build of ProxCtrl: the app's shell, ready for the features that follow. It doesn't connect to a Proxmox server yet; server setup comes in the next version.

### New

- **Five tabs** in a floating glass bar at the bottom: Overview, Guests, Storage, Tasks and Node. Each one says what it will show once a server is connected. On tablets, unfolded foldables and in landscape the tabs move to a side rail
- **Account page** opened by tapping the profile picture at the top right of any tab (there is no separate "You" tab), with links to Settings, GitHub Sponsors, the source code and the licence
- **Settings** for the theme (follow system, dark, light or pure black) and dynamic colour from your wallpaper
- **App icon** in the Ctrl style: a server rack on a keycap, with a themed version for Android's themed icons

### Security

- Plain HTTP is switched off for the whole app; Proxmox is only reached over HTTPS
- Nothing the app stores is included in Android backups or moved to a new phone
