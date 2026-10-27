# Layerbit Studio

A native, offline Android image editor built with Kotlin and Jetpack Compose. All included tools are free. This is a separate product and repository from Sheaf and IPO-RADAR.

**Status: 0.1.0 development preview.** This branch implements real editing and export flows. It is not a production release or a claim of market-leading quality. See [validation and release gates](docs/VALIDATION.md) before distributing it.

## Included tools

| Area | Implemented behavior |
| --- | --- |
| Geometry | Four independent crop edges, common aspect presets, quarter-turn rotation, flips and straightening |
| Resize | Exact pixel dimensions, aspect lock and percentage presets; requested and actual export dimensions |
| Compress | Maximum file size in KB/MB for JPEG/WebP; measured encoded preview; optional dimension reduction |
| Convert/export | JPEG, PNG and lossy WebP; format/quality controls, new-copy document export and Android sharing |
| Colour | Brightness, contrast, highlights, shadows, saturation, warmth, hue and sharpness; individual resets |
| Filters | Five adjustable looks, original comparison and reusable recipes |
| Markup | Text, freehand strokes, arrows, boxes, ellipses, opaque covers, pixelation and rectangular local blur |
| Manual layers | Reorder, visibility, opacity, delete, reversible transparency masks and sampled clone strokes |
| Batch | Sequential resize, compress, convert, filename prefixes and saved recipes; per-image failure reporting |
| Collage | Automatic fit grids for 2–9 selected projects, subject to the device pixel budget |
| Projects | Private source copies, saved edit recipes, recent projects, 60-step session undo/redo and recovered exports |
| Interface | Light/dark/system appearance, zoom, semantic animated icons, reduced-motion controls and labelled standard controls |

There are no placeholder AI buttons. Neural background removal, object erasing, healing/inpainting and learned upscaling are deferred until model licensing, accuracy, size, latency and device memory have been evaluated. Full image-layer compositing and arbitrary selective colour masks are also future work.

## Build and run

Requirements: JDK 17, Android SDK platform 36, build tools 36.0.0 and platform tools. Android 8.0/API 26 or later is required to install the app. Use Android Studio or the checked-in Gradle 8.13 wrapper; it verifies the distribution checksum.

1. Clone this repository and check out `codex/offline-editor` while the implementation is under review.
2. Set `ANDROID_HOME` to your SDK directory, or put `sdk.dir=/your/android/sdk` in an untracked `local.properties` file.
3. Build and check:

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug --console plain
python3 scripts/verify_manifest.py
```

Install `app/build/outputs/apk/debug/app-debug.apk` on a connected device:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew connectedDebugAndroidTest --console plain
```

The first build downloads build dependencies. **The installed app does not require network access.** GitHub Actions runs host checks and an API 35 emulator flow, with APK/report artifacts. Debug APKs use development signing; no release key, store upload or publication is configured.

## Privacy and output behavior

- No internet, network-state or broad storage permission. CI checks the merged app manifest.
- No analytics, crash-upload SDKs, ads, account system, cloud models or hidden upload paths.
- Photo Picker/Storage Access Framework inputs are copied to app-private storage. The renderer only reads these copies. Original provider URIs are never used as write destinations.
- Save creates a separate document; batch export creates new documents in the selected folder. Share grants read access only to selected encoded copies through a narrow FileProvider.
- Source metadata is removed by default. Optional JPEG preservation includes only camera/date/exposure fields. GPS is always excluded.
- Private project backup is disabled. Remove projects and clear prepared exports from the app. Save important outputs outside the app before uninstalling.

Other apps or document providers selected through Save/Share control what they do with the copies they receive.

## Quality and practical limits

- The working budget is based on the app heap and is limited to 1–8 MP and 8192 pixels per edge. Large sources may be downsampled. This first version is **not a tiled full-resolution editor**. Confirm the actual dimensions in the encoded preview.
- Processing uses 8-bit sRGB. RAW development, HDR/gain-map preservation, wide-gamut output and animated-image editing are not supported. Inputs are treated as still images.
- Straightening expands the canvas. PNG/WebP can preserve transparency; JPEG flattens it onto white.
- JPEG and WebP exports are lossy. PNG encoding is lossless for the edited pixels; editing and resizing may already have changed those pixels.
- A target file size is a maximum, not an exact byte count. A target that cannot be met fails clearly unless dimension reduction was enabled. The preview uses the actual encoded file.
- Crop currently uses labelled edge sliders, with a minimum of 1% per dimension. Manual layers are applied after geometry; changing geometry does not track an overlay to its original subject.
- Local blur and pixelation are visual effects, not a guarantee that sensitive information is unrecoverable. Use opaque Cover and inspect the exported file when concealing information.
- Keep batch processing open. Activity recreation is supported; process termination can interrupt a job. Completed private outputs are retained, and the latest 50 are recovered on launch. Undo/redo history is session-only; the latest committed recipe is saved.
- Freehand editing needs spatial touch input. Physical-device TalkBack, large-text, thermal and battery qualification remain release gates.

## Architecture, licenses and launch materials

[Architecture and Sheaf audit](docs/ARCHITECTURE.md) describes the source/recipe/renderer separation and staged advanced work. [Dependency decisions](docs/DEPENDENCIES.md) records third-party licensing. [Validation](docs/VALIDATION.md) separates automated evidence from outstanding release checks. The [launch kit](docs/LAUNCH_KIT.md) includes an unpublished listing, screenshot plan, demo script, FAQ and organic discovery plan.

Sheaf was used only as a product/architecture reference. No code, icons, fonts or assets were copied. The repository had no source license; its owner must select a redistribution license before an open-source release. Public visibility alone is not a license grant. Third-party components retain their own licenses.

There are no monetization mechanisms under this brief. Optional external support, development sponsorship or paid organizational/custom-development services are future business choices for the owner, not implemented app features.
