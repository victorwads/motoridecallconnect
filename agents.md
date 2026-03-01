# Agents

This file is intended for any automated agents that interact with the
repository.

## Log Monitoring

See `README.md` for instructions, but here is the summary:

- `./scripts/start-logcat.sh` will start background `adb logcat`
  processes for all currently connected devices and any that are
  connected later.
- Log files appear under `logs/` and are named after the device ID
  (`<device-id>.log`).
- Each log file only contains lines filtered by the application package
  `dev.wads.motoridecallconnect`. Agents can `tail -f` or otherwise
  scan these files to watch for runtime events from the app.
The helper script automatically kills any previously spawned logcat/grep
processes before starting fresh ones, so re-running it won’t leave
orphaned readers blocking new log files.
Agents should use this mechanism to gather runtime logs when running
or testing the Android application on devices or emulators.

## Manual Testing with Real Devices

This section explains how to drive two physical Android phones entirely
through ADB commands — no manual touching of the screens needed.

### Prerequisites

1. Two Android devices connected via USB or ADB over TCP.
2. Run `adb devices` to list them and note their serial/ID strings.
3. The app must already be installed on both devices. If not, build and
   install first:
   ```sh
   ./gradlew installDebug
   ```
   This installs on **all** connected devices simultaneously.
4. Start the logcat monitor so you can watch both devices:
   ```sh
   ./scripts/start-logcat.sh
   ```

### Opening the App

```sh
adb -s <device-id> shell am start -n dev.wads.motoridecallconnect/.MainActivity
```

### Reading the UI — uiautomator dump

To discover what is currently on screen (button names, text fields,
coordinates), dump the UI hierarchy:

```sh
adb -s <device-id> shell uiautomator dump /sdcard/ui.xml
adb -s <device-id> shell cat /sdcard/ui.xml
```

Parse the resulting XML to find clickable elements. Each `node` contains
`text`, `resource-id`, `content-desc`, `bounds`, and `clickable`
attributes. Use the `bounds` attribute (e.g. `[0,200][540,280]`) to
calculate the center tap coordinates:
`tap_x = (left + right) / 2`, `tap_y = (top + bottom) / 2`.

### Tapping the Screen

```sh
adb -s <device-id> shell input tap <x> <y>
```

### UI Labels (Two Languages)

The app is localized in English and Portuguese (pt-BR). When parsing
`uiautomator dump` output, look for these strings:

| Purpose           | English (values/strings.xml) | Português (values-pt-rBR/strings.xml) |
|-------------------|------------------------------|---------------------------------------|
| **Bottom nav**    |                              |                                       |
| Home tab          | Home                         | Início                                |
| Pair tab          | Pair                         | Parear                                |
| Friends tab       | Friends                      | Amigos                                |
| History tab       | History                      | Histórico                             |
| Config tab        | Config                       | Config                                |
| **Pairing screen**|                              |                                       |
| Host tab          | Host                         | Host                                  |
| Client tab        | Client                       | Cliente                               |
| Connect button    | Connect                      | Conectar                              |
| **Trip screen**   |                              |                                       |
| Start Trip        | Start Trip                   | Iniciar Viagem                        |
| End Trip          | End Trip                     | Finalizar Viagem                      |
| Disconnect        | Disconnect                   | Desconectar                           |
| LIVE indicator    | ● LIVE                       | ● AO VIVO                            |
| **Transport**     |                              |                                       |
| Local Network     | Local Network                | Rede local                            |
| Wi-Fi Direct      | Wi-Fi Direct                 | Wi-Fi Direct                          |
| Internet          | Internet                     | Internet                              |

To find a button regardless of language, search the dumped XML for both
possible texts. For example, to find the "Pair" tab:
```
grep -oE 'text="(Pair|Parear)"' ui.xml
```

### Typical Agentic P2P Test Flow

Below is the recommended step-by-step sequence for an agent to test a
local-network Host→Client connection.

1. **List devices**
   ```sh
   adb devices
   ```
   Pick two device IDs: `DEVICE_HOST` and `DEVICE_CLIENT`.

2. **Install latest build on both**
   ```sh
   ./gradlew installDebug
   ```

3. **Open app on both devices**
   ```sh
   adb -s $DEVICE_HOST shell am start -n dev.wads.motoridecallconnect/.MainActivity
   adb -s $DEVICE_CLIENT shell am start -n dev.wads.motoridecallconnect/.MainActivity
   ```

4. **Navigate to Pair screen on both** — dump UI, find the "Pair" /
   "Parear" tab in the bottom navigation bar, tap its center coordinates.

5. **Make DEVICE_HOST the Host** — on DEVICE_HOST, find and tap the
   "Host" tab.  The device will start advertising via mDNS.

6. **Make DEVICE_CLIENT the Client** — on DEVICE_CLIENT, find and tap
   the "Client" / "Cliente" tab.

7. **Wait for discovery** — dump DEVICE_CLIENT UI periodically until
   the host device appears in the "Nearby devices" / "Dispositivos
   próximos" list (this usually takes 2–10 seconds).

8. **Connect** — on DEVICE_CLIENT, tap the discovered host entry, then
   tap "Connect" / "Conectar".

9. **Verify connection** — watch `logs/<device-id>.log` for
   `IceConnectionChange: CONNECTED` on both devices.  Also dump the UI
   on the Home screen and look for `● LIVE` / `● AO VIVO`.

10. **Start Trip** — on either device, tap "Start Trip" /
    "Iniciar Viagem" and verify the other device also shows the trip
    active.

11. **End Trip / Disconnect** — tap "End Trip" / "Finalizar Viagem",
    then "Disconnect" / "Desconectar".

### Screen Resolution Handling

Different devices have different screen sizes. **Never hard-code
coordinates.** Always:
1. Dump the UI with `uiautomator dump`.
2. Parse the `bounds="[left,top][right,bottom]"` attribute.
3. Calculate center: `x = (left+right)/2`, `y = (top+bottom)/2`.

### Watching Logs

While running the test, tail the log files to check for errors:
```sh
tail -f logs/$DEVICE_HOST.log | grep -i "error\|fail\|exception" &
tail -f logs/$DEVICE_CLIENT.log | grep -i "error\|fail\|exception" &
```

Key log tags to watch:
- `SignalingClient` — TCP signaling connect/disconnect/errors
- `WebRtcClient` — ICE candidate and PeerConnection events
- `AudioService` — Connection status changes, trip events
- `NsdHelper` — mDNS service registration and discovery