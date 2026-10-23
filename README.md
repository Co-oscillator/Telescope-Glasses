# Telescope Glasses

An Android Compose editor prototype for Seestar S50 astrophotography images.

## Open and run

1. Open this folder (`Telescope Glasses`) in Android Studio.
2. Let Gradle sync, then choose the `app` run configuration and a connected Android device (Android 8.0 / API 26 or newer).
3. Or build a debug APK from Terminal:

   ```sh
   ./gradlew assembleDebug
   ```

   The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

The Gradle wrapper uses Java 17 and downloads its Gradle distribution/dependencies on first build. Android Studio usually supplies the Android SDK path automatically; for command-line builds set `ANDROID_HOME` or create a local `local.properties` (ignored by Git).

## Current test scope

- Import JPG/PNG and FITS files through Android's document picker.
- Receive a shared image/FITS URI directly from compatible apps via Android's Share menu (including FITS shared as `application/octet-stream`).
- Edit the preview with the 3-panel Compose UI, rotate the image, and save/share a framed JPG, PNG, or WebP under `Pictures/Seestar`.
- JPG/WebP exports have a quality slider; PNG is lossless and disables that control.
- FITS import reads the primary image HDU and common `BITPIX` types, applies `BSCALE`/`BZERO`, and uses sampled black/white percentiles before the asinh stretch. Monochrome and three-plane RGB images retain source resolution up to 2048 pixels on the longest edge.
- The SMB option connects to a Seestar IP over the local network with anonymous SMB access and looks for a `stacked_*.fit[s]` file under `EMMC Images/MyWorks/<subfolder>`.

## Known limitations before broader testing

- Two-dimensional Bayer FITS data is currently rendered as monochrome (no debayering); percentile clipping is a preview-oriented estimate, not a full scientific stretch.
- Images larger than 2048 pixels on the longest edge are downsampled on import. FITS write-back, undo/history, and persistence of edits are not implemented.
- SMB behavior depends on the telescope firmware, network mode, share permissions, and exact `MyWorks` folder name; local file import is the recommended first test.
- The bundled OpenCV + SMB dependencies make the universal debug APK large.
