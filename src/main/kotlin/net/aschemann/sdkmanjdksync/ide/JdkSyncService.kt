// SPDX-License-Identifier: Apache-2.0
package net.aschemann.sdkmanjdksync.ide

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.LanguageLevelProjectExtension
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.util.io.HttpRequests
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.aschemann.sdkmanjdksync.core.JavaVersionId
import net.aschemann.sdkmanjdksync.core.Lts
import net.aschemann.sdkmanjdksync.core.MavenToolchains
import net.aschemann.sdkmanjdksync.core.SdkPruning
import net.aschemann.sdkmanjdksync.core.StableLinks
import net.aschemann.sdkmanjdksync.core.SdkmanCatalog
import net.aschemann.sdkmanjdksync.core.SdkmanCli
import net.aschemann.sdkmanjdksync.core.SdkmanInstallation
import net.aschemann.sdkmanjdksync.core.javaMajorFromVersionString
import net.aschemann.sdkmanjdksync.core.newestInstalledPerMajor
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps the IDE's Java SDK list in sync with the newest SDKMAN JDK per managed major, and checks
 * SDKMAN for newer releases. Runs on startup, hourly, after settings changes and whenever another
 * component adds a Java SDK (e.g. "Setup SDK" for an old project).
 */
@Service(Service.Level.APP)
class JdkSyncService(private val cs: CoroutineScope) {

    private val log = thisLogger()
    private val started = AtomicBoolean(false)
    private val mutex = Mutex()
    private val syncing = AtomicBoolean(false)
    private var pending: Job? = null

    private var catalog: List<JavaVersionId> = emptyList()
    private var catalogFetched = 0L
    private val notifiedInvalid = mutableSetOf<InvalidSdk>()

    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        ApplicationManager.getApplication().messageBus.connect(cs).subscribe(
            ProjectJdkTable.JDK_TABLE_TOPIC,
            object : ProjectJdkTable.Listener {
                override fun jdkAdded(jdk: Sdk) {
                    if (!syncing.get() && jdk.sdkType is JavaSdk) requestSync()
                }

                override fun jdkRemoved(jdk: Sdk) {
                    if (!syncing.get() && jdk.sdkType is JavaSdk) jdk.name.toIntOrNull()?.let { userRemovedManagedSdk(it) }
                }
            },
        )
        cs.launch {
            while (isActive) {
                runCatching { sync() }.onFailure { log.warn("JDK sync failed", it) }
                delay(1.hours)
            }
        }
    }

    /** Debounced sync, e.g. after settings changes or SDK additions by other components. */
    fun requestSync() {
        pending?.cancel()
        pending = cs.launch {
            delay(3.seconds)
            runCatching { sync() }.onFailure { log.warn("JDK sync failed", it) }
        }
    }

    fun installation(): SdkmanInstallation = SdkmanInstallation.of(SyncSettings.get().state.sdkmanDir.orEmpty())

    suspend fun ltsMajors(): Set<Int> = withContext(Dispatchers.IO) {
        Lts.majors(::httpGet, Path.of(PathManager.getSystemPath(), "sdkman-jdk-sync", "lts.txt"))
    }

    /** SDKMAN's Java catalog for this platform, cached for six hours. */
    suspend fun catalog(): List<JavaVersionId> = withContext(Dispatchers.IO) {
        if (catalog.isEmpty() || System.currentTimeMillis() - catalogFetched > 6 * 3_600_000L) {
            val platform = installation().platform ?: return@withContext catalog
            runCatching { SdkmanCatalog.fetch(platform, ::httpGet) }
                .onSuccess {
                    catalog = it
                    catalogFetched = System.currentTimeMillis()
                }
                .onFailure { log.info("Cannot fetch SDKMAN catalog: ${it.message}") }
        }
        catalog
    }

    suspend fun sync(): Unit = mutex.withLock {
        val settings = SyncSettings.get()
        val sdkman = installation()
        if (!sdkman.exists) {
            log.info("No SDKMAN Java candidates in ${sdkman.dir}")
            return
        }
        val majors = settings.parsedMajors ?: ltsMajors()
        val best = newestInstalledPerMajor(sdkman.installedJava(), majors, settings.vendors)
            .mapNotNull { (major, id) -> sdkman.javaHome(id)?.let { major to (id to it.toString()) } }
            .toMap()

        val changes = mutableListOf<String>()
        var removed = emptyList<RemovedSdk>()
        var invalid = emptyList<InvalidSdk>()
        if (best.isNotEmpty()) {
            changes += withContext(Dispatchers.IO) { syncOutsideIde(best, settings) }
        }
        if (settings.state.manageSdkTable && best.isNotEmpty()) {
            val result = withContext(Dispatchers.EDT) { syncSdkTable(best, sdkman, settings) }
            changes += result.changes
            removed = result.removed
            invalid = result.invalid
        }
        if (changes.isNotEmpty()) {
            Notifier.notify(null, "Java SDKs updated", changes.joinToString("<br>"), NotificationType.INFORMATION)
        }
        if (removed.isNotEmpty()) notifyRemoved(removed)
        notifyInvalid(invalid)
        if (settings.state.checkUpdates) notifyUpdates(best.mapValues { it.value.first }, settings)
    }

    /** Stable symlinks and Maven toolchains.xml, both optional. */
    private fun syncOutsideIde(best: Map<Int, Pair<JavaVersionId, String>>, settings: SyncSettings): List<String> {
        val changes = mutableListOf<String>()
        val options = settings.state
        if (options.maintainLinks) {
            runCatching { StableLinks.sync(settings.linksPath, best.mapValues { Path.of(it.value.second) }) }
                .onSuccess { changes += it }
                .onFailure { log.warn("Cannot update JDK symlinks in ${settings.linksPath}", it) }
        }
        if (options.updateMavenToolchains) {
            val file = settings.toolchainsPath
            runCatching {
                val exists = Files.exists(file)
                if (!exists && !options.toolchainsAddMissing) return@runCatching
                val text = if (exists) Files.readString(file) else MavenToolchains.empty()
                val jdks = best.mapValues { (major, entry) ->
                    val home = if (options.toolchainsUseLinks && options.maintainLinks) {
                        StableLinks.linkFor(settings.linksPath, major).toString()
                    } else {
                        entry.second
                    }
                    MavenToolchains.Jdk(home, entry.first.vendor)
                }
                val result = MavenToolchains.update(text, jdks, options.toolchainsAddMissing)
                if (result.text != text) {
                    Files.createDirectories(file.parent)
                    if (exists) Files.copy(file, file.resolveSibling("${file.fileName}.bak"), StandardCopyOption.REPLACE_EXISTING)
                    Files.writeString(file, result.text)
                    LocalFileSystem.getInstance().refreshNioFiles(listOf(file))
                    changes += result.changes.map { "toolchains.xml: $it" }
                }
            }.onFailure { log.warn("Cannot update $file", it) }
        }
        return changes
    }

    private data class RemovedSdk(val name: String, val home: String?)

    /** A dead SDK that stays because open [projects] still select it and no SDK of the same major exists. */
    private data class InvalidSdk(val name: String, val projects: List<String>, val suggestion: String?)

    private data class SdkTableResult(val changes: List<String>, val removed: List<RemovedSdk>, val invalid: List<InvalidSdk>)

    private fun userRemovedManagedSdk(major: Int) {
        SyncSettings.get().setExcluded(major, true)
        Notifier.notify(
            null,
            "Java $major will no longer be managed",
            "You removed the SDK, so it will not be re-created.",
            NotificationType.INFORMATION,
            "Manage again" to { _ ->
                SyncSettings.get().setExcluded(major, false)
                requestSync()
            },
        )
    }

    private fun notifyRemoved(removed: List<RemovedSdk>) {
        val restorable = removed.filter { homeExists(it.home) }
        val actions = if (restorable.isEmpty()) {
            emptyArray()
        } else {
            arrayOf("Restore and keep" to { _: Project? -> restoreAndKeep(restorable) })
        }
        Notifier.notify(
            null,
            "Other Java SDKs removed",
            removed.joinToString(", ") { it.name },
            NotificationType.INFORMATION,
            *actions,
        )
    }

    private fun homeExists(home: String?): Boolean =
        home != null && runCatching { Files.isDirectory(Path.of(home)) }.getOrDefault(false)

    /** Once per situation: the hourly sync must not repeat a warning the user has already seen. */
    private fun notifyInvalid(invalid: List<InvalidSdk>) {
        notifiedInvalid.retainAll(invalid.toSet())
        for (sdk in invalid) {
            if (!notifiedInvalid.add(sdk)) continue
            val actions = sdk.suggestion?.let { target ->
                arrayOf("Switch to $target" to { _: Project? -> switchProjects(sdk.name, target) })
            } ?: emptyArray()
            Notifier.notify(
                null,
                "Java SDK ${sdk.name} no longer exists",
                "Still selected in ${sdk.projects.joinToString(", ")}. It stays selected until you choose another SDK.",
                NotificationType.WARNING,
                *actions,
            )
        }
    }

    private fun switchProjects(from: String, to: String) {
        ApplicationManager.getApplication().invokeLater {
            WriteAction.run<RuntimeException> {
                val table = ProjectJdkTable.getInstance()
                val old = table.findJdk(from) ?: return@run
                val replacement = table.findJdk(to) ?: return@run
                remapProjects(old, replacement)
            }
            requestSync()
        }
    }

    private fun restoreAndKeep(sdks: List<RemovedSdk>) {
        SyncSettings.get().keepSdks(sdks.map { it.name })
        ApplicationManager.getApplication().invokeLater {
            syncing.set(true)
            try {
                WriteAction.run<RuntimeException> {
                    val table = ProjectJdkTable.getInstance()
                    for (sdk in sdks) {
                        val home = sdk.home ?: continue
                        if (table.findJdk(sdk.name) == null) table.addJdk(JavaSdk.getInstance().createJdk(sdk.name, home, false))
                    }
                }
            } finally {
                syncing.set(false)
            }
        }
    }

    private fun syncSdkTable(
        best: Map<Int, Pair<JavaVersionId, String>>,
        sdkman: SdkmanInstallation,
        settings: SyncSettings,
    ): SdkTableResult {
        val changes = mutableListOf<String>()
        val removed = mutableListOf<RemovedSdk>()
        val invalid = mutableListOf<InvalidSdk>()
        val excluded = settings.excludedMajors
        syncing.set(true)
        try {
            WriteAction.run<RuntimeException> {
                val table = ProjectJdkTable.getInstance()
                val javaSdk = JavaSdk.getInstance()
                val managed = mutableMapOf<Int, Sdk>()

                for ((major, entry) in best) {
                    if (major in excluded) continue
                    val (id, home) = entry
                    val name = major.toString()
                    val existing = table.findJdk(name)
                    if (existing != null && existing.sdkType is JavaSdk && existing.homePath == home) {
                        managed[major] = existing
                        continue
                    }
                    val created = javaSdk.createJdk(name, home, false)
                    if (existing != null && existing.sdkType is JavaSdk) {
                        table.updateJdk(existing, created)
                        managed[major] = existing
                        changes += "Java $major → ${id.identifier}"
                    } else {
                        existing?.let { table.removeJdk(it) }
                        table.addJdk(created)
                        managed[major] = created
                        changes += "Java $major added (${id.identifier})"
                    }
                }

                if (settings.state.pruneUnmanaged) {
                    val managedSet = managed.values.toSet()
                    val keep = settings.keptSdks + excluded.map { it.toString() }
                    val unmanaged = table.allJdks.filter {
                        it.sdkType is JavaSdk && it !in managedSet &&
                            SdkPruning.shouldRemove(it.name, it.homePath, keep, sdkman.javaCandidates, settings.state.pruneOutsideSdkman)
                    }
                    for (sdk in unmanaged) {
                        val major = javaMajorFromVersionString(sdk.versionString)
                        val replacement = SdkPruning.automaticReplacement(major, managed.keys)?.let { managed[it] }
                        if (replacement != null) {
                            remapProjects(sdk, replacement).forEach { changes += "$it: ${sdk.name} → ${replacement.name}" }
                        } else {
                            val users = projectsUsing(sdk).map { it.name }
                            if (users.isNotEmpty()) {
                                if (!homeExists(sdk.homePath)) {
                                    val suggestion = SdkPruning.suggestedReplacement(major, managed.keys)?.let { managed[it]?.name }
                                    invalid += InvalidSdk(sdk.name, users, suggestion)
                                }
                                continue
                            }
                        }
                        removed += RemovedSdk(sdk.name, sdk.homePath)
                        table.removeJdk(sdk)
                    }
                }

                val defaultMajor = settings.state.defaultMajor?.trim()?.toIntOrNull() ?: managed.keys.maxOrNull()
                managed[defaultMajor]?.let { setDefaultProjectSdk(it) }
            }
        } finally {
            syncing.set(false)
        }
        return SdkTableResult(changes, removed, invalid)
    }

    private fun usesDirectly(module: Module, sdk: Sdk): Boolean {
        val roots = ModuleRootManager.getInstance(module)
        return !roots.isSdkInherited && roots.sdk?.name == sdk.name
    }

    private fun projectsUsing(sdk: Sdk): List<Project> = ProjectManager.getInstance().openProjects.filter { project ->
        ProjectRootManager.getInstance(project).projectSdkName == sdk.name ||
            ModuleManager.getInstance(project).modules.any { usesDirectly(it, sdk) }
    }

    /** Returns the names of the projects that were switched. */
    private fun remapProjects(old: Sdk, replacement: Sdk): List<String> {
        val affected = projectsUsing(old)
        for (project in affected) {
            val roots = ProjectRootManager.getInstance(project)
            if (roots.projectSdkName == old.name) roots.projectSdk = replacement
            for (module in ModuleManager.getInstance(project).modules) {
                if (usesDirectly(module, old)) ModuleRootModificationUtil.setModuleSdk(module, replacement)
            }
        }
        return affected.map { it.name }
    }

    private fun setDefaultProjectSdk(sdk: Sdk) {
        val defaultProject = ProjectManager.getInstance().defaultProject
        val roots = ProjectRootManager.getInstance(defaultProject)
        if (roots.projectSdk != sdk) roots.projectSdk = sdk
        JavaSdk.getInstance().getVersion(sdk)?.maxLanguageLevel?.let {
            LanguageLevelProjectExtension.getInstance(defaultProject).languageLevel = it
        }
    }

    private suspend fun notifyUpdates(installed: Map<Int, JavaVersionId>, settings: SyncSettings) {
        val available = catalog()
        if (available.isEmpty()) return
        val notified = settings.state.notifiedUpdates.orEmpty().split(',').filter { it.isNotEmpty() }.toMutableSet()
        for ((major, current) in installed) {
            val newest = available.filter { it.sameLine(current) && !it.earlyAccess }.maxOrNull() ?: continue
            if (newest <= current || newest.identifier in notified) continue
            notified += newest.identifier
            Notifier.notify(
                null,
                "Java $major update available",
                "${current.identifier} → ${newest.identifier}",
                NotificationType.INFORMATION,
                "Install ${newest.identifier}" to { project -> install(project, newest) },
            )
        }
        settings.state.notifiedUpdates = notified.joinToString(",")
    }

    /** Installs [id] via SDKMAN in the background, then re-syncs; calls [then] on success. */
    fun install(project: Project?, id: JavaVersionId, then: () -> Unit = {}) {
        val sdkman = installation()
        if (sdkman.isInstalled(id)) {
            then()
            requestSync()
            return
        }
        object : Task.Backgroundable(project, "Installing Java ${id.identifier} via SDKMAN", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val result = SdkmanCli(sdkman).install(id)
                if (result.ok) {
                    then()
                    requestSync()
                } else {
                    log.warn("sdk install java ${id.identifier} failed:\n${result.output}")
                    Notifier.notify(
                        project,
                        "SDKMAN install failed",
                        "sdk install java ${id.identifier} exited with ${result.exitCode}; see idea.log",
                        NotificationType.ERROR,
                    )
                }
            }
        }.queue()
    }

    private fun httpGet(url: String): String =
        HttpRequests.request(url).userAgent("sdkman-jdk-sync").connectTimeout(10_000).readTimeout(20_000).readString()
}
