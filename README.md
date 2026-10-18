# Hven

A private assistant for Android that runs a small language model entirely on your phone. No cloud, no account, no dependency on Google services.

Hven supports only a few models, and each one is tested end to end on real phones. The goal is an assistant that is fast and reliable rather than powerful.

> **Status: early development.** The app builds and runs, but it doesn't do anything useful yet. Next up: a local chat.

## Requirements

- Android 10 or newer
- 64-bit ARM phone (arm64-v8a) with ARMv8.2 dot-product instructions
- 6 GB of RAM

## Building

You need JDK 21 and the Android SDK, with these packages installed:

- `platform-tools`
- `platforms/android-37.0`
- `build-tools/36.1.0`
- `ndk/29.0.14206865`
- `cmake/3.31.6`

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew installDebug   # builds the app and installs it on a connected phone
```

## License

Not decided yet.
