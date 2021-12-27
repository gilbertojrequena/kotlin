/*
 * Copyright 2010-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package org.jetbrains.kotlin.testing.native

import groovy.lang.Closure
import javax.inject.Inject
import org.gradle.api.*
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.getByType
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import org.jetbrains.kotlin.ExecClang
import org.jetbrains.kotlin.bitcode.CompileToBitcode
import org.jetbrains.kotlin.bitcode.CompileToBitcodeExtension
import org.jetbrains.kotlin.gradle.plugin.tasks.KonanInteropTask
import org.jetbrains.kotlin.gradle.plugin.tasks.interchangeBox
import org.jetbrains.kotlin.konan.target.*
import java.io.*
import java.time.Duration
import java.util.concurrent.TimeUnit

open class CompileNativeTest @Inject constructor(
        @InputFile val inputFile: File,
        @Input val target: KonanTarget
) : DefaultTask() {
    @OutputFile
    var outputFile = project.buildDir.resolve("bin/test/${target.name}/${inputFile.nameWithoutExtension}.o")

    @Input
    val clangArgs = mutableListOf<String>()

    @Input @Optional
    var sanitizer: SanitizerKind? = null

    private val sanitizerFlags = when (sanitizer) {
        null -> listOf()
        SanitizerKind.ADDRESS -> listOf("-fsanitize=address")
        SanitizerKind.THREAD -> listOf("-fsanitize=thread")
    }

    @TaskAction
    fun compile() {
        val plugin = project.extensions.getByType<ExecClang>()
        val args = clangArgs + sanitizerFlags + listOf(inputFile.absolutePath, "-o", outputFile.absolutePath)
        if (target.family.isAppleFamily) {
            plugin.execToolchainClang(target) {
                executable = "clang++"
                this.args = args
            }
        } else {
            plugin.execBareClang {
                executable = "clang++"
                this.args = args
            }
        }
    }
}

open class LlvmLinkNativeTest @Inject constructor(
        baseName: String,
        @Input val target: String,
        @InputFile val mainFile: File
) : DefaultTask() {

    @SkipWhenEmpty
    @InputFiles
    val inputFiles: ConfigurableFileCollection = project.files()

    @OutputFile
    var outputFile: File = project.buildDir.resolve("bitcode/test/$target/$baseName.bc")

    @TaskAction
    fun llvmLink() {
        val llvmDir = project.property("llvmDir")
        val tmpOutput = File.createTempFile("runtimeTests", ".bc").apply {
            deleteOnExit()
        }

        // The runtime provides our implementations for some standard functions (see StdCppStubs.cpp).
        // We need to internalize these symbols to avoid clashes with symbols provided by the C++ stdlib.
        // But llvm-link -internalize is kinda broken: it links modules one by one and can't see usages
        // of a symbol in subsequent modules. So it will mangle such symbols causing "unresolved symbol"
        // errors at the link stage. So we have to run llvm-link twice: the first one links all modules
        // except the one containing the entry point to a single *.bc without internalization. The second
        // run internalizes this big module and links it with a module containing the entry point.
        project.exec {
            executable = "$llvmDir/bin/llvm-link"
            args = listOf("-o", tmpOutput.absolutePath) + inputFiles.map { it.absolutePath }
        }

        project.exec {
            executable = "$llvmDir/bin/llvm-link"
            args = listOf(
                    "-o", outputFile.absolutePath,
                    mainFile.absolutePath,
                    tmpOutput.absolutePath,
                    "-internalize"
            )
        }
    }
}

open class LinkNativeTest @Inject constructor(
        @InputFiles val inputFiles: List<File>,
        @OutputFile val outputFile: File,
        @Internal val target: String,
        @Internal val linkerArgs: List<String>,
        private val  platformManager: PlatformManager,
        private val mimallocEnabled: Boolean
) : DefaultTask () {
    companion object {
        fun create(
                project: Project,
                platformManager: PlatformManager,
                taskName: String,
                inputFiles: List<File>,
                target: String,
                outputFile: File,
                linkerArgs: List<String>,
                mimallocEnabled: Boolean
        ): LinkNativeTest = project.tasks.create(
                taskName,
                LinkNativeTest::class.java,
                inputFiles,
                outputFile,
                target,
                linkerArgs,
                platformManager,
                mimallocEnabled)

        fun create(
                project: Project,
                platformManager: PlatformManager,
                taskName: String,
                inputFiles: List<File>,
                target: String,
                executableName: String,
                mimallocEnabled: Boolean,
                linkerArgs: List<String> = listOf()
        ): LinkNativeTest = create(
                project,
                platformManager,
                taskName,
                inputFiles,
                target,
                project.buildDir.resolve("bin/test/$target/$executableName"),
                linkerArgs, mimallocEnabled)
    }

    @Input @Optional
    var sanitizer: SanitizerKind? = null

    @get:Input
    val commands: List<List<String>>
        get() {
            // Getting link commands requires presence of a target toolchain.
            // Thus we cannot get them at the configuration stage because the toolchain may be not downloaded yet.
            val linker = platformManager.platform(platformManager.targetByName(target)).linker
            return linker.finalLinkCommands(
                    inputFiles.map { it.absolutePath },
                    outputFile.absolutePath,
                    listOf(),
                    linkerArgs,
                    optimize = false,
                    debug = true,
                    kind = LinkerOutputKind.EXECUTABLE,
                    outputDsymBundle = outputFile.absolutePath + ".dSYM",
                    needsProfileLibrary = false,
                    mimallocEnabled = mimallocEnabled,
                    sanitizer = sanitizer
            ).map { it.argsWithExecutable }
        }

    @TaskAction
    fun link() {
        for (command in commands) {
            project.exec {
                commandLine(command)
                if (!logger.isInfoEnabled() && command[0].endsWith("dsymutil")) {
                    // Suppress dsymutl's warnings.
                    // See: https://bugs.swift.org/browse/SR-11539.
                    val nullOutputStream = object: OutputStream() {
                        override fun write(b: Int) {}
                    }
                    errorOutput = nullOutputStream
                }
            }
        }
    }
}

open class ReportWithPrefixes @Inject constructor(
        @Input val testName: String,
        @InputFile val inputReport: File,
        @OutputFile val outputReport: File
) : DefaultTask() {
    @TaskAction
    fun process() {
        // TODO: Better to use proper XML parsing.
        var contents = inputReport.readText()
        contents = contents.replace("<testsuite name=\"", "<testsuite name=\"${testName}.")
        contents = contents.replace("classname=\"", "classname=\"${testName}.")
        outputReport.writeText(contents)
    }
}

/**
 * Returns a list of Clang -cc1 arguments (including -cc1 itself) that are used for bitcode compilation in Kotlin/Native.
 *
 * See also: [org.jetbrains.kotlin.backend.konan.BitcodeCompiler]
 */
private fun buildClangFlags(configurables: Configurables): List<String> = mutableListOf<String>().apply {
    require(configurables is ClangFlags)
    addAll(configurables.clangFlags)
    addAll(configurables.clangNooptFlags)
    val targetTriple = if (configurables is AppleConfigurables) {
        configurables.targetTriple.withOSVersion(configurables.osVersionMin)
    } else {
        configurables.targetTriple
    }
    addAll(listOf("-triple", targetTriple.toString()))
}.toList()

private fun createTestTask(
        project: Project,
        testName: String,
        testedTaskNames: List<String>,
        sanitizer: SanitizerKind?,
        configureCompileToBitcode: CompileToBitcode.() -> Unit = {}
): Task {
    val platformManager = project.project(":kotlin-native").findProperty("platformManager") as PlatformManager
    val googleTestExtension = project.extensions.getByName(RuntimeTestingPlugin.GOOGLE_TEST_EXTENSION_NAME) as GoogleTestExtension
    val testedTasks = testedTaskNames.map {
        project.tasks.getByName(it) as CompileToBitcode
    }
    val target = testedTasks.map {
        it.target
    }.distinct().single()
    val konanTarget = platformManager.targetByName(target)
    val compileToBitcodeTasks = testedTasks.mapNotNull {
        val name = "${it.name}TestBitcode"
        val task = project.tasks.findByName(name) as? CompileToBitcode ?:
            project.tasks.create(name,
                    CompileToBitcode::class.java,
                    "${it.folderName}Tests",
                    target, "test"
                    ).apply {
                srcDirs = it.srcDirs
                headersDirs = it.headersDirs + googleTestExtension.headersDirs

                this.sanitizer = sanitizer
                excludeFiles = emptyList()
                includeFiles = listOf("**/*Test.cpp", "**/*TestSupport.cpp", "**/*Test.mm", "**/*TestSupport.mm")
                dependsOn(it)
                dependsOn("downloadGoogleTest")
                compilerArgs.addAll(it.compilerArgs)
                this.configureCompileToBitcode()
            }
        if (task.inputFiles.count() == 0)
            null
        else
            task
    }
    // TODO: Consider using sanitized versions.
    val testFrameworkTasks = listOf(
        project.tasks.getByName("${target}Googletest") as CompileToBitcode,
        project.tasks.getByName("${target}Googlemock") as CompileToBitcode
    )

    val testSupportTask = project.tasks.getByName("${target}TestSupport${CompileToBitcodeExtension.suffixForSanitizer(sanitizer)}") as CompileToBitcode

    // TODO: It may make sense to merge llvm-link, compile and link to a single task.
    val llvmLinkTask = project.tasks.create(
            "${testName}LlvmLink",
            LlvmLinkNativeTest::class.java,
            testName, target, testSupportTask.outFile
    ).apply {
        val tasksToLink = (compileToBitcodeTasks + testedTasks + testFrameworkTasks)
        inputFiles.setFrom(tasksToLink.map { it.outFile })
        dependsOn(testSupportTask)
        dependsOn(tasksToLink)
    }

    val compileTask = project.tasks.create(
            "${testName}Compile",
            CompileNativeTest::class.java,
            llvmLinkTask.outputFile,
            konanTarget
    ).apply {
        this.sanitizer = sanitizer
        dependsOn(llvmLinkTask)
        clangArgs.addAll(buildClangFlags(platformManager.platform(konanTarget).configurables))
    }

    val mimallocEnabled = testedTaskNames.any { it.contains("mimalloc", ignoreCase = true) }
    val linkTask = LinkNativeTest.create(
            project,
            platformManager,
            "${testName}Link${CompileToBitcodeExtension.suffixForSanitizer(sanitizer)}",
            listOf(compileTask.outputFile),
            target,
            testName,
            mimallocEnabled
    ).apply {
        this.sanitizer = sanitizer
        dependsOn(compileTask)
    }

    return project.tasks.create(testName, ExecRunnerWithTimeout::class.java).apply {
        dependsOn(linkTask)

        workingDir = project.buildDir.resolve("testReports/$testName")
        val xmlReport = workingDir.resolve("report.xml")
        executable(linkTask.outputFile)
        project.objects.property<Duration>(Duration::class.java).also {
            it.set(Duration.ofMinutes(10))
            setProperty("timeout", it)
        }
        val filter = project.findProperty("gtest_filter");
        if (filter != null) {
            args("--gtest_filter=${filter}")
        }
        args("--gtest_output=xml:${xmlReport.absoluteFile}")
        when (sanitizer) {
            SanitizerKind.THREAD -> {
                val file = project.file("tsan_suppressions.txt")
                inputs.file(file)
                environment("TSAN_OPTIONS", "suppressions=${file.absolutePath}")
            }
            else -> {} // no action required
        }

        doFirst {
            workingDir.mkdirs()
        }

        val reportWithPrefixes = workingDir.resolve("report-with-prefixes.xml")
        val reportTask = project.tasks.register("${testName}Report", ReportWithPrefixes::class.java, testName, xmlReport, reportWithPrefixes)
        finalizedBy(reportTask)
    }
}

abstract class ExecRunnerWithTimeout @Inject constructor(@Internal val workerExecutor: WorkerExecutor): Exec() {
    internal interface Params : WorkParameters {
        var executableName: String
        var timeOut: Duration
    }

    internal abstract class RunLLDB @Inject constructor() : WorkAction<Params> {
        override fun execute() {
            Thread.sleep(Duration.ofSeconds(1L).toMillis())
            val testProcess = ProcessHandle.current()
                    .descendants()
                    .filter {
                        println("Process: ${it.info().commandLine().get()}")
                        it.info().command()
                                .takeIf { !it.isEmpty() }
                                ?.get()
                                ?.contains(parameters.executableName)
                                ?: false
                    }
                    .findFirst()
            if (testProcess.isPresent) {
                val timeout = System.currentTimeMillis() + parameters.timeOut.toMillis()
                println("Wait for process: ${testProcess.get().pid()} ${testProcess.get().info().command()}")
                while (testProcess.get().isAlive && System.currentTimeMillis() < timeout) {
                    Thread.onSpinWait()
                }
                println("Wait is over")
                if (testProcess.get().isAlive && System.currentTimeMillis() >= timeout) {
                    println("Let's kill it with sigabrt")
                    val handle = testProcess.get()
                    val procBuilder = ProcessBuilder("lldb",
                            "-o", "process attach -p ${handle.pid()}",
                            "-o", "process save-core ${handle.info().command().get()}.core.${handle.pid()}",
                            "-o", "exit")
                    val proc = procBuilder.start()
                    proc.waitFor(5L, TimeUnit.MINUTES)
                    val stdOut = proc.inputStream.bufferedReader().readText()
                    val stdErr = proc.errorStream.bufferedReader().readText()
                    println("DONE:")
                    println(stdOut)
                    println(stdErr)
                }
            }
        }
    }

    @TaskAction
    override fun exec() {
        val queue = workerExecutor.noIsolation()
        queue.submit(RunLLDB::class.java, Action<Params> {
            executableName = executable!!
            timeOut = timeout.get().dividedBy(2L)
        })
        super.exec()
        queue.await()
    }
}

// TODO: These tests should be created by `CompileToBitcodeExtension`
fun createTestTasks(
        project: Project,
        targetName: String,
        testTaskName: String,
        testedTaskNames: List<String>,
        configureCompileToBitcode: CompileToBitcode.() -> Unit = {}
): List<Task> {
    val platformManager = project.rootProject.project(":kotlin-native").findProperty("platformManager") as PlatformManager
    val target = platformManager.targetByName(targetName)
    val sanitizers: List<SanitizerKind?> = target.supportedSanitizers() + listOf(null)
    return sanitizers.map { sanitizer ->
        val suffix = CompileToBitcodeExtension.suffixForSanitizer(sanitizer)
        val name = testTaskName + suffix
        val testedNames = testedTaskNames.map {
            it + suffix
        }
        createTestTask(project, name, testedNames, sanitizer, configureCompileToBitcode)
    }
}
