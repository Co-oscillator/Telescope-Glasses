# Telescope Glasses — Architecture & Agent Instructions (`AGENTS.md`)

**Telescope Glasses** is a dedicated 16-bit FITS & JPG astrophotography companion editor for the **ZWO Seestar S50** smart telescope.

## 1. Core Files in This Workspace
- **`seestar_studio_prototype.html`** — Single-file interactive web prototype and FITS testing harness.
- **`app/src/main/java/com/astro/telescopeglasses/SeestarAstroEditor.kt`** — Compose editor UI, FITS metadata/SMB helpers, OpenCV processing, information frame, and export helpers.
- **`app/src/main/java/com/astro/telescopeglasses/MainActivity.kt`** — Android entry point, document picker, JPG/FITS decoding, render orchestration, and SMB connection prompt.
- **`README.md`** — Android Studio, build, and device-install instructions plus current prototype limitations.

## 2. Key UX & Layout Requirements
1. **Inset-safe pinned image preview with manual rotation and zoom**:
   - Preserve the source orientation by default; Seestar FITS exports can already be landscape. Rotation is an explicit user toggle, not an assumption about source dimensions.
   - Keep the preview compact (about 30% of safe screen height when unrotated) and give the three control tabs the remaining space. Respect status/navigation bar insets.
   - Pinch/pan the preview to inspect detail. If rotated, rotate image pixels before appending the information frame so the metadata remains upright.
2. **Swipeable 3-Panel Control Deck (`HorizontalPager` on Android / Touch-Swipe Track on Web)**:
   - **Panel 1: 16-Bit Curves & Asinh Pre-Stretch** — Interactive `L`, `R`, `G`, `B` monotonic cubic spline curves backed by a 65,536-entry (`FloatArray(65536)`) lookup table so shadows can be stretched without 8-bit posterization.
   - **Panel 2: Detail, Color & Structure Sliders** — compact two-column controls for edge-preserving Noise Reduction, Saturation, Green/Purple Tint, Blue/Orange Temperature, Fine Detail, and Structure.
   - **Panel 3: File Management & Seestar Info Frame** — Load `.fit`/`.fits` or `.jpg`, receive compatible Android shares, pull from Seestar SMB, edit frame metadata, and export to `/Pictures/Seestar/`.
3. **Automatic Bottom Frame Edit Audit**:
   - The bottom border automatically prints both the FITS capture metadata (`OBJECT`, `TELESCOP`, `STACKCNT × EXPTIME`, `FILTER`, `GAIN`, `DATE-OBS`) and a live summary pill of every editing tool used (e.g., `Telescope Glasses: Pre-Stretch (45%) • Curves (L) • Denoise (25%) • Structure (+30%)`).

## 3. Recommended `build.gradle.kts` Dependencies for Android
```kotlin
dependencies {
    // NASA FITS standard parser for reading 16-bit Seestar .fit headers & pixel cubes
    implementation("gov.nasa.gsfc.heasarc:nom-tam-fits:1.19.1")

    // OpenCV Android SDK (4.9.0+) for 32-bit float Mat processing, Bilateral Denoise & Multi-Scale Unsharp Mask
    implementation("org.opencv:opencv:4.9.0")

    // SMB2/SMB3 client for pulling stacked .fit files directly from Seestar S50 over Wi-Fi Station Mode
    implementation("com.hierynomus:smbj:0.13.0")
}
```

The Gradle project is scaffolded at the repository root. Keep machine-specific SDK paths out of version control (`local.properties` is ignored). A 2D Bayer mosaic is currently displayed as monochrome; debayering, full-resolution export, and FITS write-back are not implemented yet. FITS preview normalization uses sampled black/white percentiles followed by an asinh stretch.
