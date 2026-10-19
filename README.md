# ProxCtrl

**Manage your Proxmox VE server from your phone.**

[![Latest release](https://img.shields.io/github/v/release/VoidedKN0X/ProxCtrl?label=download)](https://github.com/VoidedKN0X/ProxCtrl/releases/latest) ![Android 13+](https://img.shields.io/badge/Android-13%2B-3DDC84?logo=android&logoColor=white) [![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE) [![Sponsor](https://img.shields.io/badge/sponsor-GitHub-ea4aaa?logo=githubsponsors&logoColor=white)](https://github.com/sponsors/VoidedKN0X)

ProxCtrl is an Android app for [Proxmox VE](https://www.proxmox.com/en/proxmox-virtual-environment) 8 and 9: a fast, touch-first alternative to the web UI for monitoring your node and managing your containers and VMs day to day.

Part of the [Ctrl series](#the-ctrl-series) by VKNXWorks.

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/VoidedKN0X/ProxCtrl"><img src=".github/assets/badge_obtainium.png" alt="Get it on Obtainium" height="80"></a>

> ProxCtrl is in early development. Features arrive release by release; see the [changelog](CHANGELOG.md) for what's in each version.

## Features

- **Overview** of your node, with an attention list for full disks, high memory, failed backups and more
- **Guests**: all your containers and VMs with CPU, memory and disk use, tags, search, sorting and bulk start/stop
- **Guest pages** with graphs, power controls, console, snapshots, backups and restore
- **Storage**: storages, backups, ISOs and templates, disks with SMART health, ZFS and LVM
- **Tasks** with live logs
- **Node**: services, updates, network and a shell
- **Notifications** without Google services: polling, or UnifiedPush through your own ntfy
- **Widgets** and a Quick Settings tile
- **Safe by design**: read-only mode, typed confirmations for dangerous actions, certificate pinning, optional fingerprint lock

## Requirements

- Android 13 or newer
- Proxmox VE 8 or 9

## Install

1. Download the latest `.apk` file from the [Releases page](https://github.com/VoidedKN0X/ProxCtrl/releases/latest).
2. Open it on your phone. Android may ask you to allow installing apps from your browser or file manager.
3. Updates install over the old version. Your settings and login are kept.

**Automatic updates:** install [Obtainium](https://github.com/ImranR98/Obtainium) first, then tap the button below. It adds ProxCtrl and installs every new release for you.

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/VoidedKN0X/ProxCtrl"><img src=".github/assets/badge_obtainium.png" alt="Get it on Obtainium" height="80"></a>

If the Obtainium button doesn't open the app, add `https://github.com/VoidedKN0X/ProxCtrl` in Obtainium by hand.

## Getting started

1. [Create an API token](#create-an-api-token-for-proxctrl) for ProxCtrl on your Proxmox node.
2. Open ProxCtrl and enter your node's address, like `192.168.1.10`. Without `https://` and a port, ProxCtrl uses port 8006. For a reverse proxy, enter the full address, like `https://pve.example.com`.
3. Choose **API token** and enter the token ID and secret, or choose **Password** to sign in with a Proxmox user (two-factor codes work too).
4. If your node uses the self-signed certificate Proxmox created on install, compare the fingerprint ProxCtrl shows with the one under Datacenter › your node › System › Certificates (or `pvenode cert info`), then tap **Trust**.
5. ProxCtrl shows what your sign-in is allowed to do. Tap **Continue**.

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
| `VM.Audit` | Seeing guests, their config, graphs, snapshots and firewall |
| `VM.GuestAgent.Audit` (PVE 9) / `VM.Monitor` (PVE 8) | VM IP addresses and disk use from the QEMU guest agent |
| `Sys.Audit` | Node status and graphs, services, disks, SMART, ZFS, LVM, other users' tasks, backup jobs |
| `Datastore.Audit` | Storages and their content |
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
| `VM.Config.Options` | Editing notes and tags |
| `VM.Config.Disk` | Growing a disk |
| `VM.Clone`, `VM.Allocate` | Cloning guests and templates |
| `Datastore.AllocateTemplate` | Uploading ISOs and downloading container templates |
| `Sys.PowerMgmt` | Rebooting and shutting down the node |
| `Sys.Modify` on `/nodes/<node>` | Seeing available updates and restarting services; see step 3 |

ProxCtrl checks what your token can do when it connects and greys out anything it isn't allowed to.

## Notifications

ProxCtrl doesn't use Google services for notifications. Turn them on under **Settings › Notifications**. There are two kinds, and you can use either or both:

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

**Push from Proxmox** delivers Proxmox's own notifications instantly through [UnifiedPush](https://unifiedpush.org/), using your self-hosted [ntfy](https://ntfy.sh/) (or another UnifiedPush app). Proxmox only sends notifications for backups, available updates, fencing, replication and system mail (which is how ZFS and SMART problems arrive). Everything else on the list above still comes from the background checks.

### Set up push with ntfy

1. Install the ntfy app (from F-Droid) and set its default server to your ntfy server under its Settings.
2. In ProxCtrl, turn on **Settings › Notifications › Receive Proxmox notifications**. If you have more than one UnifiedPush app, pick ntfy. ProxCtrl then shows its **push address**, like `https://ntfy.example.com/upAbC123?up=1`. Treat it like a password: anyone who has it can send you notifications.
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

Android can delay background checks to save battery, and ColorOS (OPPO, OnePlus, realme) closes background apps aggressively. Under **Settings › Notifications › Battery**, ProxCtrl shows whether it's restricted and opens its app info. There, set **Battery** to **Allow background activity** (or **Don't optimise**), and allow **Auto launch** if your phone offers it. Do the same for ntfy if you use push.

## Consoles and the node shell

- **Containers** open a terminal (xterm.js), **VMs** open their screen (noVNC), or a terminal when their display is set to a serial port, and the **node shell** opens from the Shell button on the node's card on the Overview.
- ProxCtrl opens the console connection itself, signed with your token or session and pinned to your server's certificate, and shows it full screen with an extra-keys bar: Esc, Tab, Ctrl and Alt (and Super for VMs) that stay pressed for the next key, arrows, Home, End, Page Up/Down, Delete, Ctrl+Alt+Del for VMs, and Paste. Rotate to landscape with the rotate button.
- Consoles need `VM.Console` on the guest, and the node shell needs `Sys.Console` on the node. They're hidden in read-only mode.
- With an API token, the node shell asks for a Linux login, because Proxmox only skips the login for `root@pam`. Sign in with `root@pam` and its password if you want a root shell straight away.

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

## The Ctrl series

Native Android apps for self-hosted services and everyday tools, all with the same Material You design.

| App | What it's for |
| --- | --- |
| [BeamCtrl](https://github.com/VoidedKN0X/BeamCtrl) | TV remote for phones with an IR blaster |
| [CraftyCtrl](https://github.com/VoidedKN0X/CraftyCtrl) | Manage Minecraft servers on Crafty Controller |
| [ElementCtrl](https://github.com/VoidedKN0X/ElementCtrl) | Matrix chat client for matrix.org and self-hosted Synapse |
| [FilesCtrl](https://github.com/VoidedKN0X/FilesCtrl) | File manager with NFS, SMB, SFTP, FTP and WebDAV |
| [FrigateCtrl](https://github.com/VoidedKN0X/FrigateCtrl) | Watch and review your Frigate NVR cameras |
| [ImmichCtrl](https://github.com/VoidedKN0X/ImmichCtrl) | Photos and backup for your Immich server |
| [JellyCtrl](https://github.com/VoidedKN0X/JellyCtrl) | Movies, shows, music and Live TV from Jellyfin |
| [NaviCtrl](https://github.com/VoidedKN0X/NaviCtrl) | Music player for Navidrome servers |
| **ProxCtrl** (this app) | Manage your Proxmox VE server |
| [SeerrCtrl](https://github.com/VoidedKN0X/SeerrCtrl) | Request movies and shows on Seerr / Jellyseerr |

## License

ProxCtrl is licensed under the [GNU General Public License v3.0](LICENSE). © VKNXWorks

The Manrope font is licensed under the SIL Open Font License; see `FONT-LICENSE-Manrope.txt`.

The consoles use [noVNC](https://github.com/novnc/noVNC) (MPL-2.0, with [pako](https://github.com/nodeca/pako) under MIT) and [xterm.js](https://github.com/xtermjs/xterm.js) (MIT), bundled unchanged in `app/src/main/assets` with their licence files.
