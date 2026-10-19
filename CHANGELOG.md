# Changelog

What changed in each ProxCtrl release. Every release on the [Releases page](https://github.com/VoidedKN0X/ProxCtrl/releases) also lists the exact changes in that build.

To publish a new version: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a section here, and push to `main`. Pushes without a version bump only build; they don't make a release.

## v0.7.1 (2026-10-19)

A fix for typing in consoles.

### Fixed

- **Typing in a console did nothing**: in the node shell, container consoles and VM screens, what you typed on the phone keyboard never reached the console, so commands like `uname -a` didn't appear or run. The phone keyboard now types into ProxCtrl itself, which sends every key to the console: letters, Backspace, Enter and keyboard symbols, in terminals and on VM screens alike
- Tapping a terminal brings up the keyboard, and it comes up by itself when a terminal connects. Dragging still scrolls back through the output
- The keyboard button shows whether the keyboard is open and opens or closes it
- Ctrl and Alt from the extra-keys bar now also work with letters typed on the phone keyboard (for example Ctrl then C stops a running command)
- Keys from a Bluetooth or USB keyboard (Enter, Backspace, Esc, Tab, arrows, Home, End, Page Up/Down, Delete) also reach the console
- Paste in terminals uses bracketed paste when the shell supports it, so pasted text isn't run line by line by accident

### Security

- The phone keyboard is told the console is a password field, so it doesn't suggest, autocorrect or learn what you type into consoles, such as passwords at a login prompt

## v0.7.0 (2026-10-19)

Consoles for containers and VMs, and a shell on the node.

### New

- **Open console** on a running guest's page. Containers open a terminal, VMs open their screen, and VMs whose display is set to a serial port open a terminal instead. The console fills the screen with the system bars hidden, and closes when you go back
- **Node shell** from the new Shell button on each node's card on the Overview. When you're signed in with an API token, a note explains that the node asks for a Linux login, because Proxmox only opens a root shell straight away for `root@pam`
- **Extra-keys bar** under every console: Esc, Tab, Ctrl and Alt (and Super on VMs) that stay pressed for the next key you type and then let go, the arrow keys, Home, End, Page Up, Page Down and Delete, Ctrl+Alt+Del on VMs, and Paste. Paste sends the clipboard into terminals as a normal paste and types it into a VM's screen. In terminals there are also `/`, `-`, `|` and `~` keys
- **Keyboard button** to bring up the phone's keyboard; on VM screens what you type is sent to the VM key by key, including Backspace and Enter
- **Rotate button** to switch the console to landscape and back, and text size buttons for terminals
- If a console can't connect or the connection ends, ProxCtrl says why and offers **Reconnect**
- The Console button and the Shell button only appear when your sign-in has `VM.Console` or `Sys.Console`, and both are hidden in read-only mode

### Security

- The console connection is made by ProxCtrl itself, signed with your token or session and accepted only from your server's pinned certificate, the same as every other request; the console page never sees your token
- The console pages are bundled in the app and can't navigate to or load anything else

## v0.6.0 (2026-10-19)

ProxCtrl can now notify you about problems, without Google services. Turn it on under **Settings › Notifications**.

### New

- **Background checks**: every 15, 30 or 60 minutes (only with a network connection), ProxCtrl checks every saved server and notifies you when something starts going wrong. You get one notification per problem rather than one per check. It's updated if the problem gets worse (for example a disk going from warning to critical) and removed by itself once the problem is gone. Tapping a notification about a guest opens that guest. The page shows when each server was last checked, and **Check now** runs a check straight away
- **What it notifies about**, each with its own switch:
  - A guest disk or storage over the disk warning level
  - Node memory over the memory warning level, or node CPU over a chosen level (default 90 %), for the whole of a chosen period (5 to 30 minutes, default 10). This is read from the node's per-minute graphs, so a short spike doesn't notify and a long interval doesn't miss a high period
  - A guest that stopped without a stop, shutdown, migration, backup or other task that explains it, so it probably crashed or shut itself down
  - Failed tasks (several at once are grouped) and failed backups or backups with warnings
  - A ZFS pool that isn't ONLINE, and a disk whose SMART health is FAILED
  - A server that two checks in a row couldn't reach, a cluster node that's offline, or a sign-in that stopped working
  - Available updates, at most once a day
- **Push from Proxmox** through UnifiedPush and your own ntfy (or another UnifiedPush app). ProxCtrl shows its push address and ready-made commands to run on your node. They create a Proxmox webhook target and matchers for failed backups, updates, fencing, replication and system mail (ZFS and SMART), and send a test notification. A push also triggers a background check. While push is on, backups and updates aren't notified twice. The README has the full setup, including how to do it in the Proxmox web UI
- **Four notification channels** (Critical problems, Warnings, Updates and info, From Proxmox), so you can choose sound and importance for each in Android's settings
- **Battery help**: the Notifications page shows whether battery optimisation can delay checks and opens ProxCtrl's app info, with instructions for ColorOS and similar phones
- **Settings** now opens with a Notifications entry; the Settings row on the Account page mentions notifications and warnings

### Security

- The push address's topic is put into a Proxmox webhook secret, which only root can read, instead of the webhook URL that shows in the Proxmox UI
- Pushes carry only the notification's title, severity, time and type, not the full report

## v0.5.0 (2026-10-19)

The Overview tab now shows what needs your attention and how each node is doing.

### New

- **Needs attention** list at the top of the Overview, most serious first, each with an icon that shows how serious it is (red error, yellow warning, grey info). It shows the first five, with **Show all** for the rest, or "All good" when nothing needs a look. It flags:
  - Guests whose disk is over the disk warning level (80 % by default), with how much is used
  - Nodes whose memory is over the memory warning level (85 % by default) or whose root disk is over the disk warning level
  - Nodes that are offline (in a cluster)
  - Stopped guests that are set to start at boot, so were probably meant to be running
  - Tasks that failed in the last 24 hours, with the latest one
  - A last backup that failed or finished with warnings
  - Guests in a backup job whose newest backup is older than the backup age limit (7 days by default), and guests that no backup job includes
  - Storage over the disk warning level, or storage that's unavailable
  - A subscription that has expired, is invalid or suspended (no subscription at all isn't flagged)
  - Available package updates, when your sign-in may see them
  - High-availability errors and quorum problems in a cluster
- Tap an item to open the guest it's about, or the Guests, Storage, Tasks or Node tab. Storage, Tasks and Node are still placeholders until their stages are built
- **Node card** for each node: online status, uptime, Proxmox VE version and kernel, CPU use with the number of CPUs, IO delay and load average, memory, swap and root disk with bars that turn yellow and red past the warning levels, and small graphs of CPU, memory and network in/out over the last hour with their latest values
- A line with how many guests are running, which opens the Guests tab
- **Memory warning** and **Backup age warning** settings under Settings › Warnings, next to the disk warning
- The Overview refreshes every 5 seconds while it's on screen (heavier checks like backups and updates run less often) and stops in the background. Pull down to refresh everything now. The last state is kept on the phone and shown with a note when the server can't be reached

## v0.4.1 (2026-10-19)

Other apps can now open a guest's page in ProxCtrl directly.

### New

- **Open a guest from another app**: a link like `proxctrl://guest/103` opens that guest's page on the server you're using. TrueCtrl uses it so that after shutting down a TrueNAS VM you can jump straight to the VM in ProxCtrl to start it again. If the guest isn't on that server, the Guests tab opens instead. A link only opens the page; it never starts, stops or changes anything

### Security

- Only apps can open these links, not web pages in a browser

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
