# Architecture

One Gradle module of app code, boundaries by package, enforced where it matters by a script.

```
com.layerbit.still
  engine/     pure Kotlin, no android.* import, compiled by tools/jvm-check.sh
              Recipe, Json, RecipeJson, ColorMath (TonePipeline, CurveMath, Looks),
              Spatial, Histogram/AutoEnhance, Geometry2D, JpegSegments, FitSize, Names, Collage
  image/      the Android renderer: Renderer (decode, geometry, finish), Codecs (encode,
              target size, selective EXIF, lossless strip), Budget
  files/      StillFile, ImageStore (the only way a picture enters), Exporter (the only
              thing that writes a Uri), Workspace, Handoff, CrashLog
  ops/        ToolId, Op, the batch operations, PipelineOp (the steps composed into one op)
  jobs/       JobRunner and JobState, app-scoped
  prefs/      StillSettings, Prompts
  ui/         theme, components, icons (Glyphs), home (one gesture), editor, pipeline (the
              batch screen), tools (collage), intro, settings, about
```

## A recipe, not pixels

The editor never changes pixels in place. The user's work is a `Recipe`: geometry (perspective quad, quarter turns, flips, straighten, crop, size), tone, curves, a look and its strength, layers, a frame. The renderer reads the source copy and the recipe and produces pixels. That is what makes undo a stack of recipes, a preset a recipe without its crop and layers, a batch the same recipe over more inputs, and the preview the same arithmetic as the export.

Recipes are JSON written and read by hand, with a default for every missing field, so versions stay mutually readable. The 0.1 preview's format is read and translated.

## Two render stages

`Renderer.geometry` decodes within the budget (a preview decodes at twice the sampling and is halved with the filter, so it is the export's own shrink rather than skipped pixels), turns the picture upright from EXIF, applies the perspective map, turns, flips, straightens into the bounding box, crops in the straightened frame and resizes through a halving pyramid. The editor caches its result and reuses it while a slider moves, and keeps a half-size copy beside it: while a finger is on a slider the preview is rendered from that copy (a quarter of the pixels), and the full-size preview, the one that predicts the export, follows the moment the finger lifts. Debug builds log both timings.

`Renderer.finish` copies the pixels to an `IntArray` and hands them to the engine: `TonePipeline` (matrix then tables), `ScanMath` (the paper's light divided out), `Spatial.detail` (unsharp masks at radii scaled to the picture), vignette, grain; then the layers on a Canvas, the cut-out, and the frame.

Every stage that treats a row without its neighbours in other rows runs in bands across the cores through `engine/Parallel.kt`: the tone pipeline, each pass of a separable blur, the unsharp mask, vignette, grain, the paper cleanup and the cut-out compositing. It is a plain JDK pool of at most four threads with no Android in it, so the JVM tests run the same bands; a band asked for from inside a band runs inline, so the pool is never waited on from the pool. `ParallelTest` proves the bands give the sequential answer pixel for pixel and prints the two timings.

The cut-out (`engine/Segment.kt`) is decided once at a fixed analysis size chosen from the aspect alone: background colours learnt from the border, a first guess from what looks unlike them, then a geodesic distance from each seed set where crossing an edge costs more than crossing flat colour. The user's strokes are the only hard decisions; the mask is cached by picture, geometry and strokes and scaled to the preview and to the export, so both share one edge. The two searches, towards the subject and towards the background, run side by side. More and Less grow or shrink the decision by a separable max or min. Then the edge itself: in a band either side of the hard edge, each full-size pixel is judged from the picture's own colours against two maps made at the analysis size, the local subject colour and the local background colour (box-weighted means of the confident pixels on each side); the pixel's projection between them is its alpha, leaning on the mask where the two colours are too alike to say, and the background's share is taken out of its colour so a soft edge carries no fringe. An outline is a ring just outside the kept area; a crop to the subject is the kept area's bounds with a margin; a colour-pop backdrop is the picture drained of colour behind the subject. Every pure step is the same at any size, which `RendererTest.previewPredictsExport` measures.

## Order of geometry, and why the crop is where it is

Perspective works on the upright source. Turns and flips follow. Straightening rotates into a bounding box. The crop is measured as fractions of that box, so turning the straighten dial moves the picture under a crop that stays where the user put it, and an aspect preset chosen after a quarter turn means what it says. The 0.1 preview measured the crop on the source, which is why 16:9 after a turn came out 9:16; `Geometry2D.rotateCrop` translates those recipes.

## The budget

`Budget.exportPixels` is a twentieth of the heap in pixels, between 2 and 24 MP, with an 8192-pixel edge. The working bitmap is 4 bytes a pixel and the finish stage holds one `IntArray` copy, so the peak is about 8 bytes a pixel plus the encoder. Computed, not measured; `VALIDATION.md` names the device run that confirms or lowers it.

## Metadata

An export copies nothing from the source's EXIF block. If the user chooses to keep camera fields, eight named tags are copied one at a time; the location, serial numbers and the embedded thumbnail are never on the list. "Remove hidden data" on a JPEG walks the marker segments and keeps JFIF, the ICC profile and Adobe's colour marker, dropping APP1 and the rest, so the entropy-coded picture is byte for byte what it was. Other formats are re-encoded losslessly.

## Jobs

`JobRunner` runs one op at a time on the application scope and publishes `JobState`. A batch survives the screen that started it and a rotation; it does not survive the process. Sheaf runs its jobs as a foreground-service Worker; this app does not yet, because an image batch is seconds to a minute and a notification permission ask on the first export is a poor first impression. The change is contained in `jobs/` if the trade-off turns out wrong.

## Motion

`ProvideLivelyClock` is one slow infinite transition at the root. Glyphs read it in their draw lambda only, so the clock ticking redraws pictures and never recomposes a card. Off in Settings or when Android's animator scale is zero; every glyph then shows a rest frame.

The same clock drives everything else that moves (`ui/components/Ambient.kt`, `Celebrate.kt`): the four pools of colour drifting behind each page, the breathing front door and its walking dashes, the sheen on the amber button, the rise of cards into a screen, and the burst when an export or a batch finishes. Screen changes are spring slides with a fade in `StillApp`. All of it is still when the clock is null.

Behind the editor the backdrop's pools wear the picture's own colours: `engine/Palette.kt` takes a sparse sample of the preview, bins it by hue, weights each pixel by saturation and brightness, and lifts the fullest bins' means to a saturation and brightness that read as a light; a grey picture gives nothing and the brand's colours stand in.

A slider can hand itself to the canvas: a tap on its name makes the picture its track, a sideways swipe anywhere moves it (`TouchMode.Scrub`), and the name and value sit large over the picture while it moves.

The look itself is one set of tokens: Space Grotesk, the site's face, from display sizes down, with Fira Code for measured numbers; a deep ink ground with one amber band and the neon accents in `Neon`; cards as glass (`StillColors.Glass`) with a lit top edge; pill buttons with a glow; option rows as grids of equal cells; sliders as a thick track that fills from zero under a lit thumb.

## Not here, on purpose

No neural segmentation, inpainting or upscaling. A model would arrive with its licence, size, peak memory, latency and accuracy measured on a fixed corpus, and would run only on the device. No image-loading library. No PDF, RAW or HDR engine.
