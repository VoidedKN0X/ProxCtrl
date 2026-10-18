# Changelog

What changed in each ProxCtrl release. Every release on the [Releases page](https://github.com/VoidedKN0X/ProxCtrl/releases) also lists the exact changes in that build.

To publish a new version: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a section here, and push to `main`. Pushes without a version bump only build; they don't make a release.

## v0.4.0 (2026-10-19)

Tapping a guest now opens its full page with live status, power controls, details, notes and graphs.

### New

- **Guest page header** with the status (running, stopped, paused or hibernated), the node it runs on, uptime, whether it's a container or VM, and its tags. Status refreshes every 3 seconds while the page is open
- **Power controls**: Start, Shut down, Reboot and Stop, and for VMs Pause, Resume and Hibernate in the ⋮ menu. Buttons that don't apply right now are greyed out (for example Start on a running guest, or anything on a locked guest). Every action asks for confirmation first; Stop is shown in red with a warning that it cuts power immediately. A message confirms the action started, or says why it failed, and the status updates within a few seconds. A hibernated VM shows as "Hibernated" and comes back with Start. The buttons are hidden in read-only mode and greyed out with a note when your sign-in isn't allowed to power guests
- **Usage** of CPU (with the number of cores), memory (used of total) and disk (used of total), with bars. VM disk use comes from the guest agent; without it the page says so instead of showing 0
- **Details**: IP addresses per network interface (from the container, or from the QEMU guest agent for VMs, leaving out loopback and link-local addresses), operating system (the agent's full name for VMs, the OS type for containers), hostname for containers, high-availability state, whether it starts at boot, and its start order with up and down delays
- **Notes** from the guest's Notes in Proxmox, shown with formatting: headings, bold, italic, code, lists and links. Tap **Edit notes** to change them (needs the `VM.Config.Options` privilege). If someone else changed the guest at the same time, ProxCtrl doesn't overwrite their change and tells you
- **Graphs** for CPU, memory, network in/out and disk read/write, for the last hour, day, week, month or year. Touch or drag across a graph to see the values at that moment; the legend always shows the latest values. The graphs refresh every minute while the page is open
- When the server can't be reached, the page keeps showing the last status with a note saying when it was last updated

### Changed

- The guest list's bulk actions stay Start, Shut down, Reboot and Stop; Pause, Resume and Hibernate are only on a VM's own page

## v0.3.0 (2026-10-19)

The Guests tab now lists all your containers and VMs.

### New

- **Guest list** with the same information as the Proxmox resource view: a container, VM or template icon with a status dot, ID and name, uptime, and CPU, memory and disk use as bars with percentages. Tags show as chips in the colours the Proxmox web UI uses, including any custom tag colours set under Datacenter › Options › Tag Style. Locked guests and HA states are shown under the name
- **Disk use for VMs**: Proxmox doesn't report disk use inside VMs, so ProxCtrl asks the QEMU guest agent for it about once a minute (VMs without a running agent show "—")
- **Disk warnings**: a guest's disk bar turns yellow from 80 % and red from 90 % full. The level is set under Settings › Warnings › Disk warning
- **Search** by name, ID or tag
- **Sort** by ID, name, CPU, memory, disk or uptime, lowest or highest first. Guests without a value (stopped guests for CPU and memory, VMs without guest-agent data for disk) always go last. Your choice is remembered
- **Filter** by type (containers, VMs, templates), status (running, stopped), tag and, in a cluster, node. A dot on the sort button shows when a filter is on, and the list says how many guests are shown
- **Compact view**: one line per guest with CPU, memory and disk percentages, for seeing all your guests at once. Switch with the button next to search; it's remembered
- **Bulk start, shut down, reboot and stop**: long-press a guest to start selecting, tap more, or select all. ProxCtrl lists the guests the action will run on and skips the ones it doesn't apply to (already running or stopped, locked, templates, or not allowed for your sign-in). Stop needs an extra tick because it cuts power immediately. A message at the bottom says how many succeeded and which failed. Selecting isn't offered in read-only mode or when your sign-in can't power guests on or off
- **Live updates**: the list refreshes every 5 seconds while it's on screen, stops when ProxCtrl is in the background, and slows down when the server can't be reached. Pull down to refresh by hand
- **Works offline**: the last list is kept on the phone, so it shows straight away when you open the app. When the server can't be reached the list stays visible with a note saying when it was last updated and why it couldn't refresh
- Tapping a guest opens its page, which for now shows its row; the full guest page comes next

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
