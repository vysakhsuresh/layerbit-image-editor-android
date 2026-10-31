# Dependencies and licences

The owner has not selected a redistribution licence for the app's own source. Public visibility is not an open-source licence.

Runtime dependencies are deliberately few:

| Component | Purpose | Licence |
|---|---|---|
| Kotlin standard library, coroutines | Language, background work | Apache-2.0 |
| AndroidX Core, Activity, Lifecycle | Android integration, photo picker, ViewModel | Apache-2.0 |
| AndroidX Compose UI, Foundation, Material3, material-icons-core | Native UI | Apache-2.0 |
| AndroidX Navigation Compose | Routes | Apache-2.0 |
| AndroidX ExifInterface | Orientation; the eight camera fields a user may keep | Apache-2.0 |
| AndroidX DocumentFile | Display names; folder writes through a granted tree | Apache-2.0 |
| AndroidX ProfileInstaller | Installs the baseline profile below API 31 | Apache-2.0 |
| Space Grotesk (four weights) | The family typeface | SIL Open Font Licence 1.1 |
| Fira Code (variable weight) | Measured numbers: slider values, sizes | SIL Open Font Licence 1.1 (docs/licences/FIRA-CODE-OFL.txt) |

Build and test only: Gradle and AGP (Apache-2.0), JUnit 4 (EPL-1.0), Hamcrest (BSD-3), Robolectric (MIT), AndroidX Test, UI Automator and Macrobenchmark (Apache-2.0). `tools/jvm-check.sh` fetches the Kotlin compiler and JUnit from Maven Central for the engine check; none of that reaches the APK.

Compose brings Emoji2, whose initializer can ask an external font provider for downloads. The manifest removes `EmojiCompatInitializer`; it is never initialised by hand; `tools/verify_manifest.py` and the instrumented test enforce it.

No image-loading library, no processing SDK, no ML model, no network client, no analytics, no ads.

Before adding a dependency: read its licence and its transitive dependencies, build and inspect the merged manifest for permissions, look for reflection, native code, telemetry and download behaviour, and pin a reviewed version. Reject anything that needs the network.
