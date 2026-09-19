# Android remote controller

An experimental Android app for controlling a second Android device over ADB
TCP when a desktop computer is not available.

The controller app implements the Android-side ADB transport, authentication,
saved connections, video rendering, touch input, app launching, and adaptive
resolution. It uses a separate ADB transport for controls, selects a hardware
AVC or HEVC decoder with low-latency settings when available, and uses a
bandwidth-friendly 4 Mbps stream baseline without reducing the configured
resolution.
ADB forwarding is the default transport. Each saved connection may opt into a
direct TCP video and control transport for lower latency, but that mode exposes
a plaintext target-side port and should only be used on a trusted private
network. Direct TCP uses a fresh session token and still uses ADB to start the
temporary server.
It builds the existing scrcpy server module included in this repository and
uses the scrcpy video and control protocols. The connection editor can prefer
H.265 when the controller has a hardware decoder, reducing network traffic at
the same configured resolution. This repository is an independent project and
is not maintained by Genymobile.

## Build

Install Android SDK platform 36 and set `ANDROID_HOME` or
`ANDROID_SDK_ROOT`, then run:

```sh
./gradlew :android-app:assembleDebug
```

On Windows PowerShell:

```powershell
.\gradlew.bat :android-app:assembleDebug
```

The debug APK is written to
`android-app/build/outputs/apk/debug/android-app-debug.apk`.

Run the checks with:

```sh
./gradlew :android-app:testDebugUnitTest :android-app:lintDebug :android-app:assembleRelease
```

## Usage

Enable ADB over TCP on the target Android device, install the APK on the
controller device, and add the target's local IP address and ADB port. The
target and controller must be on a trusted network. Keep the default ADB
forwarding transport unless lower latency is more important than the additional
network exposure of direct TCP.

## License

This project and the included server module are distributed under the Apache
License 2.0. The server module is derived from the scrcpy project and retains
its upstream notices.
