# BT Print

An Android application that acts as a printer bridge for thermal Bluetooth printers. It supports ESC/POS commands and can receive print jobs from other applications via Android Intents or an HTTP server.

## 🚀 Features

-   **Bluetooth Connectivity**: Connects to classic Bluetooth (SPP) thermal printers.
-   **ESC/POS Support**: A comprehensive `EscPosBuilder` for generating print commands.
-   **Intent API**: Allows other apps to send print jobs.
-   **Local HTTP Server**: An optional background service that listens for print jobs on `http://127.0.0.1:8080/print`. Web pages open in the phone's browser can call it too (CORS enabled).
-   **Browser Printing**: BT Print registers as an Android printer, so Chrome (Share → Print) or any app with a Print option can print to the thermal printer.
-   **Barcode & QR Printing**: Type a value in the app and print it as a CODE128 barcode or a QR code.
-   **Simple UI**: For scanning, pairing, and selecting a printer.

Every printout ends with a short `BTPrint by VisionWebLabs.com` footer.

## 🛠️ How to Build an APK

1.  **Using Android Studio**:
    *   Open Android Studio.
    *   Select "Open an Existing Project".
    *   Navigate to this project's root directory and click "OK".
    *   Android Studio will automatically sync the Gradle project.
    *   From the top menu, go to `Build` -> `Build Bundle(s) / APK(s)` -> `Build APK(s)`.

2.  **Using `gradlew` (Command Line)**:
    *   Navigate to the project's root directory in your terminal.
    *   To build a debug APK: `./gradlew assembleDebug`
    *   The generated APK will typically be found at `app/build/outputs/apk/debug/app-debug.apk`.

3.  **Locate the APK**:
    *   After a successful build (either via Android Studio or `gradlew`), the APK file (`app-debug.apk` for debug builds) will be located in the `app/build/outputs/apk/debug/` directory.

## 🔐 Release Signing (required for Play Store)

`app/keystore.properties` (git-ignored, never commit it) tells Gradle which keystore to sign release builds with:

```
storeFile=btprint-release.jks
storePassword=********
keyAlias=btprint
keyPassword=********
```

Copy `app/keystore.properties.example` to `app/keystore.properties` and fill in the real values, keeping the `.jks` file next to it inside `app/`. Without this file, `assembleRelease`/`bundleRelease` produce an **unsigned** build. Back up the `.jks` file and passwords somewhere durable (password manager, secure vault) — losing them means you can never publish an update to the same Play Store listing again.

```bash
./gradlew bundleRelease   # app/build/outputs/bundle/release/app-release.aab
```

## 📱 How to Use

### Printing from a browser

1.  Open BT Print, select your printer, and tap **Enable Browser Printing**.
2.  In the Android printing settings that open, turn **BT Print** on (one-time setup).
3.  In Chrome, open the page, tap **⋮ → Share → Print**, and choose **BT Print** as the printer.
4.  Pick the **58mm roll** or **80mm roll** paper size to match your printer, then tap the print button.

The page is printed as an image, so pages designed for receipt width give the best results. Blank space at the bottom of each page is trimmed automatically to save paper.

Web apps can also print directly from JavaScript with `fetch('http://127.0.0.1:8080/print', ...)` (see the HTTP server section below). Recent Chrome versions ask the user once to allow a website to access devices on the local network.

### 1. Via Intent

You can trigger a print job from another app by sending a `Broadcast Intent`.

-   **Action**: `com.visionweblabs.thermalbtprint.PRINT`
-   **Extras**:
    -   `type`: (String) `text` or `json`.
    -   `data`: (String) The content to print. For `json`, use the same command array as the HTTP server (see the JSON command format below).

#### Example: `adb`

```bash
# Plain text
adb shell am broadcast -a com.visionweblabs.thermalbtprint.PRINT -n com.visionweblabs.thermalbtprint/.receivers.PrintIntentReceiver \
  --es type text --es data "Hello World!"

# JSON commands
adb shell am broadcast -a com.visionweblabs.thermalbtprint.PRINT -n com.visionweblabs.thermalbtprint/.receivers.PrintIntentReceiver \
  --es type json --es data '[{"type":"text","text":"My Store","align":"center","bold":true,"newline":true},{"type":"cut"}]'
```

### 2. Via HTTP Server

The app runs a local HTTP server in the background. You can send a POST request to it.

**Important:** The HTTP server expects **JSON format**, NOT raw HTML or plain text. You must parse your HTML/content on the client side and send the result as a JSON array of print commands.

-   **URL**: `http://127.0.0.1:8080/print`
-   **Method**: `POST`
-   **Headers**: `Content-Type: application/json`
-   **Body**: JSON array of command objects (see format below)
-   **Response**: sent after the printer finishes, so it reflects the real result
    -   `200` `{"status":"success"}`
    -   `400` `{"status":"error","message":"..."}` for invalid JSON or request
    -   `500` `{"status":"error","message":"..."}` when printing failed (no printer selected, Bluetooth off, printer unreachable)
-   The server only accepts connections from the same device (loopback).

#### JSON Command Format

Each command in the array is an object with the following properties:

| Property   | Type      | Required | Description                                      |
|------------|-----------|----------|--------------------------------------------------|
| `type`     | String    | Yes      | Command type: `text`, `qr`, `barcode`, `cut`     |
| `text`     | String    | Yes*     | Content to print (required for text/qr/barcode)  |
| `align`    | String    | No       | `left` (default), `center`, `right`              |
| `bold`     | Boolean   | No       | `false` (default) or `true`                      |
| `size`     | String    | No       | `normal` (default), `double`, `double_height`, `double_width` |
| `newline`  | Boolean   | No       | `false` (default) or `true` (add line break)     |

#### Example: `curl`

```bash
curl -X POST http://127.0.0.1:8080/print \
  -H 'Content-Type: application/json' \
  -d '[
    {"type": "text", "text": "HTTP Print Test", "align": "center", "bold": true},
    {"type": "text", "text": "This is a test from curl.", "align": "center", "newline": true},
    {"type": "cut"}
  ]'
```

#### Example: JavaScript (Fetch API)

```javascript
const printReceipt = async () => {
  const response = await fetch('http://127.0.0.1:8080/print', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify([
      { type: 'text', text: 'TOKO MAJU JAYA', align: 'center', bold: true, size: 'double' },
      { type: 'text', text: 'Jl. Merdeka No. 123', align: 'center', newline: true },
      { type: 'text', text: '--------------------------------', newline: true },
      { type: 'text', text: 'Item 1', align: 'left' },
      { type: 'text', text: '25.000', align: 'right', newline: true },
      { type: 'qr', text: 'https://example.com', align: 'center', newline: true },
      { type: 'cut' }
    ])
  });
  
  const result = await response.json();
  console.log(result); // { status: "success" }
};
```

#### Example: PHP

```php
<?php
$data = [
    ['type' => 'text', 'text' => 'Receipt #123', 'align' => 'center', 'bold' => true],
    ['type' => 'text', 'text' => 'Item A - 50.000', 'newline' => true],
    ['type' => 'cut']
];

$ch = curl_init('http://127.0.0.1:8080/print');
curl_setopt($ch, CURLOPT_POST, true);
curl_setopt($ch, CURLOPT_POSTFIELDS, json_encode($data));
curl_setopt($ch, CURLOPT_HTTPHEADER, ['Content-Type: application/json']);
curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
$response = curl_exec($ch);
curl_close($ch);

echo $response; // {"status":"success"}
?>
```

#### ⚠️ HTML to JSON Conversion

**The HTTP server does NOT accept raw HTML.** You must convert your HTML to JSON format on the client side.

**Example conversion:**

```html
<!-- Your HTML -->
<div>
  <h1 style="text-align:center; font-weight:bold;">TOKO MAJU JAYA</h1>
  <p style="text-align:center;">Jl. Merdeka No. 123</p>
  <hr>
  <table>
    <tr><td>Item 1</td><td style="text-align:right;">25.000</td></tr>
  </table>
</div>
```

```javascript
// Convert to JSON commands
const jsonCommands = [
  { type: 'text', text: 'TOKO MAJU JAYA', align: 'center', bold: true, size: 'double', newline: true },
  { type: 'text', text: 'Jl. Merdeka No. 123', align: 'center', newline: true },
  { type: 'text', text: '--------------------------------', newline: true },
  { type: 'text', text: 'Item 1', align: 'left' },
  { type: 'text', text: '25.000', align: 'right', newline: true },
  { type: 'cut' }
];

// Then send via fetch
fetch('http://127.0.0.1:8080/print', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(jsonCommands)
});
```

### 3. Debugging

To view logs for debugging print issues:

```bash
adb logcat -s PrinterService JsonPrintParser
```

This will show:
- Received JSON payload
- Parsed commands
- Print status (success/error)

## 📂 Project Structure

```
.
├── app
│   ├── build.gradle
│   └── src
│       └── main
│           ├── AndroidManifest.xml
│           ├── java/com/visionweblabs/thermalbtprint
│           │   ├── MainActivity.kt
│           │   ├── bluetooth/
│           │   │   ├── BluetoothDiscoveryManager.kt
│           │   │   ├── PrinterConnection.kt
│           │   │   └── PrinterManager.kt
│           │   ├── printservice/
│           │   │   └── BtPrintService.kt
│           │   ├── receivers/
│           │   │   └── PrintIntentReceiver.kt
│           │   ├── services/
│           │   │   └── PrinterService.kt
│           │   └── utils/
│           │       ├── EscPosBuilder.kt
│           │       ├── ImageConverter.kt
│           │       └── JsonPrintParser.kt
│           └── res/
│               ├── layout/
│               └── values/
├── build.gradle
└── settings.gradle
```
