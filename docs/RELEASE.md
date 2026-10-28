# Releases

## A release candidate

Push a tag that starts with `v` on a green branch:

```
git tag v1.0.0-rc.1
git push origin v1.0.0-rc.1
```

Or, without pushing a tag, run the `Release candidate` workflow from the Actions tab on the
branch with the tag name as its input: it makes the tag at that commit when it publishes.

`.github/workflows/release.yml` then runs the pure engine tests, the unit tests and lint, and
the emulator walk, builds the optimised release APK and bundle, and only when all three jobs
pass publishes a GitHub Release for the tag with:

- `still-<tag>.apk`: R8-optimised, resources shrunk, signed with `release/rc-testing.jks`
- `still-<tag>.aab`: the bundle, signed with the same key
- `SHA256SUMS.txt`

The notes come from `release/NOTES.md`; update it with the tag.

## The test key is not the Play key

`release/rc-testing.jks` (alias `rc`, password `still-rc-testing`) exists so a tester can install
an optimised build and later update it in place. It is in the repository on purpose and is not
a secret. Gradle uses it only when `STILL_RC_SIGNING=1` is set and no `keystore.properties`
exists, which is what the release workflow does.

The Play build is signed by the owner, locally, with their own keystore through
`keystore.properties` (see `keystore.properties.example`): `./gradlew bundleRelease`. That key
never enters the repository or CI. A phone that has a candidate installed must uninstall it
before a Play-signed build will install, because the keys differ.

## Before tagging

- The branch's own checks are green (`.github/workflows/android.yml`).
- `app/build.gradle.kts` carries the new `versionName` and a higher `versionCode`.
- `release/NOTES.md` says what is in the build.
- `docs/VALIDATION.md` names the green run.
