# Layerbit Studio working rules

- Work only in this repository. Sheaf, Deja and the site are references and must not be edited from here.
- The app has no INTERNET permission and never will. Preserve the manifest removals and `tools/verify_manifest.py`. No ads, analytics, accounts, cloud fallback, paywalls or model downloads.
- A picture the user picked is read once into a private copy. Only `files/Exporter.kt` opens a Uri for writing, and only one the user just created or a folder they granted. A `contentResolver.openOutputStream(source.uri)` anywhere else is the bug.
- `engine/` imports nothing from `android.*`. Every number the app shows and every pixel it changes is decided there, and `tools/jvm-check.sh` compiles it without an Android SDK and runs its tests. A change that needs an Android class belongs in `image/`, behind a pure function.
- Preview must predict export. Any effect whose look depends on resolution is scaled by `Geometry2D.resolutionScale`; `RendererTest.previewPredictsExport` guards it.
- Every tool works. A neural tool arrives, if ever, with its licence, size, memory and accuracy measured on a fixed corpus first, never as a button that does something unknown.
- Batch tools are `List<StudioFile> -> Op -> List<StudioFile>`. Do not add a single-file `run`.
- Numbers shown to the user are measured on the file that was written, never estimated.
- Run `tools/jvm-check.sh`, then Gradle's unit tests, lint and the manifest check before pushing. Report checks that could not run.
- Do not add a source redistribution licence without the owner's choice. Verify new third-party licences before adding them; the dependency list is deliberately short.
