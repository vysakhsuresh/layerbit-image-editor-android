# Layerbit Studio

**A picture editor that cannot upload your pictures.**

Crop, straighten, light and colour, curves, looks, retouching, frames, resize, photo-under-50-KB, convert, compress, lossless metadata removal and collages, on your phone. Every tool is free. Nothing is sent anywhere, and the reason to believe that is this repository rather than a privacy policy.

## Why this exists

The picture editors people install come in two kinds. One uploads the photo to a server to do the work, which for a photo of an ID card, a child or a medical report is a strange thing to ask. The other keeps the first few tools free and puts curves, batch resize and "remove watermark" behind a subscription. The free tier exists to sell the paid one.

Studio does the work on the device, in code that is visible here. There is no server, so there is nothing to pay for, so there is nothing to gate.

## Nothing leaves the phone

Studio does not declare the `INTERNET` permission, so the process cannot open a socket. Your pictures cannot leave the device even if a dependency tried to send them, and that is checkable on the Play listing without taking anyone's word for it.

`app/src/main/AndroidManifest.xml` strips network access explicitly, and `tools/verify_manifest.py` fails CI if the merged debug or release manifest ever gains it back:

```xml
<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove" />
```

Consequences that shape the code:

- **No crash reporting.** The crash log is written to private storage and offered from Settings; the user sends it, or does not.
- **No storage permission** and never `MANAGE_EXTERNAL_STORAGE`. Pictures come in through the photo picker and the Storage Access Framework and go out through documents the user creates.
- **No camera.** The camera app the phone already has is better at it; a photo taken there arrives here by share.
- **No image-loading library and no model.** Every tool is `android.graphics` plus the pure engine in `engine/`.

## Studio never writes to your original

Every edit works on a private copy. `ImageStore` copies what you picked and hands back a `StudioFile` pointing at the copy; every operation takes a `StudioFile`, so an operation can only ever reach the copy. The only component permitted to open a Uri for writing is `Exporter`, against a new document you just named in the system picker or a folder you granted.

## One shape for every batch tool

```
List<StudioFile> -> Op -> List<StudioFile>
```

Resize is one-to-one, a collage is many-to-one, and a batch is the same operation handed more inputs. "Process fifty photos at once" is not a feature to charge for; it is what the type means.

The front door is one gesture: open pictures. One picture opens the editor; several open the batch screen, where the steps are switches that compose into a single run: resize, keep under a size, format and quality, auto-enhance, a look or a saved recipe, a watermark, a frame. Hidden data is always removed, and when that is the only step a JPEG is cleaned by walking its segments, byte for byte. There is no catalogue of twenty tools to learn; there is one screen whose switches do what twenty apps sell separately.

## The engine is pure, and tested without a phone

`app/src/main/java/com/layerbit/studio/engine/` imports nothing from `android.*`. Tone is a 3×4 matrix plus three 256-entry tables, decided once per recipe and applied per pixel; blur, sharpening, clarity, vignette and grain are sized to the picture so a 900-pixel preview and a 24-megapixel export agree; JPEG metadata is removed by walking the file's segments so the picture is kept byte for byte; a target size is found by halving against an injected encoder. `tools/jvm-check.sh` compiles that package with the standalone Kotlin compiler and runs its tests in seconds, on any machine with a JDK. CI runs it before Gradle.

## What it does

| Tool | What it does |
|---|---|
| Edit | One editor: crop, square, light, colour, curves, looks, detail, draw, retouch, layers, frame, inside, export, with undo, press-and-hold compare, pinch zoom and a histogram |
| Crop and straighten | Drag handles with thirds, aspect presets that mean what they say after a turn, straighten, turn, flip, exact output pixels |
| Straighten a document | Four corners dragged onto a page; a perspective map squares it |
| Light | Exposure, contrast, highlights, shadows, whites, blacks; Auto from the histogram, left as visible sliders |
| Colour | Temperature, tint, vibrance, saturation, hue |
| Curves | Monotone curve per channel, drag to edit |
| Looks and recipes | Fourteen looks that state which sliders they move; save any edit as a recipe and run it over a batch |
| Detail | Sharpness, clarity, dehaze, fade, vignette, grain, all resolution-independent |
| Draw and write | Freehand, arrows, boxes, circles, movable wrapped text; every stroke a layer with opacity and order |
| Hide and retouch | Blur, pixelate, opaque cover, erase to transparency, clone |
| Frame and pad | Pad to an aspect, border, rounded corners, blurred fill |
| Watermark | Repeated text at an angle, batch |
| Resize | Longest edge, percent or exact pixels, batch, halving pyramid so thin lines survive |
| Photo under a size | Under 20 KB, 50 KB or whatever a form asks; best quality that fits first, shrink only if it must; passport shape; a JPEG already under is only cleaned, never re-encoded |
| Compress / Convert | Quality or target size; JPEG, PNG, WebP, lossless WebP |
| Remove hidden data | Location, camera and everything else, with the JPEG picture byte for byte the same |
| What is inside | Pixels, camera fields and whether a location is hidden |
| Enhance automatically | Levels and exposure from each picture's histogram; no model |
| Collage | Grid, columns, feature, strip, polaroid, two to twelve pictures |

Every export is measured after it is written: the size and pixels you see are the file's own. A picture that needed no change says so instead of pretending.

## Build and run

Requirements: JDK 17, Android SDK platform 36, build tools 36.0.0. Android 8.0 (API 26) or later installs the app.

```sh
bash tools/jvm-check.sh                          # engine, no Android SDK needed
./gradlew testDebugUnitTest lintDebug assembleDebug --console plain
python3 tools/verify_manifest.py
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew connectedDebugAndroidTest --console plain
```

Release signing reads a git-ignored `keystore.properties` (see the `.example`). CI builds the unsigned bundle and checks its merged manifest.

## Honest limits

- The working budget is a twentieth of the app heap in pixels, between 2 and 24 megapixels, with an 8192-pixel edge. Larger sources work at that size and the export says so. This is computed, not yet measured on a 256 MB-heap device; see `docs/VALIDATION.md`.
- 8-bit sRGB throughout. RAW development, HDR gain maps and wide-gamut output are not supported; animated images are treated as stills.
- Blur and pixelation are visual effects. Use Cover to hide a number or a name.
- A batch runs in the process, not a foreground service. Leave the app long enough and Android may end it; finished pictures stay on disk.
- Lossless WebP needs Android 11 to encode; below that it is not offered.

## Where things are

`docs/ARCHITECTURE.md` for the shape of the code; `docs/DEPENDENCIES.md` for licences; `docs/VALIDATION.md` for what has been checked and what has not; `docs/LAUNCH_KIT.md` for the unpublished listing. The repository's source has no redistribution licence yet; public visibility is not a licence grant.
