# Roadmap

What comes after the current branch, in the order it should land. Each item is a measurable
change, not a feature name; the first three are fixes already made on this branch and listed so
the reasoning stays with the code.

## Done on this branch, from the first outside review

1. **Metadata removal kept the picture's orientation.** The lossless strip used to drop the EXIF
   block whole, so a phone's portrait turned on its side after cleaning. It now writes the
   Orientation field back on its own, in a 34-byte segment that holds nothing else, and treats
   that segment as not hidden. Upright pictures get no EXIF at all. `JpegSegmentsTest` covers it.
2. **Cut-out compositing is correct source-over.** Colour is weighted by both layers' alpha, so a
   translucent edge over a translucent ground keeps its colour (`Segment.over`).
3. **No full-size buffers for the mask, the paper map or the blurred backdrop.** The mask is
   feathered at the analysis size and read bilinearly per pixel; the scan's paper estimate and
   the blurred backdrop stay quarter-size maps. The export's working set is back to the two
   `IntArray`s `Budget.kt` assumes.

## Next, in order

1. **Cut-out edge refinement.** Decide at the analysis size as now, then refine the band around
   the edge at export resolution from colour against the two models, drop colour halos by
   pulling edge pixels toward the kept side's colour, and add a magnified edge loupe to the
   panel. Measure on a fixed set of hair, fur, glass and thin objects before and after.
2. **Recoverable batch jobs.** Write each produced file and its note to the workspace as it is
   made, so a cancelled or killed run keeps what it finished; offer "retry the failures" from the
   results; survive process death by reading the workspace back on open.
3. **Selective adjustments.** Brush, radial and linear masks carrying exposure, colour,
   sharpness and blur, each a layer in the recipe, undoable and editable. The renderer already
   has the per-pixel pipeline; the mask is the new part, and the cut-out's mask code is the start.
4. **Richer layers.** Imported picture layers with transforms, duplication, blend modes and
   per-layer masks, on the existing marks list.
5. **Precision export comparison.** A 1:1 loupe over the export against the edited preview, with
   the measured bytes and any resolution change shown beside it.
6. **The animation setting re-read while the app is open**, and a looks strip in the batch
   steps like the editor's. (The home card, the editor's bar and the dock were redone in the
   bento pass; the split view and the banded render core landed with it.)

## Release checks beyond the screen walk

- Save through a document provider and a granted folder, then reopen the saved file in the editor.
- All eight EXIF orientations through the editor, the lossless strip and the batch.
- Interrupt a batch mid-run; the finished results must survive.
- Transparent PNGs and WebPs through every panel and export.
- A 24 MP picture with the frame, layers, cut-out and scan on, on a phone with a 256 MB heap,
  with peak memory from `dumpsys meminfo` recorded in `docs/VALIDATION.md`.
- A fixed corpus of real portraits, products, documents and hard cut-outs, kept out of the
  repository, scored before and after any change to `engine/Segment.kt` or `engine/Scan.kt`.
- Bring the branch into `main` through a reviewed pull request; generate the baseline profile
  on a device and commit it; complete the bundled font and dependency notices.

## Not planned

- Any neural model, until one has been measured on the corpus above for quality, memory and
  licence, and the measurement is in this repository.
- Internet permission, accounts, ads, or a paid tier.
