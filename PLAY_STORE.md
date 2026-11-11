# Publishing BT Print on Google Play

## Before you start

- **Permission to publish.** Most of this code was written by Wahyu Akbar Wibowo, and the original repository has no license. Get his written permission (for example, ask him to add an MIT license) before publishing.
- **Privacy policy URL.** Fill in the placeholders in [PRIVACY_POLICY.md](PRIVACY_POLICY.md) and publish it on a public web page, such as `https://visionweblabs.com/btprint/privacy`. Play needs a public link; a private GitHub repository won't work.

## 1. Build the signed App Bundle

Follow "Signed releases" in the [README](README.md#-signed-releases). Upload the `.aab` file; Play doesn't accept APKs for new apps.

On first upload, keep the default **Google Play App Signing**. Google then holds the final app-signing key, and your `btprint-release.jks` becomes the "upload key". If you ever lose it, Google can reset it, but avoid relying on that.

## 2. App content (Play Console → Policy → App content)

| Section | Answer |
|---|---|
| Privacy policy | Your public privacy policy URL |
| Ads | No, the app does not contain ads |
| App access | All functionality is available without special access |
| Content rating | Complete the questionnaire. Category: Utility/Productivity; answer No to every content question |
| Target audience | 18 and over (it's a business tool) |
| Data safety | See below |
| Foreground service permissions | See below |

### Data safety

- Does your app collect or share any of the required user data types? **No**
- Is all user data encrypted in transit? Not applicable (nothing leaves the device)
- Do you provide a way for users to request deletion? Not applicable (no data collected)

This is accurate because the printer address stays on the device and print content goes straight to the printer.

### Foreground service declaration

Play asks why the app uses a **connected device** foreground service, and wants a short video showing it.

- **Task:** "BT Print keeps a local print server running so point-of-sale apps and web pages on the same phone can print receipts to the user's paired Bluetooth thermal printer at any time, including while BT Print is in the background. The ongoing notification tells the user the service is running, and they can stop it with the Stop Service button."
- **Video:** screen-record opening BT Print, selecting a printer, then printing from another app or from Chrome while BT Print is in the background.

## 3. Store listing

- **App name:** BT Print
- **Short description:** Print receipts, barcodes and web pages to Bluetooth thermal printers.
- **Do not** mention "RawBT" or other apps' names in the title, description or screenshots; Play rejects listings that reference other brands.
- **Graphics:** 512×512 app icon, 1024×500 feature graphic, and at least 2 phone screenshots.

## 4. Testing requirement for new personal accounts

New personal developer accounts must run a closed test with at least 12 testers for 14 continuous days before they can publish to production. Organization accounts are exempt.
