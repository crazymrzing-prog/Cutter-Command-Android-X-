# Cutter Command

A from-scratch Kotlin rewrite of a 2017 E4A-built vinyl/blade cutter control
app, targeting current Android (`minSdk 24`, `targetSdk 34`) so it actually
installs on Android 14/15/16 and behaves correctly under scoped storage.

## What's here
- `PlotterClient.kt` - the TCP client. The plot-file send itself follows a
  confirmed-working reference (a Python script that talks to this exact
  cutter): connect with a short timeout, then send the file as one raw
  write (UTF-8, trailing newline), no preamble, no handshake. The jog pad
  and Pause/Stop/Test commands are ported from the decompiled `主窗口`
  (main window) class of an older, different app for a similar machine -
  not confirmed against this cutter, use with that in mind.
- `MainActivity.kt` / `activity_main.xml` - a single-screen UI: IP/port +
  Connect/Disconnect, a manual jog pad with Pause/Test (and a blank spot
  reserved for a possible future control), a read-only Up Speed (US) /
  Cut Speed (VS) / Pressure (FS) display, file picker/send, and a log -
  plus a toolbar menu with Error messages / Help / About.
- `res/drawable/ic_launcher_*.xml` + `res/mipmap-*` - an original launcher
  icon (captain's-hat + red cutter-cylinder silhouette), not based on or
  copied from any specific manufacturer's logo.

## Protocol
- **Plot file send** (confirmed working): connects over raw TCP (default
  `192.168.16.254:8080`) with a 5s connect timeout, then sends the loaded
  file's content exactly as read from disk - UTF-8, with a trailing `\n` -
  as a single write, with a 90s send timeout. No `BD:` preamble, no
  staged/split send, no PGREADY/PGOK handshake - the machine is sent
  nothing but the file itself.
- **Up Speed (US) / Cut Speed (VS) / Pressure (FS)**: the app does not set
  these. It reads them out of the loaded plot file (`US<n>;`, `VS<n>;`,
  `!FS<n>;`) and displays whatever it finds, read-only. If a file doesn't
  define one, the display shows "Machine Setting" - the app sends nothing
  for it, so whatever's already configured on the cutter itself applies.
- **`RSVER;`** is still sent 3x on connect (expects `VER=...;` back), and a
  bare space is sent once a second as a keepalive - both reverse engineered
  from the older decompiled app, not confirmed against this cutter either,
  but harmless if the cutter simply ignores them.

### Manual control panel - reverse engineered, not confirmed against this
### cutter (unlike the plot-file send above)
Ported from the decompiled main-window class of an older, different E4A
Android app for a similar machine, not from this cutter's own documentation
or the Python reference - treat these as unverified until tested:
- **Jog pad** (Forward/Back/Left/Right) - while held, repeatedly cycles
  move-then-stop for as long as it's held, stopping for good once
  released. Each cycle always completes (its stop always fires) even if
  release happens mid-cycle - release only prevents a *new* cycle from
  starting after the current one finishes. Move sends `;BD:100,3;` (fwd) /
  `;BD:100,4;` (back) / `;BD:100,1;` (left) / `;BD:100,2;` (right); stop
  sends `;BD:100,0;`. The center button (▶▶) toggles jog speed mode:
  **Fast** (default, button shows its normal color) pauses 0.5s between
  move and stop each cycle; **Normal** (tap ▶▶ once to switch, button
  turns red) has no deliberate pause - though a small ~50ms minimum gap
  is enforced regardless of mode, since a literal zero-delay repeating
  loop would freeze the app's UI thread and flood the connection. Tap ▶▶
  again to switch back to Fast.
- **Stop** (next to Start cut) sends `;BD:100,6;` and is unaffected by jog
  mode - it always fires immediately. **Pause** sends `;BD:100,7;`.
- **Test** sends `;SYSTEST8,0;` to trigger the machine's self-test.
- The LED button and its `BD:104,<r>,<g>,<b>;` color-cycle command have
  been removed entirely - the UI slot is left blank for possible future use.

If your plotter behaves differently than described above (e.g. the jog pad
doesn't respond, or has different ack strings), the fix is almost certainly
just in `PlotterClient.kt` - it's a small, self-contained file.

## What changed vs. the original app
- No more `WRITE_EXTERNAL_STORAGE` / `MOUNT_UNMOUNT_FILESYSTEMS` / other
  legacy permissions - file selection uses the modern Storage Access
  Framework (`ACTION_OPEN_DOCUMENT`), which works fine under scoped storage.
- `targetSdk 34` instead of an unset/ancient target, so it isn't blocked by
  the Android 14+ install-time SDK floor.
- No native/E4A runtime dependency - plain AndroidX + Kotlin coroutines.

## Building it
This project wasn't compiled in the sandbox this was written in (no Android
SDK/Gradle available there) - open it in a real environment to build:
1. Open this folder in **Android Studio** (Koala/2024.1 or newer recommended).
2. Let Gradle sync - it will download the Android Gradle Plugin, Kotlin, and
   the AndroidX libraries listed in `app/build.gradle.kts`.
3. Run on a device/emulator, or **Build > Generate Signed App Bundle/APK**
   to get an installable APK.

A launcher icon is now included (adaptive icon XML for API 26+, PNG
fallbacks for older devices) - an original design, not copied from any
manufacturer's branding. Android Studio's Image Asset tool can regenerate
or restyle it if you want a different look later.
