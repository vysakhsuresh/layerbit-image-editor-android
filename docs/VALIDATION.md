# Validation and release gates

## What is checked automatically

1. **Engine, without an Android SDK.** `tools/jvm-check.sh` compiles `engine/` with the standalone Kotlin compiler, fails if anything there imports `android.*`, and runs its JUnit tests: tone identity and each slider's shape, saturation and vibrance, hue, levels, curves, every look, blur and unsharp behaviour, preview-versus-export agreement at two sizes, vignette and grain, crop and aspect arithmetic after turns, recipe round trips and tolerant reading including the 0.1 format, JPEG segment walking and stripping, size fitting, names and collage plans.
2. **Android pipeline, on the JVM.** Robolectric with native graphics: export leaves the source byte for byte, EXIF orientation applied once and location never copied, preview predicts export through the real renderer, erase to PNG transparency, a target size measured after metadata, no partial file on failure or cancellation, lossless strip with identical decoded pixels, perspective and straighten sizes within budget, layers at two sizes, and the batch ops end to end through the store and workspace.
3. **Lint, debug APK, release bundle**, then `tools/verify_manifest.py` against every merged manifest.
4. **Emulator, API 35.** The real activity: a picture imported through the store appears under "Pick up" on the home screen, opens in the editor, exports with the measured dimensions, the source is unchanged, downloadable emoji fonts stay off. Screenshots of home, editor and export are uploaded with the run.

A green run is evidence that these things hold on that revision. It is not a release.

## Evidence for this revision

Green on `e6486a1`: https://github.com/vysakhsuresh/layerbit-image-editor-android/actions/runs/40453673866

| Stage | Where | Result |
| --- | --- | --- |
| Engine | standalone Kotlin compiler, JUnit | 59 tests pass; nothing in `engine/` imports `android.*` |
| Gradle | GitHub Actions, JDK 17 | unit tests, lint with the baseline, debug APK and release bundle, manifest check, dependency report |
| Robolectric | SDK 28, native graphics | 83 tests pass: renderer, codecs, EXIF, strip, every batch op, the pipeline |
| Emulator | API 35 | the home, editor and export flow, and a walk through every screen that commits its screenshots to `docs/screenshots` when the commit message carries `[screens]` (run 40462061887) |

Later revisions are green or they are not; this table is updated when a release candidate is cut.

## Required before a public release

The fuller list, with the reasoning, is in `docs/ROADMAP.md` under release checks: document providers and reopening, all eight EXIF orientations, an interrupted batch, transparent pictures, a 24 MP export on a 256 MB heap, and a fixed corpus for the cut-out and scan.

1. Run the exact release revision on API 26 and on a current phone, including one with a 256 MB app heap. Record cold start, preview latency per slider tick on a 12 MP source, export time at the full budget, peak memory from `dumpsys meminfo`, and whether `Budget.exportPixels` at a twentieth of the heap survives a 24 MP export with the frame and layers on. Lower the divisor if it does not; the number in `Budget.kt` changes only with this measurement.
2. Exercise every panel: drag-handle crop with each aspect, straighten while the crop stays put, perspective on a photographed page, Auto on dark and bright pictures, curves on each channel, every look, text placed, moved and edited, clone, erase exported as PNG and WebP, frame with blurred fill, undo and redo across geometry and layers.
3. Batch: fifty mixed JPEG and PNG inputs through resize, under-50-KB, convert, strip and collage; cancel midway and confirm finished outputs survive; rotate the phone midway.
4. Share in from the gallery and from a camera app, "edit in" from a file manager, the three launcher shortcuts, a share of several pictures landing on Resize.
5. Photo picker and document providers, single-file save and folder save with the folder remembered, denied and cancelled choices, a full card, sharing to common targets. Confirm source hashes unchanged.
6. Inputs: eight EXIF orientations, transparent PNG and WebP, HEIC, wide-gamut and HDR photos, a 50 MP photo, a 10000×10 panorama, corrupt files, long names.
7. TalkBack through the home screen, the pipeline's switches, the panel strip and every slider, 200% font scale, landscape and a tablet, both themes, animations off; brush tools need touch and the alternatives in Markup must each work.
8. Compare matched exports against two established editors on the same fixtures: edge quality, halos, colour, bytes, time. Publish the measurements before any comparison claim.
9. Run the baseline profile generator and the startup benchmark; record the two medians.
10. Select the source licence, configure the owner's signing key, verify the signed bundle, review the store assets.
