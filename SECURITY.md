# Security policy

Thank you for helping keep ProxCtrl and its users safe.

## Reporting a problem

**Please don't report security problems in a public issue.** Report them privately instead:

1. Go to the [Security tab](https://github.com/VoidedKN0X/ProxCtrl/security) of this repo.
2. Click **Report a vulnerability** and describe what you found, how to reproduce it, and which version of ProxCtrl you used.

Only the maintainer can see the report. I'll reply as soon as I can, work on a fix, and credit you in the changelog if you'd like.

## Supported versions

Only the latest release gets security fixes. Please update before reporting, and check the [changelog](CHANGELOG.md) to see whether it's already fixed.

## What counts

- Your API token, password, session ticket or other data leaking to anyone other than your own Proxmox VE server
- ProxCtrl accepting a certificate you didn't trust, or a changed certificate, or sending anything over plain HTTP that you didn't allow
- A change to your server (power actions, snapshots, backups, restores, consoles, deletions) happening without your confirmation, in read-only mode, or while the app lock should block it
- Ways around the app lock, or protected screens (sign-in, consoles, PIN screens) showing up in screenshots when they shouldn't
- Another app on the phone being able to make ProxCtrl do something you didn't ask for (links, widgets, shared files, push messages)
- Anything that lets someone run code or read files they shouldn't

Problems in Proxmox VE itself should go to that project.

## How releases are protected

Every release is built from this repo by GitHub Actions and signed with the same release key. Its SHA-256 certificate fingerprint is:

```
14:22:8B:A9:36:0F:A2:93:AC:20:CF:9D:32:76:50:E3:2C:51:86:34:F8:05:67:68:23:91:C5:EC:25:9E:6D:FE
```

The [CtrlF-Droid repo](https://voidedkn0x.github.io/CtrlF-Droid/) only accepts APKs signed with this key.
