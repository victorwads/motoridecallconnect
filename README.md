# MotoRideCallConnect

This repository contains an Android application whose package name is
`dev.wads.motoridecallconnect`.

## Project Purpose

MotoRideCallConnect is an Android app designed to enable voice-based
communication between motorcycle riders.  It integrates WebRTC for real-
time media, Firebase for authentication, Firestore and analytics, and
uses Whisper/ML components for speech-to-text and voice activity
detection.

## ADB Logcat Monitoring

For debugging and monitoring purposes there is a helper script that
starts `adb logcat` in the background for every connected device.  Each
device's output is filtered to include only messages coming from the
application (by package name) and is written to a separate file.

### Usage

1. Connect your Android device(s) via USB and ensure `adb devices`
   shows them.
2. Run the monitoring script from the project root:
   ```sh
   ./scripts/start-logcat.sh
   ```
   It will create a `logs/` directory (if it doesn't exist) and spawn a
   background logcat for each device.  The script continues running in a
   loop, checking every five seconds for newly connected devices.
   The first thing the helper does is kill any previous helper logcat
   processes (those started by earlier invocations) so you won't accidentally
   have two processes racing for the same output file.
3. To view live logs for a given device, use `tail -f` on the
   corresponding file:
   ```sh
   tail -f logs/<device-id>.log
   ```
4. Stop the script with `Ctrl-C` or by killing the process.

### Log Files

- Location: `logs/`
- File names: `<device-id>.log` (device IDs are the ones reported by
  `adb devices`)
- Contents: only log lines that mention the package
  `dev.wads.motoridecallconnect`, which effectively restricts output to
  the application running on the device.

These logs can be watched by any automated agent or manually inspected
for debugging.

---

Feel free to add more instructions or scripts as needed by tooling or
agents working with this repository.