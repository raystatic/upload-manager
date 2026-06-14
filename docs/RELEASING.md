# Releasing

The library version lives in
[`upload-manager/build.gradle.kts`](../upload-manager/build.gradle.kts)
(`version = "x.y.z"`) and the public group/artifact is
`dev.uploadmanager:upload-manager`.

## Cut a release

1. Update the version in `upload-manager/build.gradle.kts`.
2. Move the `Unreleased` notes in [CHANGELOG.md](../CHANGELOG.md) under a new
   version heading and add the compare/tag links.
3. Commit, tag, and push:
   ```bash
   git tag v0.1.0 && git push origin v0.1.0
   ```

## Publish

**Local / internal repo** (works out of the box):

```bash
./gradlew :upload-manager:publishToMavenLocal
# or publish to your own repository by adding it to the `publishing { repositories { ... } }` block
```

**Maven Central** requires, in addition to the existing POM:

- A Sonatype OSSRH (Central Portal) account and namespace verification.
- GPG signing — add the `signing` plugin and configure
  `signing.keyId` / `signing.password` / a key, then `signed(publishing.publications)`.
- The Sonatype publishing plugin (or manual bundle upload).

These steps need credentials and are intentionally left out of the repo. Wire
them via CI secrets when you're ready to publish publicly.

## Verify the artifact

```bash
./gradlew :upload-manager:assembleRelease :upload-manager:sourcesJar
# inspect upload-manager/build/outputs/aar and the sources jar
```
