# Car Voltage Monitor

Car Voltage Monitor is a standalone Android widget/app for ATOTO/FYT/SYU-style Android head units. It displays the vehicle voltage reported internally by the head unit and keeps the widget updated in the background.

This was built and tested around an ATOTO S8G2104PR Android 10 head unit using FYT/SYU system components.

## What it shows

The widget displays:

```text
13.2 V
Last read 6:47 PM
```

The voltage value comes from the head unit's own SYU/FYT voltage updates, not from OBD-II, Bluetooth, or an external ESP32.

## How it works

The head unit's SystemUI/SYU layer writes voltage updates to logcat. The useful log line looks like this:

```text
msg : 0 114 ints: [132]
```

The app watches logcat for SYU voltage messages where the update code is `114`.

The raw value is converted like this:

```text
132 = 13.2 V
131 = 13.1 V
125 = 12.5 V
```

In code:

```text
volts = raw / 10.0
```

## Main components

### `VoltageLogService`

`VoltageLogService` is the foreground service that reads logcat and parses voltage updates.

It watches for messages like:

```text
msg : 0 114 ints: [132]
```

Then it stores the latest voltage and tells the widget to update.

### `AtotoKeepAliveService`

`AtotoKeepAliveService` keeps the voltage reader alive in the background. 

It is exported with ATOTO-style service actions so the head unit can resolve and restart it:

```text
com.atoto.keepalive
com.atoto.keepaliveservice.keepAlive
com.orbitaldelta.carvoltage.keepAlive
com.orbitaldelta.carvoltage.wakeup
com.orbitaldelta.carvoltage.START_SCAN
com.orbitaldelta.carvoltage.WIDGET_RESTART
```

### `BootReceiver`

`BootReceiver` listens for boot and quick-boot style startup events and starts the keepalive service.

Supported startup actions include:

```text
android.intent.action.BOOT_COMPLETED
android.intent.action.LOCKED_BOOT_COMPLETED
android.intent.action.MY_PACKAGE_REPLACED
android.intent.action.QUICKBOOT_POWERON
com.htc.intent.action.QUICKBOOT_POWERON
```

### `CarVoltageWidgetProvider`

The widget displays the latest voltage and last-read time. Tapping the widget restarts the keepalive chain and kicks the voltage reader.

## ATOTO / FYT / SYU notes

This app relies on behavior specific to ATOTO/FYT/SYU Android head units.

Known details from the tested unit:

```text
Android version: 10
Device/model family: S8G2104PR / ums512_1h10_Natv
SYU/FYT voltage update code: 114
Logcat raw voltage format: msg : 0 114 ints: [132]
Voltage conversion: raw / 10.0
```

The normal Android battery APIs are not useful on this head unit. `dumpsys battery` may show fake or empty values, so this app does not use Android's battery API.

## Required ADB setup

The app needs logcat access to read the internal SYU voltage messages. After installing the app, run these commands from your Android platform-tools folder.

On Windows PowerShell:

```powershell
.\adb.exe shell pm grant com.orbitaldelta.carvoltage android.permission.READ_LOGS
.\adb.exe shell appops set com.orbitaldelta.carvoltage READ_LOGS allow
```

Recommended background/keepalive permissions:

```powershell
.\adb.exe shell cmd deviceidle whitelist +com.orbitaldelta.carvoltage

.\adb.exe shell appops set com.orbitaldelta.carvoltage RUN_IN_BACKGROUND allow
.\adb.exe shell appops set com.orbitaldelta.carvoltage RUN_ANY_IN_BACKGROUND allow
.\adb.exe shell appops set com.orbitaldelta.carvoltage START_FOREGROUND allow
.\adb.exe shell appops set com.orbitaldelta.carvoltage WAKE_LOCK allow
```

Then launch the app once:

```powershell
.\adb.exe shell am start -n com.orbitaldelta.carvoltage/.MainActivity
```

## Useful debug commands

Check whether the services are running:

```powershell
.\adb.exe shell dumpsys activity services com.orbitaldelta.carvoltage
```

Expected services:

```text
AtotoKeepAliveService
VoltageLogService
```

Check whether the package is whitelisted from device idle:

```powershell
.\adb.exe shell dumpsys deviceidle whitelist | Select-String "carvoltage"
```

Check appops:

```powershell
.\adb.exe shell cmd appops get com.orbitaldelta.carvoltage
```

Watch voltage app logs:

```powershell
.\adb.exe logcat | Select-String -Pattern "CarVoltage|AtotoKeepAliveService|VoltageLogService|Parsed voltage|msg :  0  114|updateCode =  114"
```

Watch only likely SYU voltage messages:

```powershell
.\adb.exe logcat | Select-String -Pattern "msg :  0  114|updateCode =  114"
```

Manually start the keepalive service:

```powershell
.\adb.exe shell am startservice -n com.orbitaldelta.carvoltage/.AtotoKeepAliveService -a com.orbitaldelta.carvoltage.WIDGET_RESTART
```

Manually start/kick the voltage reader:

```powershell
.\adb.exe shell am startservice -n com.orbitaldelta.carvoltage/.VoltageLogService -a com.orbitaldelta.carvoltage.KICK
```

## Verifying the ATOTO keepalive resolver

This app should expose ATOTO-style keepalive service actions. To confirm:

```powershell
.\adb.exe shell dumpsys package com.orbitaldelta.carvoltage | Select-String "com.atoto.keepalive|com.atoto.keepaliveservice.keepAlive|carvoltage.START_SCAN|carvoltage.WIDGET_RESTART"
```

You should see the actions listed under the service resolver table for `AtotoKeepAliveService`.

## Known limitations

This app depends on the head unit producing SYU/FYT voltage logcat messages. If your unit does not emit messages like:

```text
msg : 0 114 ints: [132]
```

then the app will not have a voltage source.

Some ATOTO/FYT/SYU units aggressively kill third-party apps after long sleep. The app includes a keepalive service, boot receiver, widget tap restart, and ATOTO-style keepalive actions, but some firmware versions may still require manual background permission/whitelist changes through ADB.


