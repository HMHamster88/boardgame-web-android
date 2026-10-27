# boardgame-web-android

An Android app that runs the existing [`boardgame-web-ts`](../boardgame-web-ts) launcher inside
[Node.js Mobile](https://github.com/nodejs-mobile/nodejs-mobile) and displays the web UI in a
full-screen `WebView`.

The app deliberately keeps the existing launcher in charge of core-module and game-module version
checks. The launcher downloads updates from GitHub into the app's private storage exactly like the
desktop version.

## How it works

1. `npm run prepare-node-project` builds `../boardgame-web-ts/launcher` and copies the generated
   `launcher.mjs` plus the Android bootstrap files into `app/src/main/assets/nodejs-project`.
2. `npm run prepare-libnode` downloads the Node.js Mobile 18.20.4 Android archive and extracts
   `libnode.so` (all supported ABIs) and the Node headers into `app/libnode`.
3. On first launch (and after each APK update) `NodeRuntime` extracts the JS project from assets into
   the app's private `files/nodejs-project` directory and starts Node on a background thread.
4. `main.mjs` points the launcher at the private data directory, installs compatibility helpers, and
   imports `launcher.mjs`.
5. The launcher checks for/downloads the core module, starts it on `127.0.0.1:8000`, and the
   `MainActivity` `WebView` loads the UI once the server is ready.
6. A floating terminal button opens `ServerConsoleActivity`, which shows live Node stdout/stderr and
   forwards typed commands to the core module's stdin-based CLI.

## Server console

Use the terminal button in the bottom-right of the main screen (above the refresh button). The
activity shows the server's live output with stderr highlighted in red, and the input row writes
lines to Node's `stdin`.

Useful commands from the core module's CLI:

```
help
game-modules list
users list
users roles add <user-id> ADMIN
exit
```

`exit` shuts down the Node process. Node.js Mobile cannot restart the runtime in the same app
process, so after `exit` you need to force-stop and reopen the app.

The console keeps the most recent 200,000 characters of output in memory, so opening it later still
shows the startup logs.

### Android compatibility layer

Node.js Mobile 18.20.4 is the newest available build and differs from desktop Node 18 in a few ways
that the current core module relies on:

| Problem | Workaround |
| --- | --- |
| `node:sqlite` does not exist before Node 22 | `sqlite-shim.mjs` implements `DatabaseSync`/`StatementSync` on top of [sql.js](https://sql.js.org/) (WebAssembly). The database is persisted after each write. |
| V8 is built without ICU and rejects `\p{ID_Start}` / `\p{ID_Continue}` regex escapes. The core module bundles `path-to-regexp`, which uses them. | `runtime-loader.mjs` rewrites those escapes to ASCII equivalents in loaded ESM sources. |
| `globalThis.crypto` is not exposed | `main.mjs` installs `node:crypto`'s Web Crypto implementation. |

These helpers live in `node-project/` and are copied into the APK by the preparation script.

## Requirements

- Node.js 18.19+ and npm
- JDK 17–21. Gradle 8.11.1 fails to compile its Groovy build scripts with JDK 25, so set
  `JAVA_HOME` (or Android Studio's Gradle JDK) to a supported JDK before building.
- Android SDK:
  - Platform 35
  - Build-Tools 35.0.0
  - NDK 26.1.10909125
  - CMake 3.22.1
- Android 7.0 (API 24) or newer on `arm64-v8a`, `armeabi-v7a`, or `x86_64`

## Setup and build

The `boardgame-web-ts` workspace dependencies must be installed first:

```bash
cd boardgame-web-ts
npm install
```

Then:

```bash
cd ../boardgame-web-android
npm install
npm run setup
```

`npm run setup` performs both preparation steps (it downloads the ~57 MB Node.js Mobile archive on
the first run). The Gradle build also runs both preparation scripts automatically via `preBuild`, so
opening the project in Android Studio and pressing Run works as well.

Build from the command line:

```bash
npm run build:debug
# or directly:
#   Windows:      gradlew.bat assembleDebug
#   macOS/Linux:  ./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

If the launcher was already built and you only changed the Android-side Node files:

```bash
# macOS / Linux
BOARDGAME_SKIP_LAUNCHER_BUILD=1 npm run prepare-node-project

# Windows PowerShell
$env:BOARDGAME_SKIP_LAUNCHER_BUILD = '1'; npm run prepare-node-project
```

## Release build

Set `JAVA_HOME` to JDK 17–21 before building (JDK 25 does not work with Gradle 8.11.1).

Build a universal release APK:

```bash
npm run build:release
```

Output:

```text
app/build/outputs/apk/release/app-release-unsigned.apk
```

Build a Play Store app bundle:

```bash
node scripts/gradle.mjs bundleRelease
```

Output:

```text
app/build/outputs/bundle/release/app-release.aab
```

The universal APK is about 147 MB; the AAB is about 99 MB and lets the Play Store split native
libraries per ABI.

The generated APK and AAB are unsigned. Create an upload keystore once:

```powershell
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v -keystore boardgame-release.jks `
  -alias boardgame -keyalg RSA -keysize 2048 -validity 10000
```

Keep the keystore and its passwords out of version control.

Sign the APK with `apksigner` from the Android build-tools:

```powershell
$apksigner = "$env:LOCALAPPDATA\Android\Sdk\build-tools\35.0.0\apksigner.bat"

& $apksigner sign `
  --ks boardgame-release.jks `
  --ks-key-alias boardgame `
  --out app/build/outputs/apk/release/boardgame-release-signed.apk `
  app/build/outputs/apk/release/app-release-unsigned.apk
```

Verify the signature with:

```powershell
& $apksigner verify --print-certs app/build/outputs/apk/release/boardgame-release-signed.apk
```

For Play Store distribution, configure a release signing config (or use Play App Signing) and upload
the signed AAB. The project currently has no release signing config, so `bundleRelease` produces an
unsigned bundle.

> Important: the Android app no longer contains the runtime workaround for the old core module. Publish
> the fixed core module release first (a version newer than `1.0.13`), then install the release APK.
> The launcher downloads the new core on startup.

## First launch

The app needs internet access on the first launch: the launcher downloads the latest core module
(`boardgame-web-ts-core-module-*.zip`, about 3.4 MB) and the default game modules from GitHub. They
are stored in the app's private data directory:

```
/data/user/0/com.hmhamster.boardgameweb/files/boardgame-web-ts/
├── core/
├── games-modules/
└── db.sqlite
```

After that, previously downloaded modules are reused offline. Clearing the app's data resets the
database and downloaded modules.

Node stdout/stderr and the app's own logs appear in logcat:

```bash
adb logcat -s NODEJS-MOBILE BoardgameNode BoardgameWeb
```

The app only allows cleartext traffic to `127.0.0.1` / `localhost` (see
`res/xml/network_security_config.xml`); Node's own HTTPS downloads are unaffected.

## Project layout

```
boardgame-web-android/
├── node-project/                 # JS files embedded in the APK
│   ├── main.mjs                  # Android entry point, env setup, polyfills
│   ├── runtime-loader.mjs        # ESM loader: sqlite redirect + Unicode regex patch
│   └── sqlite-shim.mjs           # sql.js based node:sqlite implementation
├── scripts/
│   ├── prepare-libnode.mjs       # download/extract Node.js Mobile
│   ├── prepare-node-project.mjs  # build launcher + assemble assets
│   ├── gradle.mjs                # cross-platform Gradle wrapper launcher
│   └── clean.mjs
├── app/
│   ├── src/main/
│   │   ├── java/com/hmhamster/boardgameweb/
│   │   │   ├── MainActivity.java         # WebView + server readiness polling
│   │   │   ├── ServerConsoleActivity.java# live stdout/stderr + CLI input
│   │   │   └── NodeRuntime.java          # runtime, output buffer, JNI bridge
│   │   ├── cpp/native-lib.cpp    # node::Start, stdin/stdout pipes, logcat, JNI output
│   │   ├── assets/               # generated nodejs-project
│   │   └── res/
│   ├── CMakeLists.txt
│   └── build.gradle
├── gradle/                       # Gradle wrapper
└── build.gradle
```

## Updating components

- **Launcher**: change `../boardgame-web-ts/launcher`, then rebuild. The assets are regenerated.
- **Android bootstrap / shims**: edit `node-project/`, then rebuild.
- **Node.js Mobile**: update `NODEJS_MOBILE_VERSION` and `ARCHIVE_SHA256` in
  `scripts/prepare-libnode.mjs` (and the NDK version if the release requires it).
- **Core module**: published as a GitHub release of `boardgame-web-ts`; the launcher checks it on
  every start because `CHECK_FOR_UPDATES=true`.

## Known limitations

- The boardgame-web core server listens on all network interfaces (the launcher does not pass a host
  to `app.listen`). The app itself only connects to `127.0.0.1`, but devices on the same network can
  reach port 8000. Use trusted networks only, or restrict the device's network access.
- The debug APK is a universal APK of about 147 MB because it contains `libnode.so` for three ABIs.
  Use `./gradlew bundleRelease` and Play Store ABI splits for distribution.
- Node.js Mobile 18.20.4 native libraries use 4 KB ELF page alignment. Android devices configured
  with 16 KB pages may reject or fail to load them; verify on such devices before release.
- Node.js Mobile can only be started once per process and cannot be restarted. The app guards
  against double starts with a process-wide flag.
- There is no foreground service. Android may stop the Node process when the app is killed or the
  system reclaims memory; reopening the app restarts it.
- The SQLite shim writes the whole database after each mutation. This is fine for the small
  boardgame database but is not a general-purpose SQLite replacement.
