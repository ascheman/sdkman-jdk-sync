// SPDX-License-Identifier: Apache-2.0
package net.aschemann.sdkmanjdksync.core

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readSymbolicLink

/**
 * Stable per-major symlinks such as `~/.jdks-lts/21 -> ~/.sdkman/candidates/java/21.0.13.0+9-tem`.
 *
 * Tools that need a fixed JDK path (Maven toolchains, scripts, other IDEs) can point to the link
 * and follow updates without any change, like SDKMAN's `current`, but per major version.
 */
object StableLinks {

    /** Creates/updates one link per major and removes numeric links for majors no longer managed. */
    fun sync(dir: Path, homes: Map<Int, Path>): List<String> {
        val changes = mutableListOf<String>()
        Files.createDirectories(dir)
        for ((major, home) in homes) {
            val link = dir.resolve(major.toString())
            if (link.isSymbolicLink() && link.readSymbolicLink() == home) continue
            if (Files.exists(link, LinkOption.NOFOLLOW_LINKS) && !link.isSymbolicLink()) {
                changes += "skipped $link: exists and is not a symlink"
                continue
            }
            val tmp = dir.resolve(".$major.tmp")
            Files.deleteIfExists(tmp)
            Files.createSymbolicLink(tmp, home)
            Files.move(tmp, link, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            changes += "$link -> $home"
        }
        for (link in dir.listDirectoryEntries()) {
            val major = link.name.toIntOrNull() ?: continue
            if (major !in homes && link.isSymbolicLink()) {
                Files.delete(link)
                changes += "removed $link"
            }
        }
        return changes
    }

    fun linkFor(dir: Path, major: Int): Path = dir.resolve(major.toString())
}
