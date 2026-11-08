# Changelog

## [Unreleased]

### Added

- One Java SDK per managed major (default: LTS versions from foojay), pointing to the newest
  installed SDKMAN release; removal of all other Java SDKs with remapping of open projects
- Default SDK and language level for new projects
- Notifications about newer releases of the managed lines, with one-click installation via SDKMAN
- `.sdkmanrc` check on project open with updates to the latest release of the line or the latest LTS line
- Stable per-major symlinks (`~/.jdks-lts/<major>`) for tools outside the IDE
- Optional updates of Maven's `toolchains.xml` (only `jdkHome` of matching JDK toolchains)
- Settings page and *Tools | Sync SDKMAN JDKs Now*
