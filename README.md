# Web Application Manager (WAM)

**Create, install, run, and manage web applications on Android — all from one app.**

Web Application Manager (WAM) is a native Android application that lets you create, import, install, run, and distribute web applications packaged as `.wap` files.

WAM includes its own built-in browser, so your web applications can run directly inside the app without requiring a separate APK for every application.

## Features

### 📦 Web Application Packages (.wap)

WAM uses its own `.wap` format, called Web Application Package.

There are two types:

- **Offline WAP:** A ZIP archive containing a `manifest.json` file and your HTML, CSS, JavaScript, images, and other assets. Runs without an internet connection.
- **Online WAP:** A lightweight package containing a manifest and an optional icon. Opens an HTTPS website using your configured network permissions.

No separate Android APK is needed for individual web applications.

### 🌐 Built-in browser

Run your applications directly inside WAM using its built-in viewer.

You can also switch to your device's external browser:

- Chrome / Chromium
- Firefox

External browser previews use a temporary local preview server. Offline applications can still run without requiring internet access.

The built-in viewer is the default runtime.

### 🛠️ Project editor

Create and manage web projects directly on your Android device.

Available tools include:

- Create HTML, CSS, and JavaScript files.
- Create folders and other file types.
- Import individual files or entire folders.
- Edit code with syntax highlighting and line numbers.
- Search and sort files.
- Duplicate, rename, and delete files.
- Export files to device storage.
- Mount a project to a folder and synchronize changes.

You can build and test a project without needing a computer.

### 🔐 Per-application permissions

Every installed WAP has its own permission settings.

Permissions are disabled by default. You decide which capabilities each application can access.

Available permissions include:

| Permission | What it allows |
|---|---|
| Camera | Capture photos using the Android camera |
| Microphone | Record audio |
| Text to speech | Read text aloud |
| Internet | Control network access |
| Send data | Allow requests that transmit data to servers |
| Vibrate | Use device vibration |
| Device info | Access basic device information |
| Clipboard | Read and write copied text |
| Notifications | Send Android notifications |
| Share | Open the Android sharing menu |
| Export files | Save application data to device storage |

**Important:** Clipboard access and permission to send data over the network are considered dangerous capabilities. They require additional confirmation before being enabled.

Camera and microphone access also require the relevant Android runtime permission.

### 📱 Android widgets

Add installed WAM applications to your home screen using Android widgets.

Widgets display an adaptive icon with the application's icon and name, matching the look of normal launcher apps.

Each widget occupies one launcher space (1×1).

### 📁 File management

WAM includes a file manager for working with project files and application data.

You can:

- Import files from device storage.
- Export files to another location.
- Mount a project to an external folder.
- Synchronize changes between WAM and the mounted folder.
- Browse hidden files and folders.
- Search and sort your files.

### 🧩 JavaScript device API

WAM provides a JavaScript bridge through `window.WAP`.

Web applications can use supported Android features through the API, subject to their individual permission settings.

Available APIs include:

- `WAP.camera.capture()` — Capture a photograph.
- `WAP.mic.record()` — Record audio.
- `WAP.tts.speak()` — Read text aloud.
- `WAP.device.vibrate()` — Vibrate the device.
- `WAP.device.info()` — Retrieve basic device information.
- `WAP.clipboard.read()` / `write()` — Access the clipboard.
- `WAP.notify()` — Display Android notifications.
- `WAP.share.text()` / `share.file()` — Share text or files.
- `WAP.storage.export()` — Export saved application data.
- `WAP.net.send()` — Send permitted HTTPS requests.
- `WAP.permissions.get()` — Check the application's granted permissions.
- `WAP.data.readDataUrl()` — Read saved binary files as data URLs.

A new-project option can automatically include an `api-examples.html` file with working examples and a live status log.

See the in-app JS API reference for usage details.

---

## Getting started

### 1. Install WAM

Download the appropriate APK from the project's GitHub Releases page.

Install it on your Android device and open the application.

### 2. Create your first project

1. Open the **Projects** section.
2. Select **New project**.
3. Enter a project name.
4. Create your project.
5. Open the file manager to add or edit files.

New projects can include ready-to-use JavaScript API examples.

### 3. Run your application

Open your project and preview it.

WAM will launch the application using the built-in browser by default.

You can change the preview engine in Settings if you prefer to use your device's browser.

### 4. Package your application

Once your project is ready, package it as a `.wap` file using the project's packaging option.

The resulting package can be imported into another WAM installation and shared with other Android users.

### 5. Import an existing WAP

1. Open the **Apps** section.
2. Select **Import WAP**.
3. Choose a `.wap` file from your device.
4. Open the imported application from your Apps list.

---

## Application settings and security

WAM gives you control over how individual applications interact with your device.

To change an application's permissions:

1. Open **Apps**.
2. Long-press the application.
3. Select **App settings**.
4. Enable or disable the permissions you need.

You can also use the built-in permission examples to apply presets for common application types.

**Security advice:** Only install WAP packages from sources you trust. A WAP can contain JavaScript, and enabling permissions gives that application access to the corresponding device features. Review its permissions before granting access.

For network access, choose an appropriate policy for each application:

- Inherit
- Allow
- Ask
- Deny

The separate **Requests to send data** permission controls whether an application can transmit data using supported requests. It is disabled by default.

---

## Requirements

- Android device.
- Android WebView provider for the built-in viewer.
- Chrome, Chromium, or Firefox for external browser previews.
- Internet connection for downloading dependencies when building from source and for using online applications.

Offline WAP applications do not require an internet connection to run.

---

## Frequently asked questions

**Do I need to install an APK for every WAP application?**

No. WAM runs WAP applications directly. Individual WAPs do not need their own Android APK.

**Can I use applications without internet?**

Yes. Offline WAP packages contain the files required to run the application locally.

**Can I use my own HTML, CSS, and JavaScript?**

Yes. You can create or import your own project files.

**Can WAP applications access my camera or microphone?**

Yes, but only when the relevant per-app permissions are enabled and Android grants the required runtime permission.

**Can I use Chrome instead of the built-in browser?**

Yes. Change the preview engine in Settings.

**Can I share my applications?**

Yes. Package a project into a `.wap` file and share that file with another WAM user.

---

## Version information

**Current version:** 1.7.0  
**Version code:** 9  
**Platform:** Android  
**Package format:** `.wap`

## Credits

Created by **iamtungingit** using **GLM 5.3-Flash**.
