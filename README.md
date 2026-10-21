# ProxCtrl

**Manage your Proxmox VE server from your phone.**

[![Latest release](https://img.shields.io/github/v/release/VoidedKN0X/ProxCtrl?label=download)](https://github.com/VoidedKN0X/ProxCtrl/releases/latest) ![Android 13+](https://img.shields.io/badge/Android-13%2B-3DDC84?logo=android&logoColor=white) [![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE) ![Telemetry-free](https://img.shields.io/badge/telemetry-none-brightgreen) [![Sponsor](https://img.shields.io/badge/sponsor-GitHub-ea4aaa?logo=githubsponsors&logoColor=white)](https://github.com/sponsors/VoidedKN0X)

ProxCtrl is an Android app for [Proxmox VE](https://www.proxmox.com/en/proxmox-virtual-environment) 8 and 9: a fast, touch-first alternative to the web UI for monitoring your node and managing your containers and VMs day to day.

Part of the [Ctrl series](#the-ctrl-series) by VKNXWorks.

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/VoidedKN0X/ProxCtrl"><img src=".github/assets/badge_obtainium.png" alt="Get it on Obtainium" height="80"></a> <a href="https://voidedkn0x.github.io/CtrlF-Droid/"><img src=".github/assets/badge_ctrlf-droid.png" alt="Get it from our F-Droid repo" height="80"></a>

## Features

- **Overview** of your node, with live CPU, memory and network graphs and an attention list for full disks, high memory, failed backups and more
- **Guests**: all your containers and VMs with CPU, memory and disk use, tags, search, sorting, a compact view and bulk start/stop
- **Guest pages** with live status, graphs, power controls, IP addresses, notes, console, snapshots, backups and restore
- **Node shell** from the node's card on the Overview
- **Notifications**: background checks, plus instant push from Proxmox through [ntfy](https://f-droid.org/packages/io.heckel.ntfy/) and your own ntfy server
- **Storage**: storages and their content, backup deletion, ISO upload (also from the share menu), container templates, disks with SMART health, ZFS pools, LVM and backup jobs
- **Tasks** with live logs, history filters and Stop
- **Node**: status and graphs, services, updates with changelogs, network and SDN, reboot and shutdown, Wake-on-LAN
- **Widgets** for the node and pinned guests, and a Quick Settings tile
- **App lock** with its own PIN and optional fingerprint, on opening and before changes; your server logins can be encrypted with the PIN while locked
- **Several servers**, signed in with an API token or a password (two-factor codes work too), with a list of what each sign-in is allowed to do
- **Works offline**: the last state stays on screen, marked as out of date, when the server can't be reached
- **Open a guest from other apps** with a `proxctrl://guest/<id>` link (TrueCtrl uses it); a link never changes anything
- **Safe by design**: read-only mode that blocks every change at the connection level, confirmation before bulk actions, the guest ID typed in before rolling back or deleting a snapshot, restoring a backup, growing a disk or making a template, the node name typed in before a reboot or shutdown, an optional app lock with its own PIN, certificate pinning, **Hide in screenshots** and **Hide in recent apps** switches, sign-in and consoles always kept out of screenshots, and token secrets and passwords encrypted with the Android Keystore

## Screenshots

Coming soon.

## Requirements

- Android 13 or newer, on a 64-bit ARM phone (nearly every phone from the last several years)
- Proxmox VE 8 or 9
- **Google-free and de-Googled:** no Google Play services, Firebase or other Google services, and the app never contacts Google. It works the same on de-Googled phones (GrapheneOS, CalyxOS, /e/OS, LineageOS without Google apps).

## Install

1. Download the latest `.apk` file from the [Releases page](https://github.com/VoidedKN0X/ProxCtrl/releases/latest).
2. Open it on your phone. Android may ask you to allow installing apps from your browser or file manager.
3. Updates install over the old version. Your settings and login are kept.

**Automatic updates:** on your phone, tap one of the buttons below.

- **Obtainium:** install [Obtainium](https://github.com/ImranR98/Obtainium) first. The button adds ProxCtrl and installs every new release for you.
- **F-Droid:** adds the [CtrlF-Droid repo](https://voidedkn0x.github.io/CtrlF-Droid/) to F-Droid, Droid-ify or Neo Store, with every Ctrl app and automatic updates.

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/VoidedKN0X/ProxCtrl"><img src=".github/assets/badge_obtainium.png" alt="Get it on Obtainium" height="80"></a> <a href="https://voidedkn0x.github.io/CtrlF-Droid/"><img src=".github/assets/badge_ctrlf-droid.png" alt="Get it from our F-Droid repo" height="80"></a>

If the Obtainium button doesn't open the app, add `https://github.com/VoidedKN0X/ProxCtrl` in Obtainium by hand.

## Getting started

1. [Create an API token](#create-an-api-token-for-proxctrl) for ProxCtrl on your Proxmox node.
2. Open ProxCtrl and enter your node's address, like `192.168.1.10`. Without `https://` and a port, ProxCtrl uses port 8006. For a reverse proxy, enter the full address, like `https://pve.example.com`.
3. Choose **API token** and enter the token ID and secret, or choose **Password** to sign in with a Proxmox user (two-factor codes work too).
4. If your node uses the self-signed certificate Proxmox created on install, compare the fingerprint ProxCtrl shows with the one under Datacenter › your node › System › Certificates (or `pvenode cert info`), then tap **Trust**.
5. ProxCtrl shows what your sign-in is allowed to do. Tap **Continue**.

Your servers, read-only mode, **Settings** and **About** are on the **You** page: tap your profile picture at the top right of any tab. Every path below that starts with **Settings** is there.

## Create an API token for ProxCtrl

ProxCtrl works best with its own Proxmox user and an API token that can only do what the app needs. Run these on your Proxmox node as root.

**1. Create the roles.** The read-only role is enough for monitoring. The operator role adds power actions, the console, snapshots and backups.

On **Proxmox VE 9**:

```bash
pveum role add ProxCtrl.Audit --privs "VM.Audit VM.GuestAgent.Audit Sys.Audit Datastore.Audit SDN.Audit"
pveum role add ProxCtrl.Operator --privs "VM.Audit VM.GuestAgent.Audit Sys.Audit Datastore.Audit SDN.Audit VM.PowerMgmt VM.Console VM.Snapshot VM.Snapshot.Rollback VM.Backup Datastore.AllocateSpace Sys.Console"
```

On **Proxmox VE 8**, use `VM.Monitor` instead of `VM.GuestAgent.Audit` (Proxmox VE 9 replaced it):

```bash
pveum role add ProxCtrl.Audit --privs "VM.Audit VM.Monitor Sys.Audit Datastore.Audit SDN.Audit"
pveum role add ProxCtrl.Operator --privs "VM.Audit VM.Monitor Sys.Audit Datastore.Audit SDN.Audit VM.PowerMgmt VM.Console VM.Snapshot VM.Snapshot.Rollback VM.Backup Datastore.AllocateSpace Sys.Console"
```

**2. Create the user and its token.** Pick one role and use it in both `acl` lines:

```bash
pveum user add proxctrl@pve --comment "ProxCtrl app"
pveum user token add proxctrl@pve phone --privsep 1 --comment "ProxCtrl on my phone"
pveum acl modify / --users proxctrl@pve --roles ProxCtrl.Operator
pveum acl modify / --tokens 'proxctrl@pve!phone' --roles ProxCtrl.Operator
```

The second command prints the token secret **once**. In ProxCtrl, enter the token ID `proxctrl@pve!phone` and that secret. With `--privsep 1` the token can never do more than the user, so it needs the role on both.

**3. Optional: updates and services.** Proxmox only shows available updates to users with `Sys.Modify`, which also allows restarting services and changing node settings such as DNS and network interfaces. To allow it on one node only (here `VKLab`), not the whole datacenter, give it as a separate role on that node's path:

```bash
pveum role add ProxCtrl.Node --privs "Sys.Modify"
pveum acl modify /nodes/VKLab --users proxctrl@pve --roles ProxCtrl.Node
pveum acl modify /nodes/VKLab --tokens 'proxctrl@pve!phone' --roles ProxCtrl.Node
```

Even with this the token can't install updates (you run the upgrade in the node shell yourself) and can't touch disks, ZFS or LVM, which need permissions on `/`. If you skip it, Proxmox can still tell you about new updates through its own notifications.

**What each privilege is for:**

| Privilege | Used for |
| --- | --- |
| `VM.Audit` | Seeing guests, their config, graphs, snapshots, options and firewall |
| `VM.GuestAgent.Audit` (PVE 9) / `VM.Monitor` (PVE 8) | VM IP addresses and disk use from the QEMU guest agent |
| `Sys.Audit` | Node status and graphs, services, network, disks, SMART, ZFS, LVM, backup jobs, other users' tasks, guests no backup job includes |
| `Datastore.Audit` | Storages and their content, such as backups |
| `SDN.Audit` | SDN zones and VNets |
| `VM.PowerMgmt` | Start, shutdown, reboot, stop, suspend and resume |
| `VM.Console` | Console for containers and VMs |
| `VM.Snapshot`, `VM.Snapshot.Rollback` | Take, roll back and delete snapshots |
| `VM.Backup`, `Datastore.AllocateSpace` | Back up now, restore, delete backups |
| `Sys.Console` | Node shell (you log in with a Linux user on the node; only `root@pam` gets a shell without a login) |

**Optional extras** for the operator role, only if you want those actions in the app:

| Privilege | Adds |
| --- | --- |
| `VM.Config.CPU`, `VM.Config.Memory` | Changing cores, memory and swap |
| `VM.Config.Options` | Editing a guest's notes and tags |
| `VM.Config.Disk` | Growing a disk |
| `VM.Clone`, `VM.Allocate` | Cloning guests, cloning templates and converting a guest to a template |
| `Datastore.AllocateTemplate` | Uploading ISOs and downloading container templates |
| `Datastore.Allocate` | Deleting backups without `VM.Backup` on the guest |
| `Sys.PowerMgmt` | Rebooting and shutting down the node |
| `Sys.Modify` on `/nodes/<node>` | Seeing available updates, refreshing the package list and starting, stopping or restarting services; see step 3 |

To add all of them to the operator role at once (on the node, as root):

```bash
pveum role modify ProxCtrl.Operator --append 1 --privs "VM.Config.CPU VM.Config.Memory VM.Config.Options VM.Config.Disk VM.Clone VM.Allocate Datastore.AllocateTemplate Sys.PowerMgmt"
```

ProxCtrl checks what your token can do when it connects and greys out anything it isn't allowed to.

## Notifications

Turn notifications on under **Settings › Notifications**. There are two kinds, and you can use either or both:

**Background checks** look at every saved server every 15, 30 or 60 minutes and notify you when something starts going wrong. You get one notification per problem, not one every check. The notification goes away when the problem does.

| Event | When |
| --- | --- |
| Guest disk almost full | Over the disk warning level (VM disks need the QEMU guest agent) |
| Node memory high | Over the memory warning level for the whole chosen period (default 10 minutes) |
| Node CPU high | Over the CPU level (default 90 %) for the whole chosen period |
| Guest stopped unexpectedly | A running guest stopped without a stop, shutdown, migration or backup task |
| Task failed | Any task that ended with an error |
| Backup failed | A backup that failed or had warnings |
| Storage almost full | Over the disk warning level |
| ZFS pool degraded | A pool that isn't ONLINE |
| Disk SMART failure | A disk whose SMART health is FAILED |
| Server unreachable | Two checks in a row couldn't connect, a cluster node is offline, or the sign-in stopped working |
| Updates available | At most once a day |

CPU and memory are judged from the node's per-minute graphs, so a long interval reports a high period later but doesn't miss it. Each event can be switched off. ZFS and SMART need `Sys.Audit` on `/`, and updates need the optional `ProxCtrl.Node` role from step 3 above.

**Push from Proxmox** delivers Proxmox's own notifications instantly through the [ntfy app](https://f-droid.org/packages/io.heckel.ntfy/) and your self-hosted ntfy server. Push comes through ntfy only (no other push apps), and the background checks keep running alongside as a fallback. Proxmox only sends notifications for backups, available updates, fencing, replication and system mail (which is how ZFS and SMART problems arrive). Everything else on the list above still comes from the background checks.

### Set up push with ntfy

1. Install the [ntfy app](https://f-droid.org/packages/io.heckel.ntfy/) and set its default server to your ntfy server under its Settings. If ntfy isn't installed, **Settings › Notifications** says so and has an **Install ntfy** button.
2. In ProxCtrl, turn on **Settings › Notifications › Receive Proxmox notifications**. ProxCtrl registers with ntfy and shows its **push address**, like `https://ntfy.example.com/upAbC123?up=1`. Treat it like a password: anyone who has it can send you notifications.
3. Tap **Copy commands** under **Set up Proxmox** and run them in your node's shell as root. They create:
   - A **webhook target** called `proxctrl` that posts a small JSON message to your push address. The topic part of the address is stored as a webhook secret, which only root can read, so it doesn't show in the Proxmox UI. Only the title and type are sent, not the full report, so long backup logs never go over ntfy's size limit.
   - Four **matchers** that send notifications to it:

     | Matcher | Rules | For |
     | --- | --- | --- |
     | `proxctrl-backups` | `match-field exact:type=vzdump`, `match-severity error` | Failed backups |
     | `proxctrl-cluster` | `match-field exact:type=fencing,replication` | Fenced nodes, failed replication |
     | `proxctrl-updates` | `match-field exact:type=package-updates` | Available updates |
     | `proxctrl-system-mail` | `match-field exact:type=system-mail` | ZFS (zed) and SMART (smartd) mails |

   - A **test notification**, which should appear on your phone within seconds.
4. To set it up in the web UI instead, go to **Datacenter › Notifications**:
   1. Add a **Webhook** target: method POST, URL `https://ntfy.example.com/{{ secrets.path }}?up=1`, header `Content-Type: application/json`, and a secret `path` holding the topic (the `upAbC123` part).
   2. Use this body:
      ```
      {"title":{{ json title }},"severity":{{ json severity }},"timestamp":{{ json timestamp }},"fields":{{ json fields }}}
      ```
   3. Add the four matchers from the table, each targeting it.

Proxmox has no notification for failed tasks in general, so ProxCtrl can't get those by push; the background check covers them. While push is on, the background check doesn't notify about backups and updates a second time.

### Keep checks running on your phone

Android can delay background checks to save battery, and ColorOS (OPPO, OnePlus, realme) closes background apps aggressively. Under **Settings › Notifications › Battery**, ProxCtrl shows whether it's restricted and opens its app info. There, set **Battery** to **Allow background activity** (or **Don't optimise**), and allow **Auto launch** if your phone offers it. Do the same for ntfy if you use push; ProxCtrl warns under **Battery** when ntfy is battery-restricted.

## Snapshots and backups

- **Snapshots** open from a guest's page. They're shown as a tree, oldest first, with **Now** where the guest currently is. Take a snapshot with a name, a description and, for running VMs, the RAM. Rolling back and deleting ask you to type the guest ID.
- **Backups** lists this guest's backups on every backup storage of its node (including Proxmox Backup Server), newest first, with size, notes, protection and verification state.
- **Back up now** picks a storage, the mode (snapshot, suspend or stop), the compression (not for Proxmox Backup Server), notes and protection. It never prunes older backups.
- **Restore** replaces the guest with the backup. It only works while the guest is stopped and not protected, shows a clear warning and asks you to type the guest ID. Containers are restored to the storage their root disk is on now, unless you pick another; VMs keep the storages from the backup unless you pick one.
- Every action runs as a Proxmox task: the screen shows its progress and result, with the end of the task log if it fails.
- You need `VM.Snapshot` (or `VM.Snapshot.Rollback` for rolling back only) for snapshots, and `VM.Backup` plus `Datastore.AllocateSpace` on the storage for backups, restores and deleting backups.

## Guest resources, options and clones

- A guest's page shows its **resources**: cores (and sockets and CPU type for VMs), memory with ballooning or swap, every disk with its storage and size, and every network device with its bridge, VLAN, MAC, addresses and firewall flag.
- **Edit cores and memory** (and swap for containers). A running VM may only take some changes after its next restart; Proxmox keeps those as pending.
- **Edit tags**, with the same colours as in the web interface.
- **Grow a disk**: only ever bigger, never smaller, and only after typing the guest ID. The partition and file system inside the guest may need growing too.
- **Options and firewall**: the guest's options, firewall settings and firewall rules, read-only.
- **Task history** for the guest.
- **Clone** a guest or template: the next free ID is suggested, with a name, a full or (for templates) linked clone, and where the copy's disks go. **Convert to template** asks for the guest ID first; the guest must be stopped.

## Storage

- The **Storage** tab lists every storage of your node with its type, usage bar, content types, and whether it's enabled, shared and available. Tap one to see what's on it.
- A storage's page shows its backups, ISO images, container templates and disk images, filterable by type, with date, size, guest, notes, protection and verification state. **Backups can be deleted** after a confirmation (protected ones can't).
- **Upload an ISO** from the storage's page with the file picker, or from any app with **Share → ProxCtrl** (for example straight from your browser's downloads). Pick the storage, check the file name (it must end in .iso or .img) and follow the progress. The file goes straight from the phone to Proxmox.
- **Download container templates** from Proxmox's own template list (Debian, Ubuntu, Alpine, TurnKey…), with search; templates already on the storage are marked.
- **Disks**: every physical disk with model, size, type, S.M.A.R.T. health and SSD wearout, and the full S.M.A.R.T. values on tap. Also **ZFS pools** (health, usage, fragmentation, last scrub, errors and every device with its error counters) and **LVM / LVM-thin** usage.
- **Backup jobs**: each scheduled job with its guests, storage, mode, schedule, next run and last run result, and **Run now**. Proxmox doesn't record which job made a backup, so the last run is the newest backup task for the same guests and storage.
- Privileges: `Datastore.Audit` to see storages and their content, `Sys.Audit` for disks, ZFS, LVM and backup jobs, `Datastore.AllocateTemplate` to upload ISOs and download templates, `Datastore.Allocate` (or `Datastore.AllocateSpace` plus `VM.Backup` on the guest) to delete backups, and `VM.Backup` plus `Datastore.AllocateSpace` for Run now.

## Tasks

- The **Tasks** tab shows what's running on your nodes right now, with the latest log line, a progress bar when the log reports a percentage (backups, restores, disk moves), and **Stop**.
- Below it is the **history**, newest first, filterable by status (OK, warnings, failed), type (backup, start, shutdown, snapshot…), user and guest. Load more goes further back.
- Tap a task for its details (node, who started it, when, how long it took, how it ended) and its **full log**, which follows new lines while the task runs. Copy the log, or turn line wrapping off to read wide output.
- Each guest's page has its own task history.
- You always see your own tasks. `Sys.Audit` on the node shows everyone's; stopping someone else's task needs `Sys.Modify` on the node.

## Node

- The **Node** tab shows the node's health (CPU, IO delay, load, RAM, swap, root disk), its hardware and versions (CPU model, sockets and CPUs, RAM, kernel, Proxmox VE version, EFI or BIOS boot and Secure Boot), and CPU, load, memory and network graphs for the last hour, day, week, month or year. With more than one node, chips at the top pick which one.
- **Services** lists the Proxmox services (pveproxy, pvedaemon, corosync, pve-cluster and the others) with their state. Start, stop and restart are each confirmed; ProxCtrl warns when a service it connects through could cut it off. Proxmox doesn't allow stopping pveproxy, pvedaemon or pve-cluster, so only restarting is offered for them.
- **Updates** lists the available package updates with the installed and new version; tap one for its changelog. **Refresh** updates the package database. ProxCtrl also tells you when a newer kernel is installed than the one running, so a reboot is due. Installing updates is up to you in the node shell (`apt dist-upgrade`).
- **Network** shows the node's interfaces, bridges, bonds and VLANs with their addresses, and the cluster's SDN zones and VNets. Read-only.
- **Reboot** and **Shut down** the node after typing its name (for example `VKLab`).
- **Wake-on-LAN**: enter the node's MAC address (and, if needed, your network's broadcast address and port) from the menu on the Node tab, and ProxCtrl can turn the node back on. When the node can't be reached, the Node tab offers to wake it. The packet is sent from your phone, so your phone has to be on the same network as the node, and Wake-on-LAN must be turned on in the node's firmware and network card. ProxCtrl never looks at your Wi-Fi name or location.
- Services, updates and changelogs need `Sys.Modify` on the node (see step 3 of the API token setup), and reboot and shutdown need `Sys.PowerMgmt`.

## Widgets and Quick Settings tile

- **Node status widget**: the node's health (all good, things to look at, or offline), CPU, RAM, root disk and uptime, and when it was last updated. Tap it to open the Overview.
- **Pinned guests widget**: pick the guests it shows when you add it (tap its title to change them). Each shows a status dot and a **Start** or **Shut down** button. The buttons never act from the home screen: they open ProxCtrl and ask you to confirm first.
- Widgets show the active server. They refresh about every 15 minutes (Android's minimum, with a network connection) and whenever you open ProxCtrl. When the server can't be reached they keep the last known state, marked as not updated. With the app lock on and *Background checks while locked* off, they only say "ProxCtrl is locked" until you unlock it.
- **Quick Settings tile**: the node's name, health and CPU / RAM. It's lit when all is good. Tap it to open the Overview. Add it from the Quick Settings editor.

## App lock

Turn it on under **Settings › Privacy and security › App lock** (tap your profile picture, then **Settings**). It's off until you turn it on.

- **Its own app PIN** of at least 6 digits, separate from your phone's screen lock. Very obvious PINs such as 111111 or 123456 are refused.
- **Unlock with fingerprint** (optional) only accepts strong biometrics, such as the fingerprint sensor, checked by the phone's secure hardware. Face unlock that only uses the camera isn't accepted. The PIN is still needed after the phone restarts and at least every 72 hours, and adding a new fingerprint to the phone turns fingerprint unlock off until you turn it on again.
- **Your server logins can be encrypted with the PIN while ProxCtrl is locked** (see *Background checks while locked* below). The key only exists in memory while the app is unlocked and is wiped the moment it locks. The PIN itself is never stored: it's stretched with 600,000 rounds of PBKDF2 and mixed with a key held in the phone's secure hardware, so a copy of the app's files isn't enough to guess it on a computer.
- **Wrong PINs:** after 5 tries you wait 30 seconds, doubling up to 15 minutes. Restarting the app or the phone, or changing the clock, doesn't skip the wait.
- **Lock again:** as soon as you leave the app (the default), after 1, 5 or 15 minutes or 1 hour away, or only when the app restarts. Picking a file, opening a link in your browser or Android's settings don't count as leaving, unless you stay away for more than 5 minutes.
- **Change app PIN** asks for your current PIN first, and turning the app lock off asks for the PIN too.
- **Confirm changes** (ProxCtrl only) asks for your fingerprint or PIN before anything that changes a server: power actions, snapshots, backups, consoles and edits. One confirmation covers the next minute.
- **Background checks while locked:** on, notifications, widgets and the Quick Settings tile keep checking your servers while ProxCtrl is locked, so your logins are only encrypted with the Android Keystore (as without the app lock). Off (most secure), your logins are also encrypted with the PIN, and background checks wait until you unlock; while ProxCtrl is locked, the widgets and the tile only say "ProxCtrl is locked" instead of showing your guests or the node's last known state. It starts on when you already use notifications or widgets, so turning the lock on never silently stops them.
- **Forgot your PIN?** It can't be recovered. The lock screen offers to remove every server from this phone and turn the app lock off; nothing changes on your Proxmox servers.
- **Sharing an ISO to ProxCtrl** asks for the PIN first, and the lock screen and every PIN screen are always kept out of screenshots.

Two more switches in the same section work with or without the app lock, and are both off until you turn them on:

- **Hide in screenshots**: screenshots and screen recordings show a blank screen (and so does the recent-apps view).
- **Hide in recent apps**: the recent-apps view shows a blank card instead of ProxCtrl. Screenshots still work.

Signing in to a server, consoles, the node shell and the lock and PIN screens are always kept out of screenshots and recordings, whatever these switches say. With the app lock on, the recent-apps view always shows a blank card.

## Consoles and the node shell

- **Containers** open a terminal (xterm.js), **VMs** open their screen (noVNC), or a terminal when their display is set to a serial port, and the **node shell** opens from the Shell button on the node's card on the Overview.
- ProxCtrl opens the console connection itself, signed with your token or session and pinned to your server's certificate, and shows it full screen with an extra-keys bar: Esc, Tab, Ctrl and Alt (and Super for VMs) that stay pressed for the next key, arrows, Home, End, Page Up/Down, Delete, Ctrl+Alt+Del for VMs, and Paste. Rotate to landscape with the rotate button.
- Consoles need `VM.Console` on the guest, and the node shell needs `Sys.Console` on the node. They're hidden in read-only mode.
- With an API token, the node shell asks for a Linux login, because Proxmox only skips the login for `root@pam`. Sign in with `root@pam` and its password if you want a root shell straight away.
- If a console doesn't behave, tap the bug icon at the top for a live diagnostics readout (connection, data sent and received, keyboard, page errors) and copy it into an issue. It never includes what you typed.

## Privacy

**Telemetry-free.** ProxCtrl has no analytics, crash reporting, usage stats or tracking of any kind. It only talks to the servers you add.

- **Servers:** your own Proxmox servers. Push notifications come through the ntfy app and your own ntfy server. The consoles are bundled in the app and can't load anything from the internet, and links in a guest's notes open in your browser only when you tap them.
- **Consoles:** the built-in web view for the consoles and node shell has Safe Browsing and WebView usage reports turned off, so page addresses never leave the phone.
- **Wake-on-LAN** sends its packet only on your own network, to the node's MAC address and the broadcast address you entered.
- **Links:** the Support, Report a bug, Source code and Licence rows in About open in your browser; the app itself doesn't contact GitHub.
- **Notifications** on the lock screen hide guest and node details when you hide sensitive notification content in Android's settings.
- **No backups:** Android doesn't back up ProxCtrl's data or move it to a new phone.

## Build from source

You need [Android Studio](https://developer.android.com/studio), or JDK 17 and the Android SDK.

1. Clone the repo:
   ```bash
   git clone https://github.com/VoidedKN0X/ProxCtrl.git
   cd ProxCtrl
   ```
2. Build the app:
   ```bash
   ./gradlew assembleDebug
   ```
   On Windows, use `gradlew.bat assembleDebug`.
3. The APK is in `app/build/outputs/apk/debug/`. Copy it to your phone and install it.

Or open the folder in Android Studio and press **Run** with your phone connected.

## Support the project

ProxCtrl is free and open source. If it's useful to you, you can support development on [GitHub Sponsors](https://github.com/sponsors/VoidedKN0X). Starring the repo and [reporting bugs](https://github.com/VoidedKN0X/ProxCtrl/issues) helps too.

In the app, the **About** section at the bottom of the **You** page (tap your profile picture) has **Support ProxCtrl** and **Report a bug** rows that open these pages.

## The Ctrl series

Native Android apps for self-hosted services and everyday tools, all with the same Material You design.

| App | What it's for |
| --- | --- |
| [AdCtrl](https://github.com/VoidedKN0X/AdCtrl) | Control your AdGuard Home DNS server |
| [ArrCtrl](https://github.com/VoidedKN0X/ArrCtrl) | Radarr, Sonarr, Prowlarr, Bazarr, qBittorrent, autobrr and Cleanuparr in one app |
| [BeamCtrl](https://github.com/VoidedKN0X/BeamCtrl) | TV remote for phones with an IR blaster |
| [CraftyCtrl](https://github.com/VoidedKN0X/CraftyCtrl) | Manage Minecraft servers on Crafty Controller |
| [ElementCtrl](https://github.com/VoidedKN0X/ElementCtrl) | Matrix chat client for matrix.org and self-hosted Synapse |
| [FilesCtrl](https://github.com/VoidedKN0X/FilesCtrl) | File manager with NFS, SMB, SFTP, FTP and WebDAV |
| [FrigateCtrl](https://github.com/VoidedKN0X/FrigateCtrl) | Watch and review your Frigate NVR cameras |
| [ImmichCtrl](https://github.com/VoidedKN0X/ImmichCtrl) | Photos and backup for your Immich server |
| [JellyCtrl](https://github.com/VoidedKN0X/JellyCtrl) | Movies, shows, music and Live TV from Jellyfin |
| [MemoCtrl](https://github.com/VoidedKN0X/MemoCtrl) | Notes on your Memos server |
| [NaviCtrl](https://github.com/VoidedKN0X/NaviCtrl) | Music player for Navidrome servers |
| [PaperCtrl](https://github.com/VoidedKN0X/PaperCtrl) | Scan and browse documents on Paperless-ngx |
| [PrintCtrl](https://github.com/VoidedKN0X/PrintCtrl) | Bambu Lab printers through Bambuddy, and Spoolman spools |
| **ProxCtrl** (this app) | Manage your Proxmox VE server |
| [SeerrCtrl](https://github.com/VoidedKN0X/SeerrCtrl) | Request movies and shows on Seerr / Jellyseerr |
| [TrueCtrl](https://github.com/VoidedKN0X/TrueCtrl) | Monitor and manage your TrueNAS server |

## License

ProxCtrl is licensed under the [GNU General Public License v3.0](LICENSE). © VKNXWorks

The Manrope font is licensed under the SIL Open Font License; see `FONT-LICENSE-Manrope.txt`.

The consoles use [noVNC](https://github.com/novnc/noVNC) (MPL-2.0, with [pako](https://github.com/nodeca/pako) under MIT) and [xterm.js](https://github.com/xtermjs/xterm.js) (MIT), bundled unchanged in `app/src/main/assets` with their licence files.
