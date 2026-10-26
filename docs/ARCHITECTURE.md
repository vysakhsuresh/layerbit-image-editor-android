# Architecture and reference audit

## Repository and reference

The destination was an empty public repository with no license, build files, instructions or dependency policy. Sheaf was inspected at tree `01134fcf70d82dc6b1579e1cde833963b9a10749`. Its `CLAUDE.md` applies to Sheaf; this project has its own review branch. Sheaf and IPO-RADAR are not modified.

Sheaf uses Kotlin/Compose, private input copies, Android's scoped file pickers, explicit removal of networking permissions, and a shared drawing clock for semantic animated icons. Its visual language uses navy and paper surfaces, restrained accents, and readable tool groups. No license file was present, so no Sheaf code, font, icon or other asset was copied. Studio uses original code, system typography, a mint/coral palette and original Canvas drawings.

## Components

- `ProjectStore`: copies provider input into private files with a 100 MB input limit; validates image headers; persists projects and recipes; writes only user-created destinations. A FileProvider exposes only encoded exports.
- `Recipe`: normalized crop, transforms, explicit output dimensions, tone controls, filter and ordered markup/mask layers. Source bytes never change. Coordinates belong to the canvas after geometry; geometry changes affect the image beneath overlays.
- `ImageEngine`: sampled decode, EXIF orientation, crop/transform, resize, 8-bit sRGB colour, overlays, encoding. It checks cancellation between stages, rows and compression attempts.
- `StudioViewModel`: session undo/redo, asynchronous preview, serialized project persistence, sequential batch queue and explicit export preparation.
- Compose UI: Home, editor, export preview, batch sheet and settings. Native Android contracts handle import/save/share.

## Image budget

The working budget is `maxHeap / 48`, clamped to 1–8 million pixels, plus an 8192-pixel edge limit. Previews use at most 900,000 pixels. Decoding reserves additional area for diagonal rotation. Explicit oversized resize requests fail with a useful message. Natural-size renders may be sampled; the encoded preview displays actual dimensions and original dimensions before Save. This is a bounded raster implementation, not a tiled full-resolution engine. It is not a full-fidelity RAW/HDR editor. Wide-gamut input is converted to 8-bit sRGB. Standard resize interpolation does not recover missing detail.

## Export semantics

JPEG flattens transparency onto white; PNG keeps transparency and encodes the edited pixels losslessly; WebP uses lossy encoding with alpha. Target sizes apply to JPEG/WebP. The quality search checks the actual file after optional metadata insertion. If the target is unattainable, the operation fails unless the user opted into dimension reduction. The preview reports the final size and dimensions. Input EXIF is not copied wholesale. Optional JPEG metadata is restricted to camera/date/exposure fields; GPS is always omitted.

## State and background behavior

The latest recipe is persisted in private storage. Undo/redo is a 60-step session history. Batch work survives Activity recreation through the ViewModel but is not a foreground service: keep the app open. Process death can interrupt a batch. Already prepared output files and imported projects stay private on disk; the batch results list itself is session state. Projects can be reopened. Prepared exports are not a backup; Save copies to a chosen destination.

## Staged advanced features

Implemented: ordered overlay layers, opacity/visibility/reordering, reversible erase masks, sampled clone strokes, rectangular local blur and pixelation, opaque Cover, and 2–9-image fit collages. These are manual tools and do not claim semantic AI selection.

Deferred and hidden: neural subject segmentation, generative object erasing, healing/inpainting, learned upscaling, advanced image-layer compositing and arbitrary local colour masks. No model was bundled because accuracy, redistribution license, peak memory, size, latency and low-end-device performance have not been demonstrated. The next milestone should evaluate candidates on a fixed representative corpus before selecting one. No cloud fallback is permitted.

## Motion and accessibility

All tool cards use distinct Canvas drawings. One six-second clock is read during drawing rather than recomposing the whole list. The lifecycle and Android animator scale gate the clock; a user setting also disables it. System typography, TalkBack labels and 48 dp controls cover standard controls. Freehand work still requires spatial touch interaction; text has a centre-placement alternative. Full screen-reader parity for brush-based editing remains a device usability task.
