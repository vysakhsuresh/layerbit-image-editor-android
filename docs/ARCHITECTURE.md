# Architecture

One Gradle module of app code, boundaries by package, enforced where it matters by a script.

```
com.layerbit.studio
  engine/     pure Kotlin, no android.* import, compiled by tools/jvm-check.sh
              Recipe, Json, RecipeJson, ColorMath (TonePipeline, CurveMath, Looks),
              Spatial, Histogram/AutoEnhance, Geometry2D, JpegSegments, FitSize, Names, Collage
  image/      the Android renderer: Renderer (decode, geometry, finish), Codecs (encode,
              target size, selective EXIF, lossless strip), Budget
  files/      StudioFile, ImageStore (the only way a picture enters), Exporter (the only
              thing that writes a Uri), Workspace, Handoff, CrashLog
  ops/        ToolId, Op, the batch operations, PipelineOp (the steps composed into one op)
  jobs/       JobRunner and JobState, app-scoped
  prefs/      StudioSettings, Prompts
  ui/         theme, components, icons (Glyphs), home (one gesture), editor, pipeline (the
              batch screen), tools (collage), intro, settings, about
```

## A recipe, not pixels

The editor never changes pixels in place. The user's work is a `Recipe`: geometry (perspective quad, quarter turns, flips, straighten, crop, size), tone, curves, a look and its strength, layers, a frame. The renderer reads the source copy and the recipe and produces pixels. That is what makes undo a stack of recipes, a preset a recipe without its crop and layers, a batch the same recipe over more inputs, and the preview the same arithmetic as the export.

Recipes are JSON written and read by hand, with a default for every missing field, so versions stay mutually readable. The 0.1 preview's format is read and translated.

## Two render stages

`Renderer.geometry` decodes within the budget (a preview decodes at twice the sampling and is halved with the filter, so it is the export's own shrink rather than skipped pixels), turns the picture upright from EXIF, applies the perspective map, turns, flips, straightens into the bounding box, crops in the straightened frame and resizes through a halving pyramid. The editor caches its result and reuses it while a slider moves.

`Renderer.finish` copies the pixels to an `IntArray` and hands them to the engine: `TonePipeline` (matrix then tables, across the cores by rows), `Spatial.detail` (unsharp masks at radii scaled to the picture), vignette, grain; then the layers on a Canvas and the frame. Every pure step is the same at any size, which `RendererTest.previewPredictsExport` measures.

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

## Not here, on purpose

No neural segmentation, inpainting or upscaling. A model would arrive with its licence, size, peak memory, latency and accuracy measured on a fixed corpus, and would run only on the device. No image-loading library. No PDF, RAW or HDR engine.
