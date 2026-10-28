# Still 1.0.0-rc.2

A release candidate for testing, signed with the release-candidate test key so it installs and
updates on any Android 8.0+ phone.

Since rc.1: the home puts the tools on the first screen whatever has been opened before. The
open door lies across, the quick tools are one sideways strip under it with the next one
peeking in, the four doors follow, and the recent pictures are a compact strip at the bottom.

## Install

Download `still-v1.0.0-rc.2.apk`, open it on the phone, allow the install from this source.
Check the download against `SHA256SUMS.txt` if you want to be sure of the file.

## What is in it

- **Remove the background.** Opens on the picture with what would go under a red wash and
  asks first. Remove it, or adjust: More and Less move the edge, Keep and Remove strokes correct
  it, the edge is decided from the picture's own colours with the colour fringe taken off. Then
  what goes behind: transparent, a colour, a blur, or the picture drained of colour behind a
  subject in colour; an outline for stickers; a crop to the subject.
- Every tool from the editor and the batch screen: crop, square, light, colour, curves, looks on
  the picture itself, detail, markup, retouch, layers, frame, scan a page, lift a signature, red
  eye, tap a white, stickers, photo under a size, passport, convert, strip hidden data,
  watermark, collage.
- A swipe anywhere on the picture moves the chosen slider; a split view; a quick preview under
  the finger; the editor lit by the picture's own colours.
- No internet permission. Originals are never written to. Every number is measured from the
  written file.

## Not the Play build

This APK is signed with `release/rc-testing.jks`, a key kept in the repository for testing.
The Play build must be signed with the owner's upload key (docs/RELEASE.md); a phone that has
this candidate installed will need it uninstalled before the Play build goes on.
