// SPDX-License-Identifier: Apache-2.0
package net.aschemann.sdkmanjdksync.ide

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
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

    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        ApplicationManager.getApplication().messageBus.connect(cs).subscribe(
            ProjectJdkTable.JDK_TABLE_TOPIC,
            object : ProjectJdkTable.Listener {
                override fun jdkAdded(jdk: Sdk) {
                    if (!syncing.get() && jdk.sdkType is JavaSdk) requestSync()
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
        if (best.isNotEmpty()) {
            changes += withContext(Dispatchers.IO) { syncOutsideIde(best, settings) }
        }
        if (settings.state.manageSdkTable && best.isNotEmpty()) {
            changes += withContext(Dispatchers.EDT) { syncSdkTable(best, settings) }
        }
        if (changes.isNotEmpty()) {
            Notifier.notify(null, "Java SDKs updated", changes.joinToString("<br>"), NotificationType.INFORMATION)
        }
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

    private fun syncSdkTable(best: Map<Int, Pair<JavaVersionId, String>>, settings: SyncSettings): List<String> {
        val changes = mutableListOf<String>()
        syncing.set(true)
        try {
            WriteAction.run<RuntimeException> {
                val table = ProjectJdkTable.getInstance()
                val javaSdk = JavaSdk.getInstance()
                val managed = mutableMapOf<Int, Sdk>()

                for ((major, entry) in best) {
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
                    val unmanaged = table.allJdks.filter { it.sdkType is JavaSdk && it !in managedSet }
                    for (sdk in unmanaged) {
                        val replacement = replacementFor(sdk, managed)
                        if (replacement != null) remapProjects(sdk, replacement)
                        table.removeJdk(sdk)
                    }
                    if (unmanaged.isNotEmpty()) changes += "Removed ${unmanaged.size} other Java SDK(s): ${unmanaged.joinToString { it.name }}"
                }

                val defaultMajor = settings.state.defaultMajor?.trim()?.toIntOrNull() ?: managed.keys.maxOrNull()
                managed[defaultMajor]?.let { setDefaultProjectSdk(it) }
            }
        } finally {
            syncing.set(false)
        }
        return changes
    }

    /** Same major if managed, otherwise the next higher managed major, otherwise the newest. */
    private fun replacementFor(sdk: Sdk, managed: Map<Int, Sdk>): Sdk? {
        val major = javaMajorFromVersionString(sdk.versionString) ?: return managed[managed.keys.maxOrNull()]
        return managed[major] ?: managed.keys.filter { it > major }.minOrNull()?.let { managed[it] } ?: managed[managed.keys.maxOrNull()]
    }

    private fun remapProjects(old: Sdk, replacement: Sdk) {
        for (project in ProjectManager.getInstance().openProjects) {
            val roots = ProjectRootManager.getInstance(project)
            if (roots.projectSdkName == old.name) roots.projectSdk = replacement
            for (module in ModuleManager.getInstance(project).modules) {
                val moduleRoots = ModuleRootManager.getInstance(module)
                if (!moduleRoots.isSdkInherited && moduleRoots.sdk?.name == old.name) {
                    ModuleRootModificationUtil.setModuleSdk(module, replacement)
                }
            }
        }
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
