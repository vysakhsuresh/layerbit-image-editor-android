# Source and dependency policy

The owner has not selected a redistribution license for the original app source. Public visibility is not an open-source license. No Sheaf implementation/assets were copied. Do not advertise this repository as open source until the owner chooses a license.

Runtime dependencies are deliberately small:

| Component | Purpose | License |
|---|---|---|
| Kotlin standard library / coroutines (via AndroidX) | Language, background work | Apache-2.0 |
| AndroidX Core and Activity | Android integration, scoped picker, file sharing | Apache-2.0 |
| AndroidX Lifecycle | ViewModel and lifecycle | Apache-2.0 |
| AndroidX Compose UI/Foundation/Material3 | Native UI, drawing, controls | Apache-2.0 |
| AndroidX ExifInterface | Orientation and selective metadata | Apache-2.0 |
| AndroidX DocumentFile | User-selected destination directories | Apache-2.0 |

Build/test only: Gradle and AGP (Apache-2.0); JUnit 4 (EPL-1.0), Hamcrest (BSD-3-Clause), Robolectric (MIT), AndroidX Test (Apache-2.0). GitHub Actions are build infrastructure and are not bundled in the APK. Transitive versions are visible with `./gradlew :app:dependencies`.

Apache-2.0 license text is included in the app assets and available in Settings. No custom fonts, stock images, external icon packs, additional image-processing SDKs or ML models are bundled. Encoding uses Android platform codecs; AndroidX may include native graphics helpers. The launcher art and tool animations are original vector/Canvas code.

Before adding a dependency: inspect its license, transitive dependencies, merged permissions, reflection/native code, telemetry and model-download behavior. Pin a reviewed version. Reject network, tracking or server requirements. Do not silently substitute an unbundled model.

Official implementation references:
- https://developer.android.com/build/releases/agp-8-13-0-release-notes
- https://developer.android.com/reference/android/graphics/BitmapFactory.Options
- https://developer.android.com/reference/androidx/exifinterface/media/ExifInterface
- https://developer.android.com/training/data-storage/shared/photopicker
- https://developer.android.com/training/secure-file-sharing/setup-sharing

Private data is explicitly excluded from cloud backups and device transfers using the [Android backup rules](https://developer.android.com/identity/data/autobackup), alongside `allowBackup=false`. CI records the resolved release runtime dependencies and checks the debug/release merged manifests.
