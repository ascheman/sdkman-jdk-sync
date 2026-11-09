# JDK Sync for SDKMAN

An IntelliJ IDEA plugin that keeps the IDE's Java SDK list lean and current, based on the JDKs
installed with [SDKMAN!](https://sdkman.io).

IntelliJ never cleans up its SDK list: every `sdk install java ...`, every project that asks for
a JDK name it doesn't know, every Gradle/JBang toolchain adds another entry. After a few years the
list is long, full of `temurin-21 (7)`-style duplicates and dead paths, and new projects still
default to whatever happened to be configured years ago.

## Stable JDK paths: `~/.jdks-lts/<major>`

SDKMAN has `~/.sdkman/candidates/java/current`, but no stable path per *major version*: every
patch release lives in its own directory (`21.0.10-tem`, `21.0.13.0+9-tem`, ...). Anything that
needs a fixed JDK path (Maven `toolchains.xml`, scripts, other IDEs, `JAVA_HOME` of services)
breaks or silently stays on an old patch after each update.

The plugin therefore maintains one symlink per managed version:

```text
~/.jdks-lts/8  -> ~/.sdkman/candidates/java/8.0.512.0+9-zulu
~/.jdks-lts/17 -> ~/.sdkman/candidates/java/17.0.21.0+9-tem
~/.jdks-lts/21 -> ~/.sdkman/candidates/java/21.0.13.0+9-tem
~/.jdks-lts/25 -> ~/.sdkman/candidates/java/25.0.5.0+8-tem
```

Point your tools to `~/.jdks-lts/21` instead of `~/.sdkman/candidates/java/21.0.10-tem`, and they
follow every update without further changes. The links are only replaced, never real directories,
and the directory is configurable (or the feature can be switched off).

Optionally, the plugin also keeps Maven's `~/.m2/toolchains.xml` current (off by default):
the `jdkHome` of each JDK toolchain with a plain version (`1.8`, `11`, `17`, ...) and a matching
vendor is pointed to the symlink (or, if you prefer, to the versioned SDKMAN path). Version
ranges, other vendors, other toolchain types, comments and formatting are left alone; a backup of
the previous file is kept as `toolchains.xml.bak`. Adding toolchains for missing versions is a
separate opt-in.

## Features

- **One SDK per Java version.** For each managed major (default: all LTS versions, as published by
  the [foojay Disco API](https://api.foojay.io)) there is exactly one Java SDK, named by its major
  (`8`, `11`, `17`, `21`, `25`), pointing to the newest installed SDKMAN patch release.
- **Cleanup that respects your choices.** Other Java SDKs are removed if their home no longer
  exists or lives under SDKMAN; JDKs elsewhere (JBang, Gradle, manual installations) stay unless
  you opt in. Open projects and modules that use a removed SDK are switched to the same (or next
  higher) version first. If another component adds a Java SDK later, the plugin cleans up again.
  The notification offers *Restore and keep* for anything you want to retain, names on the
  "Never remove" list always stay, and a managed SDK you delete yourself is not re-created.
- **Defaults for new projects.** "Settings for New Projects" use the newest managed version and its
  language level.
- **Update notifications.** When SDKMAN offers a newer release of a managed line (same major,
  vendor and variant), a notification offers to install it with one click (`sdk install java ...`,
  without changing your SDKMAN default). After installation the SDK entry is repointed.
- **`.sdkmanrc` check.** When a project with an `.sdkmanrc` is opened and its `java=` entry is
  outdated, a notification offers to update it to the latest release of the same line or to the
  latest LTS line (installing it via SDKMAN if necessary).

Syncs run on startup, hourly, after settings changes and via *Tools | Sync SDKMAN JDKs Now*.

## Settings

*Settings | Tools | JDK Sync for SDKMAN*

| Setting | Default |
|---|---|
| SDKMAN directory | `$SDKMAN_DIR` or `~/.sdkman` |
| Managed versions | empty = all LTS versions; or e.g. `17, 21, 25` |
| Vendor preference | `tem, zulu, amzn` (SDKMAN vendor suffixes) |
| Keep one Java SDK per managed version | on |
| Remove other Java SDKs that are dead or live under SDKMAN | on |
| Also remove Java SDKs outside SDKMAN | off |
| Never remove | empty; comma-separated SDK names |
| Do not re-create | filled automatically when you delete a managed SDK |
| Default for new projects | empty = newest managed version |
| Maintain one symlink per managed version | on, in `~/.jdks-lts` |
| Update Maven toolchains.xml | off; then: `~/.m2/toolchains.xml`, point to symlinks: on, add missing: off |
| Notify about newer releases | on |
| Check `.sdkmanrc` on project open | on |
| Offer only LTS versions as "latest line" | on |

## Requirements

- IntelliJ IDEA 2026.3 or later (Community or Ultimate)
- SDKMAN! on macOS or Linux (installation uses `bash` and `sdkman-init.sh`)

## Building

```shell
# against a local IDE installation (fast, no download)
./gradlew buildPlugin -PplatformLocalPath="$HOME/Applications/IntelliJ IDEA.app"

# against a downloaded IDE (platformType/platformVersion in gradle.properties)
./gradlew buildPlugin
```

The plugin ZIP ends up in `build/distributions/`; install it via
*Settings | Plugins | ⚙ | Install Plugin from Disk…*.
`./gradlew runIde` starts a sandbox IDE with the plugin.

The `core` package has no IntelliJ dependencies and is covered by plain JUnit 5 tests
(`./gradlew test`).

## Roadmap

- Remove superseded patch releases from SDKMAN after an update (opt-in)
- Maven runner JRE / Gradle JVM defaults
- Gradle toolchain configuration (`org.gradle.java.installations.paths`)
- Other `.sdkmanrc` candidates (Maven, Gradle, ...)
- Windows support (SDKMAN via WSL/Git Bash)
- Platform tests for the SDK table synchronization

## License

[Apache License 2.0](LICENSE). Not affiliated with the SDKMAN! project.
