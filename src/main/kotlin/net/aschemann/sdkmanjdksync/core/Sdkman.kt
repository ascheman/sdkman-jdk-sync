// SPDX-License-Identifier: Apache-2.0
package net.aschemann.sdkmanjdksync.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/** A local SDKMAN installation (default `$SDKMAN_DIR` or `~/.sdkman`). */
class SdkmanInstallation(val dir: Path) {

    val javaCandidates: Path get() = dir.resolve("candidates/java")

    val exists: Boolean get() = javaCandidates.isDirectory()

    /** SDKMAN platform id such as `darwinarm64`, as detected by SDKMAN itself. */
    val platform: String?
        get() = runCatching { dir.resolve("var/platform").readText().trim() }.getOrNull()?.ifEmpty { null }

    fun installedJava(): List<JavaVersionId> =
        if (!exists) {
            emptyList()
        } else {
            javaCandidates.listDirectoryEntries()
                .filter { it.isDirectory() && !it.isSymbolicLink() }
                .mapNotNull { JavaVersionId.parse(it.name) }
        }

    fun isInstalled(id: JavaVersionId): Boolean = javaCandidates.resolve(id.identifier).isDirectory()

    /** The JDK home of an installed version (handles the `zulu-8.jdk/Contents/Home` layout). */
    fun javaHome(id: JavaVersionId): Path? {
        val root = javaCandidates.resolve(id.identifier)
        if (root.resolve("release").isRegularFile()) return root
        return runCatching {
            root.listDirectoryEntries("*.jdk").map { it.resolve("Contents/Home") }.firstOrNull { it.resolve("release").isRegularFile() }
        }.getOrNull()
    }

    companion object {
        fun default(): SdkmanInstallation =
            SdkmanInstallation(System.getenv("SDKMAN_DIR")?.let { Paths.get(it) } ?: Paths.get(System.getProperty("user.home"), ".sdkman"))

        fun of(configured: String): SdkmanInstallation =
            if (configured.isBlank()) default() else SdkmanInstallation(Paths.get(configured.replaceFirst("~", System.getProperty("user.home"))))
    }
}

/** Remote SDKMAN catalog of Java versions. */
object SdkmanCatalog {
    const val API = "https://api.sdkman.io/2"

    fun url(platform: String): String = "$API/candidates/java/$platform/versions/all"

    fun parse(response: String): List<JavaVersionId> =
        response.split(',', '\n').mapNotNull { JavaVersionId.parse(it) }

    fun fetch(platform: String, http: (String) -> String): List<JavaVersionId> = parse(http(url(platform)))
}

/** Runs `sdk` commands through bash, without making the installed version the default. */
class SdkmanCli(private val installation: SdkmanInstallation) {

    data class Result(val exitCode: Int, val output: String) {
        val ok: Boolean get() = exitCode == 0
    }

    fun install(id: JavaVersionId, timeoutMinutes: Long = 30): Result {
        val script = "source \"\$SDKMAN_DIR/bin/sdkman-init.sh\" && printf 'n\\n' | sdk install java \"\$1\""
        val process = ProcessBuilder("/bin/bash", "-c", script, "sdk-install", id.identifier)
            .redirectErrorStream(true)
            .also { it.environment()["SDKMAN_DIR"] = installation.dir.toString() }
            .start()
        val output = StringBuilder()
        val reader = Thread { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }.apply { start() }
        if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            return Result(-1, output.append("timeout after $timeoutMinutes minutes").toString())
        }
        reader.join()
        val ok = process.exitValue() == 0 && Files.isDirectory(installation.dir.resolve("candidates/java/${id.identifier}"))
        return Result(if (ok) 0 else process.exitValue().takeIf { it != 0 } ?: 1, output.toString())
    }
}
