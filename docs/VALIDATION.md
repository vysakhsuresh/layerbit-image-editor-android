# Validation and release gates

## Automated checks

The Android workflow builds the debug APK, runs JVM/Robolectric image tests and Android lint, checks the merged application manifest, and runs an Android API 35 emulator flow. Each workflow artifact belongs to its commit; use the latest successful run for that exact revision. A green build alone is not production certification.

The pipeline suite covers crop/rotate/resize dimensions, unchanged source hashes, EXIF orientation and GPS stripping, transparent PNG masks, actual target-size bounds, cancellation cleanup, corrupt inputs, sampled large-image decode, diagonal panorama allocation, real clone/blur pixels, recipe serialization and oversized resize rejection. Geometry tests cover size bounds, aspect presets and safe names.

The emulator flow imports a generated fixture through the real ViewModel/store, recreates the Activity, changes theme/motion preferences, reopens the project, prepares a measured export and confirms source bytes are unchanged. It captures actual Home/editor/export screens for review. It does not claim end-to-end automation of every external document provider or share target.

## Milestone record

| Milestone | Implementation | Remaining qualification |
| --- | --- | --- |
| Audit/architecture | Empty destination and Sheaf inspected; original implementation and dependency policy recorded | Owner selects source redistribution license |
| Foundation | Compose app, scoped imports/exports, private projects, bounded image pipeline, semantic motion | Broader OS/device matrix |
| Core tools | Editing panels, target-size encoding, presets and sequential batches implemented | Hands-on quality review of every tool and SAF provider |
| Advanced stage | Manual overlay layers, masks, clone, local blur/pixelation and grid collage | Neural tools and richer compositing deferred; no unfinished UI |
| Release preparation | Build workflow, fixture tests, privacy check, README and unpublished launch kit | Physical-device accessibility/performance checks, release signing and owner review |

## Required before a public release

1. Run the exact release revision on API 26 and a current physical Android phone, including a low-memory device. Record device/RAM/OS, cold import time, preview latency, export time, peak memory, battery/thermal behavior and actual output dimensions.
2. Exercise each visible control, undo/redo after geometry and layer operations, batch partial failures, cancellation, process interruption and reopening projects. Verify saved copies with independent viewers.
3. Test Photo Picker and document providers, both single-file and folder export, denied/cancelled choices, low storage and sharing to common targets. Confirm source hashes remain unchanged.
4. Use small and high-resolution photos, very thin panoramas, all eight EXIF orientations, transparent PNG/WebP, wide-gamut/HDR sources, corrupt inputs and unusually long filenames. Record expected sRGB/SDR conversion and dimension reductions.
5. Test TalkBack traversal and announced slider values, 200% font scaling, landscape/tablet layouts, touch targets, both themes, Android Remove animations and the in-app motion switch. Brush gestures still need spatial touch input.
6. Compare matched edits and outputs against established editors on the same fixture set: edge quality, transparency halos, colour differences, encoded bytes, timings and memory. Publish measurements and limitations before making benchmark claims.
7. Recheck runtime dependency licenses and the merged **release** manifest; select the source license, configure an owner-held signing key, verify the signed build and review final store assets. No release has been published.

## Evidence status

The [initial passing run](https://github.com/vysakhsuresh/layerbit-image-editor-android/actions/runs/40437573713), revision `5a2c26d`, passed 15 JVM/Robolectric tests, Android lint (zero errors, 23 warnings), the debug APK build, the merged debug privacy check and one API 35 emulator import/reopen/export test. Lint warnings included explicit removal declarations, newer available versions, Kotlin-style suggestions and backup rules. Backup-rule hardening, additional labels and a crop-boundary regression test are in the subsequent revision; its checks must also pass before using that build. Screenshot extraction is being corrected because Gradle removed the test installation before collection.
