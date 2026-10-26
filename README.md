# Layerbit Studio — Android image editor

A native Kotlin/Jetpack Compose image editor for Layerbit. All included tools are free; image processing stays on the device. This repository is a separate product from Sheaf and IPO-RADAR.

## Implementation plan

1. Audit and foundation: original source, offline Android manifest, scoped import/export, bounded image decoding, and non-destructive edit state.
2. Editor: crop, rotate/straighten/flip, resize, target-size compression, JPEG/PNG/WebP, adjustments and filters, markup, undo/redo and comparison.
3. Workflow: saved recipes, metadata controls, sequential batch jobs, image layers/masks, local blur/pixelation, clone, and collage.
4. Quality: semantic animated icons with reduced motion, accessibility, fixture-based image checks, Android build, privacy verification and launch documentation.

## Reference audit

Sheaf provides design and engineering references: Kotlin/Compose, navy/paper surfaces, purposeful motion, private working copies and Android file pickers. Its repository currently has no license file. No Sheaf implementation or assets are copied. The new repository was empty and has no established source license or dependency policy; a redistribution license must be selected by its owner. Third-party licenses are documented separately.

## Advanced feature boundary

On-device neural background removal, generative object erasing, neural upscaling and healing require model-license, memory, accuracy and device evaluation. They must not appear as unfinished controls. Functional manual tools can ship under accurate names.

## Status

Implementation is being developed on a review branch. Build and validation evidence will be recorded in the final README and docs.
