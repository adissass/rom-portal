# RomPortal

RomPortal is an Android app that turns your phone into a local web portal for managing emulator files from another device on your network.

## Problem
Moving ROMs, BIOS files, saves, and states onto handheld devices is often slow and awkward.

Common pain points:
- plugging/unplugging storage repeatedly
- dealing with limited file manager UX on-device
- bouncing through cloud apps just to move files on the same LAN

## Solution
RomPortal gives you a browser-based file manager backed by a folder you choose on Android.

Flow:
1. Pick a root folder in the app (SAF picker).
2. Start the local server.
3. Open the shown URL from another device on the same network.
4. Log in with the generated PIN.
5. Manage files in the browser.

## Current MVP Features
- SAF-based root folder selection (no `MANAGE_EXTERNAL_STORAGE`)
- LAN HTTP server with PIN login and session cookie auth
- Browser file manager UI with:
  - browse directories
  - create folder
  - rename
  - delete
  - download
  - upload (sequential for multi-file selection)
- Security baseline:
  - in-memory sessions with TTL and inactivity expiry
  - brute-force backoff for repeated bad PIN attempts
  - traversal-safe path validation on API inputs

## Prerequisites
Install these before using RomPortal:
- Android Studio (latest stable)
- Android SDK Platform-Tools (`adb`)
- An Android emulator (ARM image recommended on Apple Silicon) or a physical Android device

Recommended checks:
```bash
adb version
```

If `adb` is not found, add your Android SDK `platform-tools` directory to your shell `PATH`.

## Quick Start (Use the Product)
### 1) Run the app
1. Open project in Android Studio.
2. Run `app` on emulator or physical device.
3. Tap `Pick Folder` and choose your ROM root.
4. Tap `Start Server`.

The app will display:
- `Server URL: http://<ip>:8080`
- `PIN: <6 digits>`

### 2) Open from another device
- Same Wi-Fi or hotspot: open the displayed URL in a browser.
- Log in with PIN.

### 3) Use the web file manager
- Navigate folders
- Upload/download files
- Rename/delete entries

## Emulator Access from Laptop (Apple Silicon)
If you run RomPortal on an emulator and want to access it from your laptop browser, use adb port forwarding.

Commands:
```bash
adb devices
adb forward tcp:8080 tcp:8080
```

Then open:
- `http://127.0.0.1:8080`

Notes:
- Use an ARM emulator image on Apple Silicon.
- Current MVP server policy is foreground-only: server stops when app backgrounds.

## API Endpoints (MVP)
Public:
- `GET /health`
- `GET /login`
- `POST /login`
- `GET /` (redirects to `/login` when unauthenticated)

Authenticated (`rs_session` cookie required):
- `GET /api/ping`
- `GET /api/list?path=<relativePath>`
- `POST /api/mkdir` (`path` form field)
- `POST /api/rename` (`path`, `newName` form fields)
- `POST /api/delete` (`path` form field)
- `GET /api/download?path=<relativePath>`
- `POST /api/upload?path=<relativePath>` (`multipart/form-data`, single file per request)

`GET /health` response fields:
- `status`: server health status (`ok` for MVP)
- `serverStartedAtEpochMs`: Unix epoch milliseconds when server started
- `uptimeMs`: elapsed server uptime in milliseconds
- `rootSelected`: whether a SAF root is currently selected
- `rootUri`: sanitized SAF tree identifier or `null`
- `freeSpaceBytes`: best-effort free-space estimate available to the app, or `null`
- `activeSessions`: current in-memory authenticated session count

## Security and Product Limits (MVP)
- LAN HTTP only (no TLS in-app yet)
- Session store is in-memory and resets when server stops
- Cookie uses `HttpOnly` and `SameSite=Lax`
- SAF root containment enforced for all file operations
- Upload size default is storage-bounded unless max limit is configured

## Troubleshooting
### Run button is disabled in Android Studio
- Sync project with Gradle files.
- Ensure run configuration points to module `app`.
- Ensure emulator/device is selected.

### Browser cannot connect to server
- Confirm app is still in foreground and server is started.
- Confirm same network/hotspot.
- For emulator access from laptop, use adb forwarding.

### Login appears to do nothing
- Re-open `/login`, re-enter current PIN from app UI.
- If too many invalid attempts were made, wait for retry delay.

## Developer Appendix
### Local verification commands
```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

### Operability checks
Verify `/health` and `X-Request-ID`:
```bash
curl -i --http1.1 http://127.0.0.1:8080/health
curl -i --http1.1 http://127.0.0.1:8080/health | rg -i "x-request-id"
```

View RomPortal structured request logs only:
```bash
adb logcat --pid $(adb shell pidof -s com.romportal.app) | rg "requestId=|request logging|Exception|error"
```

Expected structured line shape:
```text
requestId=<uuid> method=GET path=/health status=200 latencyMs=<ms> bytesIn=<n|-1> bytesOut=<n|-1>
```

### Runtime smoke test (curl)
```bash
curl -i -c cookies.txt -d "pin=YOUR_PIN" http://127.0.0.1:8080/login
curl -i -b cookies.txt "http://127.0.0.1:8080/api/list?path="
curl -i -b cookies.txt -X POST -d "path=TestDir" http://127.0.0.1:8080/api/mkdir
curl -i -b cookies.txt -X POST -d "path=TestDir" -d "newName=TestDir2" http://127.0.0.1:8080/api/rename
curl -i -b cookies.txt -X POST -d "path=TestDir2" http://127.0.0.1:8080/api/delete
curl -i "http://127.0.0.1:8080/api/list?path="
curl -i -b cookies.txt "http://127.0.0.1:8080/api/list?path=../"
```

Expected highlights:
- unauthenticated `/api/*` -> `401`
- traversal path input -> `400`

### Validation results template
Fill in after test runs:

Transfer speed test:
- file size:
- upload time:
- download time:
- measured throughput:

Hotspot test:
- topology (phone/emulator/laptop):
- result (pass/fail):
- notes:
