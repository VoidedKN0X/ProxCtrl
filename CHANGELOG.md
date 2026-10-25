# Changelog

What changed in each ProxCtrl release. Every release on the [Releases page](https://github.com/VoidedKN0X/ProxCtrl/releases) also lists the exact changes in that build.

To publish a new version: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a section here, and push to `main`. Pushes without a version bump only build; they don't make a release.

## v1.4.1 (2026-10-25)

Crash fixes for closing and leaving the app, safer dialogs with the app lock, and fixes for creating containers and moving disks.

### Fixed

- **No crash when closing ProxCtrl with the app lock on.** With **Lock again** set to "As soon as you leave", pressing Back on the first screen or swiping ProxCtrl away from recent apps could crash it, because the lock screen (or a dialog) tried to open on a screen that was already closing. The app still locks; it just doesn't try to show anything while it closes, and opens locked next time.
- **The fingerprint prompt can't crash ProxCtrl.** If Android refuses to show the fingerprint prompt (for example while you're leaving the app), the lock screen and Settings now show the usual error message instead of crashing, and the PIN keeps working.
- **Dialogs no longer stay on top of the lock screen.** A dialog, menu or bottom sheet that was open when ProxCtrl locked (or when a PIN or **Confirm changes** screen came up) used to stay on top of the lock screen, where it could still be tapped. It now waits behind the lock and comes back, as it was, after you unlock.
- **A console is hidden while ProxCtrl is locked.** An open console or node shell is now hidden behind the lock screen and the PIN screens too, and shows again after unlocking.
- **Odd answers from a server or a proxy are an error message, not a crash.** A connection that breaks while an answer is being read, or an answer that isn't what Proxmox normally sends (for example from a reverse proxy), now shows the usual error card everywhere, including at sign-in.
- **Background work can't crash the app.** A failure in work that runs on its own (refreshing the widgets and the tile, saving a setting, handling a push from ntfy, starting up) is now ignored until the next try instead of closing ProxCtrl, also while it's closing.
- **Links from other apps can't crash ProxCtrl.** A link, widget button or shared file from another app whose contents can't be read is now ignored.
- **New container: the root password isn't lost silently.** If Android closed ProxCtrl in the background while you were in the container wizard, the password you typed (which is never saved) was gone but the wizard still let you create the container without it. The wizard now goes back to **Login** to ask for it again, and **Create** stays off until the login step is complete.
- **"ID already in use" only when it really is.** In the create wizards and the clone dialog, a network hiccup (or typing the ID quickly) could flag a free ID as already in use. Now only Proxmox's own answer that the ID is taken does.
- **Moving a TPM disk.** Proxmox can't move a VM's TPM state while the VM is running, so its **Move** button now only shows while the VM is stopped. The format choice no longer shows for TPM state either, because it's always raw.

### Security

- **Push from ntfy is checked more strictly.** Nothing is accepted before ntfy has given ProxCtrl a push address, and the secret token in every push is compared in a way that doesn't leak how much of it was right.
- **One unneeded component removed.** WorkManager's diagnostics receiver (only useful for developer dumps over USB) is no longer part of the app.

### Changed

- Internal cleanup: removed 19 unused texts.

## v1.4.0 (2026-10-25)

ProxCtrl can now **create containers and VMs** and **move disks between storages**.

### New

- **Create button on Guests**: a violet + button at the bottom right of the Guests tab (hidden in read-only mode and while selecting guests) opens **Create**, with three choices: **New container**, **New VM**, and **From a template**. If your sign-in lacks `VM.Allocate`, the page says so and the wizards stay closed.
- **New container wizard** (eight steps, with Back and Next; the system back gesture goes to the previous step):
  - **Template**: pick a template storage, then a container template on it. **Download templates** opens the existing template list for that storage (needs `Datastore.AllocateTemplate`); come back and tap **Refresh** to see it. What you entered so far is kept.
  - **General**: the ID (the next free one is filled in; an ID in use is flagged), the hostname, **Unprivileged container** (on by default) and **Nesting** (on by default; needed by modern systemd and Docker). Turning unprivileged off explains that it needs `Sys.Modify` on `/` and turns nesting off, because only root@pam can set features on privileged containers.
  - **Login**: a root password (typed twice, at least 5 characters), SSH public keys (one per line), or both. The password is sent to Proxmox once and never saved, and the whole wizard is kept out of screenshots and the recent-apps preview.
  - **Disk**: the storage for the root disk (only storages that can hold containers; ones your sign-in can't allocate on are greyed out) and its size in GiB.
  - **CPU and memory**: cores, memory with quick picks, and swap.
  - **Network**: the bridge (picked from the node's bridges, or typed when the sign-in can't list them), an optional VLAN tag, the firewall switch, IPv4 (DHCP, static with optional gateway, or none) and IPv6 (none, SLAAC, DHCP, or static with optional gateway).
  - **DNS**: an optional DNS domain and DNS servers; empty uses the node's settings.
  - **Confirm**: a summary of everything, **Start after creating** (on by default), and the privileges your sign-in seems to lack for these choices.
- **New VM wizard** (eight steps):
  - **General**: ID (next free one filled in) and name.
  - **OS**: guest OS type (Linux, Windows 11/10/8/7, Solaris, Other) and an ISO from any ISO storage, or **No CD/DVD**. Picking Windows 11 turns on UEFI, Secure Boot keys and a TPM, as the web UI does.
  - **System**: SeaBIOS or OVMF (UEFI); with OVMF, the EFI disk's storage, **Pre-enroll Secure Boot keys** and **TPM 2.0**; machine type q35 (default) or i440fx; SCSI controller (VirtIO SCSI single by default, VirtIO SCSI, LSI 53C895A, LSI 53C810, MegaRAID SAS, VMware PVSCSI); and the **QEMU guest agent** switch (on by default).
  - **Disk**: bus (SCSI, VirtIO Block, SATA, IDE), storage (only storages that hold VM images), size in GiB, and the format (qcow2, raw, vmdk) on file storages such as directories, NFS and CIFS. VirtIO disks get an IO thread, as in the web UI.
  - **CPU**: sockets, cores per socket and CPU type (x86-64-v2-AES by default, x86-64-v3, x86-64-v4, host, kvm64, qemu64, or any other you type).
  - **Memory**: memory with quick picks, **Ballooning** (on by default) and an optional ballooning minimum.
  - **Network**: bridge, VLAN tag, firewall and the model (VirtIO, Intel E1000/E1000E, Realtek RTL8139, VMware vmxnet3).
  - **Confirm**: a summary, **Start after creating** (off by default) and any privileges that seem to be missing.
- **From a template**: every template on the server is listed; tapping one opens the existing clone dialog (ID, name, full or linked clone, target storage). Templates your sign-in can't clone say "needs VM.Clone".
- **The task log opens after creating**: as soon as Proxmox accepts a new container, VM or clone, ProxCtrl opens that task's live log (the same screen as in Tasks), and the Create pages close behind it. The guest list refreshes too.
- **Clear permission errors**: before creating, the last step lists each privilege your sign-in seems to lack and where (for example "SDN.Use on /sdn/zones/localnetwork/vmbr0"); you can still try, since Proxmox decides. If Proxmox refuses, a red card shows "Your sign-in isn't allowed to do this" with Proxmox's own message, and you stay on the page to change your choices.
- **Move disks to another storage**: on a guest's page, each disk in Resources has a **Move** button next to Grow (needs `VM.Config.Disk`). The dialog lists only other active storages on the guest's node that can hold the disk (VM images, or container directories), with their free space; ones without `Datastore.AllocateSpace` are greyed out. For VMs moving to a file storage you can keep the format or pick qcow2, raw or vmdk. **Delete the original** (off by default; otherwise the old disk stays as an unused disk) is blocked when the guest has snapshots, as Proxmox requires. An optional **speed limit** in MiB/s overrides the storage or datacenter limit. A second dialog confirms the move, and the task card follows it to the end. VMs can move disks while running; for running containers the page says to stop them first. EFI and TPM disks of VMs can be moved too; bind mounts can't.

### Changed

- The README lists the privileges for creating guests and moving disks (`VM.Config.Network`, `VM.Config.HWType`, `SDN.Use` on `/sdn/zones/localnetwork`), and the command that adds them to the operator role.
- The guest list has a little more room at the bottom so the + button never covers the last guest.

### Removed

- An unused placeholder screen for tabs that weren't built yet (and its two texts) was removed from the code. Nothing changes on screen.

## v1.3.0 (2026-10-25)

ProxCtrl now has a **light mode that follows the device theme**. The home-screen widgets get the Signal look too.

### New

- **Light mode that follows the device theme**: ProxCtrl now switches between Signal dark and the new **Signal light** by itself, whenever your phone switches between dark and light mode (including scheduled or sunset switching). There's no setting for it and no theme picker: it always follows the phone. Wallpaper colours (Material You) stay off.
- **Signal light colours**: a warm off-white background (#F5F5F1), white cards with a thin grey border, near-black text and grey secondary text. The violet accent (#B48CFF) still fills the most important things (the CPU card on the Overview, main buttons, the selected tab, switches that are on, the PIN dots, the profile picture on the You page), with dark content on it, the same as in dark mode. Where the accent is text or an icon on a light background (the slash in PROX/CTRL, RUN states, checkmarks, the focused text field, the fingerprint key on the lock screen, chart lines), it's a deeper violet (#6A3BDB) so it stays readable on white. Warnings are a darker amber and errors a darker red in light mode.
- **Every screen in both modes**: Overview, Guests, Storage, Tasks, Node and every page behind them, the You page and Settings, dialogs, bottom sheets, text fields, switches (light grey track with a grey knob when off), progress bars (light track, dark fill), the floating tab bar (white with a thin border), the lock screen (white keys, light grey empty PIN squares), Confirm changes, Share → ProxCtrl and the pinned guests widget's settings.
- **Status and navigation bars follow too**: their colour is the app's background, with light icons in dark mode and dark icons in light mode. The window behind the app as it starts also matches, so there's no dark flash in light mode.

### Changed

- **Home-screen widgets in the Signal look**, light or dark with the phone: a card with a thin border and rounded corners, small upper-case mono labels, square status dots (violet when running or healthy, grey when stopped, amber or red for problems), 6dp bars (the CPU bar in violet, RAM and disk in the text colour) and mono times. The node status widget shows the health ("ALL GOOD", "2 ISSUES") and uptime on top and the node's name in large type. In the pinned guests widget each guest shows its ID in mono, its name and its state, and **Start** is a violet button while **Shut down** is a grey one. "ProxCtrl is locked" uses the same card. Widgets already on your home screen change at their next update.
- **Notifications** use the Signal violet as their accent colour.
- **Consoles stay dark**: the VM and container consoles and the node shell are terminals, so they keep their dark look (and light status bar icons) in light mode too, like a photo or video viewer. Everything else follows the phone.

### Removed

- The frosted-glass blur library (haze) is no longer part of ProxCtrl. The tab bar hasn't used it since 1.2.0, so nothing changes on screen, but the app is a little smaller.

## v1.2.0 (2026-10-25)

ProxCtrl gets the new **Ctrl / Signal** look shared by every Ctrl app: near-black, one violet accent, square-cornered shapes and two new fonts. Only the look changed. Every screen, feature, setting and button works as before.

### Changed

- **New colours**: a near-black background (#08080A), dark grey cards (#141417) with a thin border, off-white text, and one violet accent (#B48CFF) for whatever matters most: the selected tab, switches that are on, main buttons, running guests and healthy nodes. Warnings stay yellow and failures red, so problems still stand out. Stopped guests and offline items are grey.
- **New fonts**: Space Grotesk for all text and headings, and JetBrains Mono for numbers, IDs, states, section labels and the PROX/CTRL wordmark. Both are bundled in the app (nothing is downloaded, no Google Fonts). They replace Manrope.
- **Top of every tab**: the PROX/CTRL wordmark on the left (with a violet slash) and your profile picture on the right as a rounded square with your server's initial, with a small violet square while ProxCtrl is connected. Tapping it still opens the You page with your servers, Settings and About. Tab titles are bigger and bolder below it.
- **Overview**: each node now starts with its status ("NODE · ONLINE") and its name in large type, with the Proxmox VE version and uptime below. CPU load is a big violet card with the percentage in large numbers, the thread count and a bar chart of the last CPU readings. Memory and root disk are two cards side by side with used / total and a bar. Kernel, IO delay, load, swap and the CPU, memory and network trends are in a Details card below. The node shell button sits next to the node's name.
- **Overview guests list**: under "Needs attention", a Guests list shows your first guests by ID, each with its name and a RUN / STOP / PAUSE state with a small square (violet when running). Tap a guest to open it, or the heading ("3 / 4 RUNNING") to open the Guests tab.
- **Tab bar**: a floating dark bar with rounded corners, a bit higher above the bottom of the screen. The selected tab is a violet block with its icon and name; the other tabs show their icon only. On tablets and in landscape the side rail uses the same colours.
- **Pushed screens** (guest, node pages, storage, tasks, settings and so on) have a square back button and the title next to it, instead of the Material top bar.
- **Cards and settings groups**: rounded cards (22dp corners) with a thin border, section headings in small upper-case mono type ("PRIVACY", "NOTIFICATIONS"), and thin lines between rows. Icons in rows are plain outlined icons instead of coloured circles.
- **Switches**: new rounded-square switches. On is a violet track with a dark knob, off is a dark track with a grey knob. The whole row is still one switch for TalkBack.
- **Buttons**: main buttons are violet with dark text and rounded corners (Support, Unlock, Done). Secondary buttons (Stop, Run now, Refresh, Copy, Load more and so on) are dark with a thin border.
- **Text fields**: dark fill with a thin border and rounded corners; the border and cursor turn violet while you type.
- **Progress and usage bars**: 6dp bars with rounded ends on a dark track (CPU, memory, disk, uploads, running tasks).
- **Status dots** are now small squares instead of circles.
- **Dialogs and bottom sheets**: dark cards with large rounded corners and a thin border.
- **Charts** draw in violet and off-white.
- **Lock screen**: a violet rounded square with a lock, "VKNXWORKS · CTRL" and "Locked" in large type, and the PIN shown as small squares that fill in violet. The keypad uses large rounded keys in mono type, three across. When fingerprint unlock is on, the fingerprint key sits bottom left on the keypad (outlined in violet) instead of a "Use fingerprint" button below; delete is bottom right. For PINs that aren't checked automatically, the bottom-left key turns into a violet OK key once enough digits are entered. "Confirm changes" and choosing or changing your PIN use the same keypad. How the lock works hasn't changed.
- **You page**: a close button and "PROFILE" at the top, then a violet avatar with your server's initial, its name and its address in mono type. **Support ProxCtrl** is now a violet button at the bottom instead of a row in About, with the version and "GOOGLE-FREE · TELEMETRY-FREE · ARM64" below it.
- **Status and navigation bars** always show the app's background with light icons.

### Removed

- **Theme and wallpaper colours**: the Signal look is dark only, so the **Appearance** section in Settings (Theme: Follow system / Dark / Light / Pure black, and Use wallpaper colours) is gone. ProxCtrl no longer follows the system's light mode or your wallpaper colours. Your old choice is kept in the settings file but no longer does anything.

### Licences

- Space Grotesk and JetBrains Mono are licensed under the SIL Open Font License 1.1. Their licence texts are in the repository (`FONT-LICENSE-SpaceGrotesk.txt`, `FONT-LICENSE-JetBrainsMono.txt`) and inside the app (`assets/licenses/`). The Manrope font and its licence were removed.

## v1.1.10 (2026-10-22)

### Fixed

- **ProxCtrl now really asks to show notifications**: on Android 13 and later an app can only show notifications after you allow it, and ProxCtrl only asked when you turned **Check in the background** or **Push from Proxmox** on. If that question was missed or answered with no, checks and push messages kept arriving but every alert (node or guest down, high CPU, storage, backups, updates and Proxmox's own notifications) was silently dropped. With background checks or push on, ProxCtrl now asks once by itself as soon as it's unlocked, and never again after that, so it doesn't nag on every start.
- **Allow notifications always does something**: once you'd said no twice, Android stops showing its question, and the **Allow notifications** button on the Notifications screen did nothing at all. It now opens ProxCtrl's notification settings in Android when the question can't be shown any more (and when notifications are turned off there), and the app lock doesn't lock you out when you come back.

### Changed

- **Notification switches show the truth**: while notifications are blocked, **Check in the background**, **Push from Proxmox** and the event switches show as off, because nothing can reach you, and the red banner at the top explains why. The push address and setup commands are hidden until notifications are allowed. Turning any of the switches on asks for the permission (or opens Android's settings when it can't ask any more). As soon as you allow notifications, the switches show your saved choices again; you don't have to set anything twice.
- **Settings shows when notifications are blocked**: the **Notifications** row in Settings reads "Notifications are blocked: tap to allow them" while background checks or push are on but ProxCtrl can't show notifications.

## v1.1.9 (2026-10-21)

### Fixed

- **Fingerprint unlock works again after you leave the app**: once you'd left ProxCtrl with the back gesture or by swiping home, the lock screen no longer asked for your fingerprint when you came back, and tapping **Use fingerprint** did nothing either; only the PIN worked until the app was force-closed. The fingerprint prompt was asked for while ProxCtrl was still coming back on screen, and the phone dropped it without answering, which left ProxCtrl waiting for an answer that never came. Now the prompt only opens once ProxCtrl is fully back on screen, a prompt that never answered can't block the next one, and the lock screen offers your fingerprint again every time you come back to it, not just the first time after it locked.
- **Confirm changes asks for your fingerprint again after you leave the app**: the same fix applies to the **Confirm changes** screen. If you leave ProxCtrl while it's asking and come back, it offers your fingerprint again, and **Use fingerprint** always works.
- **The one-time unlock with the phone's screen lock (after updating from the old app lock) is asked for again when you come back**, and can't get stuck in the same way.

## v1.1.8 (2026-10-21)

### Security

- **Sharing an ISO only accepts files from other apps**: the Share → ProxCtrl screen accepted any address another app handed it, including `file://` addresses and ProxCtrl's own files, so another app could get ProxCtrl to upload one of its private files to your server. It now only takes files offered by another app (a `content://` address that doesn't belong to ProxCtrl, also when written with a user number in front), and otherwise says there's no file to upload.
- **Only the pinned guests widget can open the Start / Shut down question**: any app could open ProxCtrl straight into "Start …?" or "Shut down …?" for one of your guests. Nothing happened without your confirmation, but the question could be made to pop up out of nowhere. The widget's buttons now carry a secret only ProxCtrl knows, and requests without it are ignored. Widgets already on your home screen pick it up at their next update (about every 15 minutes, or when you open ProxCtrl).
- **Widgets and the tile say "ProxCtrl is locked" while it's locked**: with the app lock on and *Background checks while locked* off, the node status and pinned guests widgets kept showing your guests' names and the node's last known state, and the Quick Settings tile the node's name and health, while ProxCtrl was locked. They now only say "ProxCtrl is locked" until you unlock it, then show your server again.
- **Your server logins are always sealed with the app lock**: if ProxCtrl was stopped in the split second between setting up the app lock and encrypting your server logins with the PIN, they could stay encrypted with the Android Keystore only. ProxCtrl now checks every time you unlock and seals anything that was missed.

## v1.1.7 (2026-10-21)

### Fixed

- **Clear message when a certificate can't be read**: when you add a server with a self-signed or changed certificate and the app can't fetch that certificate to show you, it now says either that it can't reach the server (check the address and network) or that it couldn't read the certificate the server presents. Before, it showed the phone's raw technical error text, or just the server's address.

## v1.1.6 (2026-10-21)

### Changed

- **The page behind your profile picture is now called You**, the same name as in every Ctrl app (before: "Account"). It still holds your servers, read-only mode, what your sign-in may do, **Settings** and **About**, and the profile picture at the top right of every tab is still the only way into Settings. Screen readers now announce the profile picture as "You" instead of "Account and settings".
- **README**: Getting started now says what's behind the profile picture, and the app lock and Support sections say to tap your profile picture.

## v1.1.5 (2026-10-21)

### Changed

- **Hide in screenshots** and **Hide in recent apps** are now two separate switches in **Settings > Privacy and security**, both off by default. **Hide in screenshots** makes screenshots and screen recordings show a blank screen (the recent-apps view too). **Hide in recent apps** only shows a blank card in the recent-apps view, so screenshots still work. They replace "Hide in screenshots and recent apps".
- **What changes if you never touched it:** the old switch was on by default, so ProxCtrl used to block every screenshot and always showed a blank card in recent apps. Both new switches start off, so screenshots of ProxCtrl now work and recent apps shows a preview. Turn both switches on to keep the old behaviour. With the app lock on, recent apps still always shows a blank card, because a preview there would show your servers without the PIN.
- Signing in to a server, consoles, the node shell and the lock and PIN screens are still always kept out of screenshots and recordings, whatever the switches say. Sharing an ISO and the pinned guests widget's settings follow the same switches.

## v1.1.4 (2026-10-21)

Settings now follow the same layout as the other Ctrl apps, and the lock screen is better protected.

### Changed

- **Settings are in the same order as in every Ctrl app:** Appearance first, then Notifications, then **Privacy and security**, then Warnings and Widgets. Privacy and security now holds everything that was under Privacy and App lock, in the same order as the other apps: App lock, Change app PIN, Unlock with fingerprint, Lock again, Confirm changes, Background checks while locked, and Hide in screenshots and recent apps.
- **"Hide content in screenshots & recents" is now called "Hide in screenshots and recent apps"**, and "Dynamic colour" is now **Use wallpaper colours**, the same names as in the other Ctrl apps. Both work as before.
- **The lock screen after updating from the old app lock** looks like the PIN screen and the other Ctrl apps: the same lock badge, and a full-width **Unlock** button.

### Security

- **The lock screen and every PIN screen are always kept out of screenshots and screen recordings**, also with "Hide in screenshots and recent apps" turned off, and when sharing an ISO to ProxCtrl.
- **Accessibility services can't read or tap the app behind the lock screen any more.** While ProxCtrl is locked, the screens underneath are hidden from TalkBack and other accessibility services, not only covered.
- **The console's diagnostics panel no longer turns on remote debugging** (inspecting the console from a computer over USB) in the release app. A computer with USB debugging access could otherwise reach the open shell while the panel was open. The panel itself still shows its diagnostics.

## v1.1.3 (2026-10-21)

### Security

- **Trips out of the app no longer skip the lock if you stay away.** The file picker, links, ntfy and the notification, battery and permission screens still don't count as leaving the app, but if you don't come back within 5 minutes (or your "Lock again" time, if that's longer), ProxCtrl locks anyway. Before, such a trip never locked, however long you were gone.

## v1.1.2 (2026-10-21)

The app lock now works the same way as in every other Ctrl app, with the strongest protection of the options compared.

### Changed

- **App lock now has its own PIN**, separate from your phone's screen lock (**Settings › App lock › App lock**). It needs at least 6 digits, and very obvious ones (like 111111 or 123456) are refused. Someone who knows or has seen your phone's PIN can't open ProxCtrl with it
- **Updating with the app lock (or "Confirm changes") already on:** the first time you open ProxCtrl, unlock once more with your fingerprint, face or screen lock as before, then choose your app PIN. This step can't be skipped. If the phone no longer has a screen lock, ProxCtrl goes straight to choosing the PIN. Having only "Confirm changes" on now also turns on the app lock, because both use the PIN
- **Fingerprint unlock is optional** (**Unlock with fingerprint**). It only accepts strong biometrics such as the fingerprint sensor, not face unlock that only uses the camera, which a photo can sometimes fool. The PIN is still needed after the phone restarts and at least every 72 hours, the same as Android does for your screen lock
- **Lock again**: choose when ProxCtrl asks again: as soon as you leave the app (the new default; it used to be after 30 seconds), after 1, 5 or 15 minutes or 1 hour away, or only when the app restarts. Opening the file picker, a link, ntfy or the notification and battery settings from ProxCtrl doesn't count as leaving the app
- **Confirm changes** now asks for your fingerprint or app PIN instead of the phone's screen lock. One confirmation still covers the next minute, and unlocking counts as one
- **Change app PIN** asks for your current PIN first, and turning the app lock off asks for the PIN too
- **Background checks while locked** (new switch, shown with the app lock on): on, notifications, widgets and the Quick Settings tile keep checking your servers while ProxCtrl is locked. Off (most secure), your server logins are also encrypted with your app PIN while ProxCtrl is locked, and background checks, widgets and the tile wait until you unlock. When you turn the app lock on, this starts on if you use background checks, push or widgets, so nothing stops without you choosing it

### Security

- **Server logins can be encrypted with your PIN while ProxCtrl is locked** (with *Background checks while locked* off). The key that decrypts them only exists in memory while the app is unlocked and is wiped the moment it locks; the connection to the server is closed until you unlock. A locked app, or a copy of its files, can't use your API token, password or session
- **The PIN can't be guessed off the phone.** It's never stored. It's stretched with 600,000 rounds of PBKDF2 and mixed with a secret held in the phone's secure hardware (StrongBox where available), so copying ProxCtrl's files to a computer isn't enough to try PINs there
- **Wrong PINs lock the screen**: after 5 tries you wait 30 seconds, doubling with every further 5 wrong tries up to 15 minutes. Restarting the app or the phone, or changing the clock, doesn't reset the wait. The same counter covers "Confirm changes"
- **Fingerprint unlock can't be faked**: the fingerprint has to unlock a key in the phone's secure hardware, so a tampered "success" on a rooted phone doesn't open the app. Adding a new fingerprint turns fingerprint unlock off until you turn it on again
- **Forgot your PIN?** The lock screen offers to remove every server from this phone and turn the app lock off. Because the PIN can encrypt your logins, there's no way around it. Nothing changes on your Proxmox servers; add them again to continue

## v1.1.1 (2026-10-20)

### Changed

- **64-bit ARM phones only.** ProxCtrl now ships only the 64-bit ARM (arm64-v8a) build that nearly every phone from the last several years uses, like every Ctrl app, so the download is smaller. Old 32-bit phones and x86 devices (emulators, some Chromebooks) are no longer supported.
- **README**: Requirements say 64-bit ARM phones only

## v1.1.0 (2026-10-20)

### Changed

- **Push from Proxmox through ntfy only.** Push notifications now always come through the [ntfy app](https://f-droid.org/packages/io.heckel.ntfy/), and other push apps can no longer be picked (the "Choose a push app" list is gone). If you already used ntfy, nothing changes: your push address stays the same, so the webhook target on your Proxmox server keeps working. If you used another push app, ProxCtrl removes its registration there and registers with ntfy instead when ntfy is installed; then copy the new setup commands under **Set up Proxmox** and run them again, since the push address changes. Without ntfy, push from Proxmox is off until you install it. Background checks keep running either way
- **ntfy isn't installed?** **Settings › Notifications** now says so under **Push from Proxmox**, with an **Install ntfy** button that opens ntfy on F-Droid. It checks again whenever you come back to the screen
- **ntfy battery warning**: with push on, the **Battery** section warns when ntfy is battery-restricted (Android can then cut its connection while the phone sleeps), and tapping it opens ntfy's app info so you can set its battery usage to Unrestricted
- **ntfy temporarily unavailable**: when ntfy reports that your ntfy server is down, **Push from Proxmox** says so. The push address stays the same and push resumes by itself once the server is back
- **ProxCtrl registers with ntfy again every time it starts**, which keeps the same push address and recovers by itself if ntfy was reinstalled
- Texts on the Notifications screen now talk about ntfy instead of "UnifiedPush" or "push apps"

### Privacy

- **De-Googled.** The push library ProxCtrl used is gone, along with the Google crypto library (Tink) it brought along, its built-in check for Google Play services and its references to Firebase. ProxCtrl now talks to ntfy directly, contains no Google libraries or services and never contacts Google, and the app's texts no longer mention Google
- **README**: the Google-free line now reads "Google-free and de-Googled", push is described as ntfy-only with background checks as a fallback, and the ntfy setup steps are updated

## v1.0.2 (2026-10-20)

### Privacy

- **No more usage stats: ProxCtrl is now telemetry-free.** The optional anonymous usage stats are gone completely, along with the Usage stats setting in Settings and the one-time question after adding your first server. The app no longer contacts VKNXWorks' stats server at all, and the settings it used are deleted from your phone when you update. ProxCtrl now only talks to your own Proxmox servers (and your ntfy server, if you use push notifications)

### Changed

- **README**: a Screenshots section (screenshots are coming soon), a new Privacy section and a "Telemetry-free" badge, and a link preview image (`.github/assets/social-preview.png`) with ProxCtrl's icon, name and summary, for when the repo is shared on Reddit, Discord or elsewhere

## v1.0.1 (2026-10-19)

### Changed

- **One About section in every Ctrl app**: Support, Report a bug, Source code and Licence (GPL-3.0), with the version underneath, at the bottom of the Account page (tap the profile picture on any tab)
- New **Report a bug** row in About opens ProxCtrl's GitHub issues, where you can report a problem or suggest an idea
- The "Source code and issues" row is now just **Source code**, since bugs have their own row

## v1.0.0 (2026-10-19)

ProxCtrl is complete: the Storage, Tasks and Node tabs, guest resources and clones, widgets, a Quick Settings tile and an app lock.

### New

- **Resources on a guest's page**: cores (with sockets and CPU type for VMs), memory with ballooning or swap, every disk with its storage and size, and every network device with its model, MAC address, bridge, VLAN, IP settings and firewall flag
- **Edit cores and memory** (and swap for containers), with quick memory presets. Only what you changed is sent, and a running VM shows a note that some changes may only apply after its next restart
- **Edit tags**: add and remove tags in the server's tag colours; invalid tag names are caught before saving
- **Grow a disk** by a number of GiB, with the new size shown before you confirm. Disks can only grow, never shrink, and the guest ID has to be typed in first. EFI and TPM state disks and bind mounts can't be grown and have no button
- **Options and firewall** screen: the guest's options (boot order, BIOS, machine, guest agent, protection, start at boot and order, unprivileged, features, DNS…), its firewall settings and policies, and every firewall rule with action, ports, source, destination and comment. Read-only
- **Task history** for each guest, from its page
- **Clone** a guest or template from the menu on its page: the next free ID is filled in and checked, with a name or hostname, a full or (for templates) linked clone, and the storage for a full copy's disks
- **Convert to template** from the same menu, for stopped guests, only after typing the guest ID
- **Storage tab**: every storage of your node with its type, a usage bar with used, total and percentage, the content it holds (backups, ISO images, container templates, disk images…), and whether it's enabled, shared and available right now. Pull down to refresh; it also refreshes every 30 seconds while open
- **Storage content**: tap a storage to see everything on it, grouped by type with filter chips, newest first, with date, size, format, guest ID, notes, and marks for protected and verified backups
- **Delete backups** from a storage's page after a confirmation. Protected backups can't be deleted, and the button is greyed out without the privileges for it
- **Upload ISO images** from a storage's page with the system file picker, or from any app with **Share → ProxCtrl**: pick the storage (only ISO storages you may upload to are offered), check the file name, and follow the upload with a progress bar until Proxmox has moved the file into place
- **Download container templates** from Proxmox's template list, grouped by section with search; templates already on the storage are marked so they aren't downloaded twice. The download runs as a Proxmox task with its progress and result shown
- **Disks**: every physical disk with model, size, type, S.M.A.R.T. health (passed or failing at a glance) and SSD wearout, as Proxmox shows them; tap a disk for its full S.M.A.R.T. values (the attribute table, or the text report for NVMe and SAS disks)
- **ZFS pools** on the Storage tab with their health, and a pool page with its state, what's wrong and what to do when it isn't healthy, the last scrub, errors, and every vdev and disk with its read, write and checksum error counts
- **LVM volume groups and LVM-thin pools** with their usage, physical volumes, and thin-pool metadata usage
- **Backup jobs**: every scheduled backup job with which guests it covers (all, all except, a list, or a pool), its storage, mode, schedule, next run, and the result of its last run; **Run now** starts it straight away with its own settings, like "Run now" in the web UI
- **Tasks tab**: tasks running now on every node, each with its latest log line, a progress bar when the log shows how far it is (backups, restores, disk moves), how long it has been running and who started it, and a **Stop** button (with a confirmation) for tasks you're allowed to stop. Updates every few seconds while it's on screen
- **Task history** below, newest first, with the task named as in the Proxmox web interface ("VM 106 (haos15.2) – Start", "Backup job", "Update package database"), when it ran, how long it took and who started it; failed tasks and warnings show Proxmox's message. Filter by status (OK, warnings, failed), type, user and guest, and load more to go further back
- **Task details**: tap a task to see its node, who started it, when it started and ended, how long it took and how it ended, and its full log. While a task runs the log follows new lines; scroll up to read without being pulled down, and tap the arrow to jump back to the end. Copy the log or switch off line wrapping for wide output
- **Task history for a guest**, from its page: the same list, only for that guest, including its running tasks
- If your token can only see its own tasks, the Tasks tab says so (Sys.Audit on the node shows everyone's)
- **Node tab**: the node's health at a glance (CPU with the number of CPUs, IO delay, load average, RAM, swap and root disk with the same warning colours as the Overview), uptime, and the details: CPU model, sockets and CPUs, total RAM, kernel, Proxmox VE version, and whether it booted with EFI (and Secure Boot) or legacy BIOS. Pull down to refresh. With more than one node, chips at the top switch between them
- **Node graphs**: CPU and IO delay, load average, memory and network traffic, for the last hour, day, week, month or year, with the same touch-to-read charts as the guest page
- **Services**: every Proxmox service on the node with its state (running, stopped, failed, disabled, masked), and Start, Stop and Restart, each confirmed and followed as a task. Restarting or stopping a service ProxCtrl connects through (pveproxy, pvedaemon, pve-cluster, sshd) comes with a clear warning, and Stop isn't offered for the services Proxmox refuses to stop
- **Updates**: the number of available updates on the Node tab, and a list with each package's installed and new version, title and origin. Tap a package to read its changelog. **Refresh** runs apt update on the node as a task. A note appears when a newer kernel is installed than the one running, so you know a reboot is due. Upgrading stays in your hands: **Shell** opens the node shell right there
- **Network**: the node's interfaces, bridges, bonds and VLANs with IPv4 and IPv6 addresses and gateways, bridge ports, VLAN awareness, MTU and comments, plus the cluster's SDN zones and VNets (type, bridge, tag, and whether changes are still pending). Read-only
- **Reboot and shut down the node**, only after typing the node's name. The dialog spells out that all guests are shut down first, and before a shutdown whether Wake-on-LAN is set up to turn the node back on
- **Wake-on-LAN** for the node: save its MAC address (plus broadcast address and port if your network needs them) per server, and wake it from the Node tab's menu. When the node can't be reached, a card offers to wake it straight away. The packet is sent from your phone on the local network; ProxCtrl never uses your Wi-Fi name or location
- **Shell** on the Node tab opens the node shell, like the button on the Overview
- **Node status widget** for the home screen: the node's health (all good, how many things need a look, or can't be reached), CPU, RAM and root disk with bars, uptime, and when it was last updated. Tap it to open the Overview
- **Pinned guests widget**: choose which guests it shows when you add it, and change them later by tapping the widget's title. Each guest has a status dot (running, stopped, paused) and a **Start** or **Shut down** button. The buttons don't act from the home screen: they open ProxCtrl and ask you to confirm, and nothing happens if you cancel. A guest that's no longer on the server is marked as such
- The widgets show the active server and refresh about every 15 minutes while there's a network connection, and when you open the app. While the server can't be reached they keep showing the last known state, marked "Not updated"
- **Quick Settings tile**: shows the node's name and health with CPU and RAM underneath, lit when everything is fine. Tap it to open the Overview (on a locked phone, after unlocking)
- **App lock** in Settings: **Lock when opening the app** covers ProxCtrl until you unlock it with your fingerprint, face or screen lock, on start and whenever it comes back after more than 30 seconds in the background. **Confirm changes** asks for the same before anything that changes a server (power actions, snapshots, backups, restores, edits, opening a console), and one confirmation covers the next minute, so bulk actions ask once. Cancelling stops the change before anything is sent. ProxCtrl never sees or stores your fingerprint or PIN
- The pinned guests picker also follows the app lock and the "Hide content in screenshots & recents" setting

### Changed

- The README describes every part of the app: the **Planned** section is gone, the feature list covers the Storage, Tasks and Node tabs, widgets, the tile and the app lock, and there are new sections for guest resources and clones, Storage, Tasks, Node, and widgets, tile and app lock
- The README's privilege tables list every privilege the new features use (`VM.Config.CPU`, `VM.Config.Memory`, `VM.Config.Disk`, `VM.Clone`, `VM.Allocate`, `Datastore.AllocateTemplate`, `Sys.PowerMgmt`, and `Sys.Modify` for services and updates), with one `pveum role modify` command that adds them all to the operator role
- "Safe by design" in the README now also lists the typed confirmations for growing a disk, making a template and rebooting or shutting down the node, and the app lock

## v0.8.1 (2026-10-19)

ProxCtrl is now completely Google-free. Nothing changes in how you use it.

### Privacy

- **Emoji no longer make ProxCtrl ask Google for a font.** A standard Android library inside the app asked Google Play services for a downloadable emoji font every time ProxCtrl started, which could make Google Play services fetch that font from Google. This lookup is now switched off: emoji are drawn with your phone's own emoji font. On phones without Google Play services nothing changes; on other phones, emoji can look slightly different if the phone's built-in emoji font is older.
- **The built-in web view no longer reports to Google.** The VM and container consoles and the node shell run in Android's built-in web view, which by default checks pages with Google Safe Browsing and may send usage reports to Google. Both are now turned off for ProxCtrl. It works exactly as before.
- **Checked: ProxCtrl is Google-free.** It contains no Google Play services, Firebase, ML Kit, Google Cast or Play Core, and it doesn't load fonts or anything else from Google's servers. It also never switches server addresses by itself: it always uses the address you set up, and only you can change it.

### Changed

- **README: new Google-free line** under Requirements: works without Google Play services, contains no Google libraries that contact Google, and draws emoji with your phone's own emoji font, and the web view used for the consoles and node shell has Google Safe Browsing and usage reports turned off.
- **README brought up to date with 0.8.0.** The Storage tab, the Tasks tab with live logs, the Node tab (services, updates and network) and the home screen widgets and Quick Settings tile aren't in the app yet, so they moved from the feature list to a new **Planned** section. The feature list now also mentions the node shell on the Overview, the compact guest list, IP addresses and notes on guest pages, several servers with token or password sign-in, the offline view, `proxctrl://guest/<id>` links and the encrypted token secrets and passwords. The privilege tables only list what the app uses today; changing cores and memory, editing tags, growing disks, cloning, uploading ISOs, node reboot and restarting services are listed as planned. Install now has the CtrlF-Droid badge and link, and a new paragraph lists everything ProxCtrl connects to besides your servers. No change to the app itself

## v0.8.0 (2026-10-19)

Snapshots and backups for every guest.

### New

- **Snapshots and backups** on a guest's page: how many snapshots it has and when its last backup was made, each opening its own screen
- **Snapshots screen**: all snapshots of the guest as a tree, oldest at the top, with child snapshots indented under the one they were taken after, and **Now** marking where the guest is. Each shows when it was taken, its description, and whether it includes RAM
- **Take snapshot**: name (a suggested one with the date and time is filled in, and invalid or duplicate names are caught before sending), optional description, and for running VMs an **Include RAM** switch so a rollback resumes the VM where it was
- **Roll back** to a snapshot: warns that everything since the snapshot is lost and that a running guest is stopped first, offers **Start afterwards** (snapshots with RAM start by themselves), and only goes ahead once you type the guest ID
- **Delete snapshot**, also confirmed by typing the guest ID
- **Backups screen**: every backup of the guest on all backup storages of its node, including Proxmox Backup Server, newest first, with date, age, storage, size and notes, a lock for protected backups and a mark for verified or failed-verification backups. A storage that can't be read is named, without hiding the others
- **Back up now**: choose the storage (only ones you may write to, Proxmox Backup Server preferred), the mode (snapshot, suspend or stop, each explained), the compression (ZSTD, LZO, GZIP or none; not shown for Proxmox Backup Server), notes (`{{guestname}}` and friends are filled in by Proxmox), and whether to protect the backup. Older backups are never pruned from the app
- **Restore**: replaces the guest with the chosen backup after a big warning that its current disks, settings and snapshots are lost, and only once you type the guest ID. Choose where the disks go (containers default to the storage their root disk is on now; VMs default to the storages in the backup) and whether to start it afterwards. A running or protected guest can't be restored over, and the app tells you why
- **Delete backup** with a confirmation. Protected backups can't be deleted
- Every snapshot, backup, restore and delete runs as a Proxmox task: a card shows it running, then the result, with the end of the task log when it fails or has warnings. The task keeps running on the server if you leave the screen
- Pull down to refresh the snapshot and backup lists
- Actions you don't have the privileges for are greyed out, and all of them are hidden in read-only mode (where the app also refuses to send them)

### Changed

- The README's "Safe by design" line now also mentions the typed guest ID before rolling back or deleting a snapshot and before restoring a backup
## v0.7.5 (2026-10-19)

Security and privacy hardening.

### Security

- **Read-only mode now blocks every change, not just the buttons.** While a server is in read-only mode, ProxCtrl refuses to send anything that could change something on it (power actions, notes, opening a console or the node shell), whichever screen asks. Switching read-only on also stops screens that were already open, and closes a console that's open at the time
- **A saved token secret is only sent to the server it was saved for.** When you edit a server and change its address or token ID, ProxCtrl now asks for the token secret again instead of sending the saved one to the new address. Editing a server behind a reverse proxy (port 443) also no longer turns its address into port 8006
- **Notifications on the lock screen hide the details.** While your phone is locked, ProxCtrl's notifications only say there's a new alert from your Proxmox server; server, node, guest and storage names show after you unlock
- **Notifications go away with their server.** Removing a server from ProxCtrl also removes its notifications
- **Sign-in and consoles are never in screenshots.** The screen where you enter a token secret, password or two-factor code, and every console and node shell, are always hidden from screenshots, screen recordings and the recent apps view
- **New setting: Settings > Privacy > Hide content in screenshots & recents**, on by default, does the same for the whole app. The recent apps view always shows a blank card for ProxCtrl
- **Consoles are locked down further.** The console page can only load the files bundled with the app and can't reach the network on its own; anything else it tries to load is refused. Remote debugging, which the diagnostics panel turns on, now always switches off again when you leave the console
- **Copied secrets stay out of clipboard previews.** The push address and the Proxmox setup commands (which contain your private ntfy topic), and console diagnostics, are copied as sensitive, so Android doesn't show them in the clipboard preview and keyboards don't keep them
- **Two-factor recovery keys** are typed with a password keyboard, so the keyboard doesn't suggest or remember them
- **Push notifications are kept short.** A push message's title and host name are shown on one line and cut to a sensible length, so a message sent to your push address can't fill the screen

### Changed

- **Usage stats send exactly what's promised.** Power action stats no longer say whether the action was run on several guests at once, which the description didn't list. Turning usage stats off now also drops anything that was still waiting to be sent. The description in the app now lists everything the README does (phone or tablet, theme and compact list)
- **All Ctrl apps in the README.** The README's Ctrl series table now lists all 14 apps. New in the table: [ArrCtrl](https://github.com/VoidedKN0X/ArrCtrl) (Radarr, Sonarr, Prowlarr, Bazarr, qBittorrent, autobrr and Cleanuparr in one app), [PaperCtrl](https://github.com/VoidedKN0X/PaperCtrl) (scan and browse documents on Paperless-ngx), [PrintCtrl](https://github.com/VoidedKN0X/PrintCtrl) (Bambu Lab printers through Bambuddy, and Spoolman spools) and [TrueCtrl](https://github.com/VoidedKN0X/TrueCtrl) (monitor and manage your TrueNAS server). Every app in the table is also in the [CtrlF-Droid repo](https://voidedkn0x.github.io/CtrlF-Droid/) for automatic updates.

- **README describes only what's in the app's security.** The README's "Safe by design" line no longer mentions typed confirmations and a fingerprint lock, which aren't in ProxCtrl yet. It now lists read-only mode, the confirmation before bulk actions, certificate pinning and screenshot protection.

## v0.7.4 (2026-10-19)

Optional, anonymous usage stats.

### New

- **Optional anonymous usage stats.** After you add your first server, ProxCtrl asks once whether you'd like to share anonymous usage stats. Nothing is sent unless you tap **Share stats**, and you can switch it on or off any time in **Settings > Usage stats**
- **What's shared:** which screens you open (for example "Overview", "Guest" or "Console", never which node or guest), which power action you use (start, shut down, reboot, stop, suspend, resume or hibernate) and whether on a container or a VM, which kind of console you open (node shell, container terminal, VM screen or VM serial terminal), the app version, Android version, phone model, language, phone or tablet, your theme and whether you use the compact guest list
- **What's never shared:** your server address, node names, guest names or IDs, IP addresses, username, API tokens or passwords, anything you type in a console, or any ID that follows you between sessions. Each session gets a new random ID, and stats from before and after you switch them off and on again aren't linked
- **Where it goes:** to VKNXWorks' own self-hosted server (Aptabase), not to Google or any analytics company. Stats use their own connection with your phone's normal certificate checks, separate from your Proxmox connection and its pinned certificate. Builds you make from source can't send stats at all, and the setting doesn't appear in them

### Changed

- The README has a new **Usage stats** section listing exactly what's collected

## v0.7.3 (2026-10-19)

The real fix for consoles that stayed frozen.

### Fixed

- **Consoles never showed what you typed or the command output**: in the node shell, container consoles and VM screens, typing and output did reach the server and the console (the diagnostics from 0.7.2 confirmed it), but the console page was never redrawn after its first frame, so the screen looked stuck. The console is now shown in its own layer on top of the app instead of inside the app's layout, where it wasn't being redrawn
- Terminals could open only one line tall and stay that way. They now resize to fill the console area as soon as it's known, and again whenever it changes (keyboard, banners, rotation)

### Changed

- The diagnostics panel now sits above the console instead of over it, so it doesn't hide the top lines
- The console appears once it's connected; until then you see the progress indicator, or the error card with **Reconnect**

## v0.7.2 (2026-10-19)

Another fix for consoles that didn't react to typing, plus a diagnostics panel in case they still don't.

### Fixed

- **Consoles looked frozen**: the node shell, container consoles and VM screens could keep showing the first screen they drew, so typed text, command output and screen changes never appeared even though they were sent. Consoles are no longer drawn under the glass effect behind the tab bar, which kept the console from being redrawn
- In landscape on a wide screen, the console now fills the whole screen instead of sitting next to the side navigation
- Pressing **Reconnect** could leave the new connection marked as closed by the old one, so nothing typed after reconnecting was sent. Events from an old connection are now ignored

### New

- **Diagnostics** button (bug icon) at the top of every console. It shows a live readout: whether the console proxy started and the connection is open, messages and bytes received and sent, whether the page sent anything, whether the keyboard field has focus and how many key presses it got, and the last errors from the console page. **Copy** puts it on the clipboard so you can paste it into a bug report. It never shows what you typed, passwords or tickets
- While the diagnostics panel is open, the console page can be inspected from a computer over USB debugging (`chrome://inspect`). Closing the panel turns this off again

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
