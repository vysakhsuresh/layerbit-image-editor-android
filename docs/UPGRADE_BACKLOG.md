# Layerbit Studio — upgrade backlog

> Audit of `codex/offline-editor` at `e679d07` (CI run 8, green: 15 JVM tests,
> lint, debug APK, privacy manifest check, API 35 emulator flow).
> About 1,240 lines of Kotlin across 7 source files and 3 test files.
>
> Verdict: the foundation is unusually honest and well bounded for a 0.1.
> Privacy posture, cancellation, the measured export preview and the
> source/recipe/renderer split are all real. What holds it back from being
> a product people reach for is (1) a slow per-pixel colour pipeline that
> makes every slider laggy and caps output at 8 MP, (2) a crop tool made of
> four sliders, and (3) release plumbing that Deja already solved.
>
> Items are ordered by value per hour. P1–P4 and U1 are *the* upgrade.

---

## 0. Rules every item inherits

These come from `AGENTS.md` and the README's "advanced feature boundary".
An item that breaks one of them is wrong even if it works.

1. **No network, no telemetry, no accounts, no paywall.** The manifest
   removals stay; `scripts/verify_manifest.py` stays in CI.
2. **Provider URIs are read once.** The engine only ever sees private copies.
3. **Every visible control works.** Neural tools (segmentation, inpainting,
   learned upscaling) stay in docs until a model has been evaluated on a
   fixed corpus for license, memory, latency and accuracy. Nothing below
   needs a model.
4. **Processing stays cancellable and inside the documented pixel budget.**
   If an item raises the budget it must measure peak heap on a 256 MB-heap
   device and write the number in the commit.
5. **Preview must predict export.** Any effect whose look depends on
   resolution (P4) is a bug, not a limitation.
6. **Keep the dependency table tiny.** No image-loading library, no
   processing SDK. Everything below is platform `android.graphics` plus
   AndroidX components already on the classpath.
7. **The owner picks the source license.** Still unselected; nothing here
   changes that.

**Do not rebuild what exists:** bounded decode with EXIF orientation,
undo/redo, recipes/presets, measured target-size export, batch, collage,
erase masks, clone, local blur/pixelate, the motion clock, the privacy
check, backup exclusions, the Emoji2 removal.

---

## Tier P — engine performance and fidelity (do this first)

### P1. Replace the per-pixel HSV loop with ColorMatrix + LUT
`ImageEngine.adjust` (`core/ImageEngine.kt:82-124`) walks every pixel in
Kotlin, calls `Color.RGBToHSV`/`HSVToColor` on each one even when hue and
saturation are zero, and truncates to int in between (visible banding on
gradients). On a 900 k-pixel preview that is roughly 150–400 ms per slider
tick on a mid-range phone; on an 8 MP export it is seconds, single-threaded.

Replace with:
- A `ColorMatrix` for brightness, contrast, saturation, warmth and hue
  (`setSaturation`, `setRotate` on the hue axis, a scale/translate row for
  warmth). Drawn through `Canvas.drawBitmap` with a
  `ColorMatrixColorFilter`, it runs in native code, uses no `IntArray` copy,
  and takes single-digit milliseconds.
- One 256-entry `IntArray` LUT per channel for the luminance-dependent part
  (shadows, highlights, the five filter looks at a given intensity). Apply
  it with a single pass over the `IntArray`, no `Color.*` calls, or fold it
  into the matrix where it is linear.
- Keep sharpening as the one convolution, but see P4.

Keep the existing tests; add one that renders a 256-step grey ramp through
every slider at 0 and asserts the output equals the input (no quantisation
drift). The old loop is the oracle for a tolerance test on the new path.

### P2. Cache the geometry stage between slider ticks
`StudioViewModel.render()` re-opens the source file, re-decodes, re-crops
and re-rotates on **every** debounced slider move (`StudioViewModel.kt:60-72`
calls `engine.render` which calls `decode` from disk). Only tone, filter and
marks change while the user drags an Adjust slider.

Split `ImageEngine.render` into `geometry(source, recipe) -> Bitmap` and
`finish(base, recipe) -> Bitmap`. Hold one cached `geometry` result in the
ViewModel keyed by `(source.id, crop, rotation, straighten, flipX, flipY,
width, height, preview)`; recycle it on `select()`/`home()` and when the key
changes. With P1 this turns the Adjust and Filter panels from "wait for the
progress bar" into live.

### P3. Raise the pixel budget, measured, not guessed
`ImageEngine` budget is `maxHeap / 48`, clamped to 1–8 MP
(`core/ImageEngine.kt:11`). A 512 MB heap gets 8 MP; phones shoot 12–50 MP.
The divisor was chosen for a pipeline that holds the bitmap, an `IntArray`
copy and a second copy for sharpening at the same time (~3× pixels × 4
bytes). After P1 removes the copies and P2 recycles stages promptly, the
same safety margin supports roughly `maxHeap / 16`, i.e. 24 MP on a 512 MB
heap and ~12 MP on a 256 MB one.

Do this only with evidence: an instrumented test that exports a 24 MP
fixture on the API 26 emulator with a 256 MB heap and logs
`Debug.getNativeHeapAllocatedSize` + `Runtime.totalMemory` peaks. Keep the
8192-pixel edge limit. Update the README numbers from the measurement.

A tiled full-resolution engine (`BitmapRegionDecoder` + per-tile tone +
streamed JPEG) is the eventual answer for 50 MP but is a separate milestone;
it cannot do rotation/straighten or overlays per tile without a second
design pass.

### P4. Make preview predict export (WYSIWYG bugs)
Two effects depend on the bitmap resolution, so a 900 k preview and an 8 MP
export disagree:
- **Local blur** radius is `min(w,h) * width * 2` capped at **40**
  (`core/ImageEngine.kt:164`). The cap triggers on the export but often not
  on the preview, so exports come out sharper than shown. The box blur is a
  sliding window whose cost is independent of radius, so the cap buys
  nothing. Remove it; clamp only to the rect size.
- **Sharpness** is a fixed 1-pixel kernel (`:110-122`). At 8 MP it is
  invisible; at 900 k it is strong. Scale the kernel offset (or run it on a
  downscaled copy and upsample the high-pass) by `min(w,h) / 1000f`.

Add a test that renders the same recipe at 300 k and 2 M pixels, downsamples
the large one, and asserts a mean per-channel difference under a threshold.
That one test guards every future effect.

### P5. Thumbnails: decode once, keep a small cache
`Thumbnail` in `ui/StudioApp.kt:156-159` decodes the full source (sampled to
30 k pixels, but still a disk read and EXIF parse) every time the home list
recomposes it. Write a 160-px JPEG next to `project.json` at `register()`
time and read that; invalidate when the recipe changes if you want edited
thumbnails. Keep a 20-entry `LruCache<String, Bitmap>` in the ViewModel.

### P6. Target-size search in memory
The quality binary search (`core/ImageEngine.kt:214-226`) writes every
attempt to disk and, with `keepCamera`, re-parses and rewrites EXIF each
time. Search against a reused `ByteArrayOutputStream`, write the winner
once, then insert EXIF and re-measure. Same result, roughly 7× fewer file
writes per export.

---

## Tier U — editor interaction

### U1. On-canvas crop with drag handles
Crop is four labelled sliders (`ui/StudioApp.kt:207-216`). Everyone who has
used a phone expects to drag corners on the picture. Draw the crop rectangle
over `EditorCanvas`, with 8 handles, rule-of-thirds lines while dragging, a
dimmed outside region, and the existing aspect chips locking the ratio.
Snap to 1% minimums (the `Crop.valid()` rule) and commit on gesture end so
undo has one entry per drag. Keep the sliders under a "Fine adjust"
disclosure for accessibility (the README's TalkBack commitment).

While there: a two-finger rotate gesture that maps to `straighten`, with
the straighten slider still present.

### U2. Aspect presets ignore rotation
Crop is applied before rotation, but `Geometry.aspectCrop` is fed the
unrotated source size (`ui/StudioApp.kt:209-210`). Rotate 90°, choose 16:9,
get 9:16. Swap width/height when `rotation % 180 == 90` and invert the
ratio. Add a `GeometryTest` case.

### U3. Home tool cards open an arbitrary project
Tapping a tool on Home opens `state.projects.first()` (`ui/StudioApp.kt:86`),
i.e. whichever project was saved most recently, with no indication which.
Either open the picker, or show a "Choose a picture" sheet listing projects
with the tool preselected.

### U4. Placed layers can be moved and edited after the fact
Text is placed by tap and cannot be dragged, resized or re-worded; the text
also does not wrap (`core/ImageEngine.kt:135`). In Layers (or by tapping a
layer on the canvas) allow: drag to move, pinch to scale `width`, edit text,
change colour. Wrap text with `StaticLayout` at a max width of 80% of the
canvas. Marks already store normalised points, so moving is a point
translation.

### U5. A tap during a running task is silently ignored
`runTask` returns early when `work` is active (`StudioViewModel.kt:74`).
The user sees nothing happen. Surface a message ("Wait for the current task
or cancel it") or disable the triggering controls while `busy != null`.

### U6. Press-and-hold to compare
The "Compare" chip toggles state. Add press-and-hold on the canvas (show
original while held) — the gesture every editor uses — and keep the chip
for switch access.

### U7. Histogram and clipping warnings
A 256-bin luminance histogram over the preview `IntArray` (free after P1
since the array is already walked) drawn as a 60 dp strip in the Adjust
panel, plus optional red/blue clipping overlay. Small, honest, and the kind
of thing that makes the Adjust panel feel professional.

---

## Tier F — features that need no model

### F1. Lossless metadata strip and "privacy copy"
The app's clearest privacy promise is "exported images remove GPS", but the
only way to get there is a full re-encode, which is lossy for JPEG. A JPEG
is a sequence of marker segments; dropping `APP1` (EXIF, XMP) and `APP13`
(IPTC) while copying every other segment byte-for-byte is ~60 lines with
`DataInputStream` and needs no library. Offer:
- "Remove metadata only" on the Details panel and in Batch: same pixels,
  same bytes elsewhere, GPS gone, no quality loss.
- Lossless 90° rotation is also possible on JPEG but needs DCT-block
  transposition; skip unless a reviewed dependency appears.

Test: strip a fixture with GPS, assert `ExifInterface` finds no tags, assert
the entropy-coded segment bytes are identical to the source.

### F2. WebP lossless and PNG size control
`Bitmap.CompressFormat.WEBP_LOSSLESS` exists on API 30+ and is usually
25–35% smaller than PNG for the same pixels. Add it as a format option
(hidden below API 30). For PNG, "target size" is rejected today; allow
"allow smaller dimensions" as the only lever and run the same shrink loop.

### F3. More tone tools, all deterministic
In rough order of value:
- **Auto** (one tap): histogram stretch to the 0.5/99.5 percentiles plus a
  small saturation lift. Not neural, fully reproducible, and the single
  most-used button in consumer editors.
- **Temperature / tint** split (warmth today moves only red/blue).
- **Vignette**, **grain**, **fade/levels**, **curves** (a 4-point spline
  rendered to a LUT; P1 makes this almost free).
- **Dehaze** as a contrast-in-shadows LUT, named honestly.

Each is a new `Tone` field with a default of 0, so recipes stay compatible
(see R6).

### F4. Perspective correction
`Matrix.setPolyToPoly` with four draggable corners on the canvas gives
document and whiteboard straightening in one gesture. It fits the existing
geometry stage (apply after crop, before resize). Store the four normalised
points on `Recipe`. Preview the bounding box growth like straighten does.

### F5. Canvas tools people ask for weekly
- **Pad to aspect** (square/4:5/16:9) with a chosen background colour or a
  blurred copy of the image — the "post without cropping" feature.
- **Border** width/colour, **rounded corners** (PNG/WebP keep the alpha).
- **Repeating text watermark** with angle and opacity, as a `Mark` kind so
  it is a layer with opacity and can be removed.

### F6. Batch rename patterns and batch metadata strip
Filenames are `prefix-index-random`. Allow `{name}`, `{index}`, `{w}x{h}`,
`{date}` tokens. Add "strip metadata only" (F1) as a batch mode: that is
the privacy-copy use case for a whole folder of photos.

### F7. Receive images from other apps
Declare `ACTION_SEND` / `ACTION_SEND_MULTIPLE` (`image/*`) and
`ACTION_VIEW` / `ACTION_EDIT` intent filters on `MainActivity`. The share
sheet is how most people will first meet the app. Route the received URIs
through the same `store.import`, so the read-once rule holds. Deja's
`share/` package is the reference for the manifest and the grant handling.

### F8. Collage layouts
Fit grids only, 600-px cells, white gutters. Add 2–3 named layouts (one
large + strip, two columns, polaroid), gutter width and colour, and let the
cell size follow the pixel budget rather than a constant.

---

## Tier R — release readiness (mostly ports from Deja)

### R1. Move strings to resources, then add Hindi
Every user-visible string is a Kotlin literal in `StudioApp.kt` and
`Design.kt`; the app label is a literal in the manifest. That blocks
localisation and the lint `HardcodedText` warnings are part of the 23.
Deja already has `values/strings_*.xml` split by screen and `values-hi/`;
copy the structure. Hindi first because the owner already has a reviewed
glossary there.

### R2. Adaptive and themed launcher icon
`res/drawable/ic_launcher.xml` is a plain vector. Android 8+ shrinks it
inside a white disc. Add `mipmap-anydpi-v26/ic_launcher.xml` with
`<adaptive-icon>` foreground/background, a monochrome layer for Android 13
themed icons, and a 512-px Play icon under `store-assets/`. Deja has the
exact file layout.

### R3. Release signing from `keystore.properties`, shrink resources
No signing config exists; `isShrinkResources` is off. Port Deja's
`keystore.properties` loading (git-ignored, example file checked in) and
enable `isShrinkResources = true`. Add a `--release` CI job that builds the
unsigned bundle and runs `verify_manifest.py` against the **release** merged
manifest, which `VALIDATION.md` item 7 already asks for.

### R4. Baseline profile
Cold start into a Compose app with a Canvas-heavy home screen benefits
measurably. Deja's `:baselineprofile` module is a drop-in: change the
namespace, target `:app`, generate on a connected device, commit the
profile. Record the startup delta in `VALIDATION.md`.

### R5. Build hygiene
- `kotlinOptions { jvmTarget }` is deprecated under Kotlin 2.x; use
  `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }`.
- Introduce `gradle/libs.versions.toml`; Renovate/Dependabot can then bump
  one file.
- Turn `lint { warningsAsErrors = true }` on with a committed
  `lint-baseline.xml`, so the warning count can only go down.

### R6. Recipe schema that tolerates growth
`RecipeJson.read` requires `version == 1` and uses `get*` for every field
(`core/Model.kt:68-83`). The first new `Tone` field (F3) or `Recipe` field
(F4) breaks every saved project and preset. Switch to `opt*` with the
data-class defaults, keep `version` for breaking changes only, and add a
test that reads a hand-written v1 JSON missing the new fields.

### R7. Store assets and listing in the repo
`LAUNCH_KIT.md` is a draft listing. Deja keeps `store-assets/` (icon,
feature graphic, `play-listing.md`, `play-console.md`, privacy policy).
Mirror it so the listing, screenshots plan and privacy policy are versioned
with the build that they describe.

### R8. Local crash log and "Report a problem"
With no crash-upload SDK, field failures are invisible. Deja installs an
`UncaughtExceptionHandler` that writes a private log and offers it through
a mail intent the user sends. Same pattern here; add "Report a problem" to
Settings, next to the licenses.

### R9. Onboarding card
First launch shows the hero card only. A three-line card ("Originals are
never touched · No internet permission · Export makes a new copy") with a
"Got it" that sets a preference costs little and front-loads the trust
message the listing leans on. Deja's `onboarding/` is the reference.

---

## Tier T — tests that the next tiers need

### T1. ViewModel tests
`StudioViewModel` has no JVM tests. With Robolectric already configured:
undo/redo ordering and the 60-step cap; `change()` rejecting a narrow crop
without clearing redo; `batch()` counting a failed image without dropping
successful outputs; `prepare()` producing one file and recovering it via
`store.outputs()`; `home()` persisting before `refreshProjects()`.

### T2. Preview-equals-export fixture test
The test in P4: same recipe, two resolutions, bounded mean difference.
Run it over a small corpus (gradient, noise, transparent PNG, a thin
panorama) so it covers every mark kind.

### T3. Golden screenshots in the emulator job
The device job already captures `home`, `editor`, `export` PNGs. Compare
against committed goldens with a tolerance and fail on drift; a visual
regression in `ToolGlyph` or the theme is otherwise invisible in CI.

### T4. Recipe compatibility corpus
A `src/test/resources/recipes/` folder with one JSON per schema version,
loaded by a parameterised test (R6).

---

## Tier A — accessibility follow-through

### A1. Slider value announcements
`Knob` sets `contentDescription` but not `stateDescription`, so TalkBack
reads "Brightness, slider" and a raw float. Set `stateDescription` to a
rounded percentage and give the crop knobs pixel values.

### A2. Non-touch paths for the canvas tools
Documented as a release gate. Minimum viable: "Place text in centre"
exists; add "Cover the centre third", "Blur the lower quarter" style
actions in Markup, and a numeric rectangle entry in Layers, so every mark
kind has at least one keyboard/switch path.

### A3. Large-text layouts
At 200% font scale the compact tool cards (`ToolCard(compact = true)`,
110 dp wide, `labelLarge`) truncate. Let the card width grow with font
scale or drop the hint at large scales; verify in the emulator job with
`settings put system font_scale 2.0`.

---

## Explicitly not on this list

- **Neural tools** — see rule 3. The README is right to keep them out until
  a model has been evaluated; nothing above pretends otherwise.
- **An image-loading library** — Coil/Glide would add code the app does not
  need; `BitmapFactory` plus P5 covers thumbnails.
- **Cloud anything** — by construction.
- **RAW / HDR gain-map preservation** — platform codecs re-encode SDR; a
  truthful "converted to 8-bit sRGB" note is the right call for now.
- **A full tiled engine** — mentioned in P3 as the eventual path; not a
  0.2 item.

---

## Suggested order for the next three pushes

1. **P1 + P2 + P4 + T2** — live sliders, correct blur/sharpen, one guard
   test. Smallest diff with the biggest felt change.
2. **U1 + U2 + R6** — drag-handle crop, rotation-aware presets, and the
   schema change that every later feature needs.
3. **R1 + R2 + R3 + F7** — strings, icon, signing, share-target: the set
   that turns a review branch into an installable candidate.

Then P3 with measurements, then F1/F3/F4 by whichever the owner wants to
demo first.
