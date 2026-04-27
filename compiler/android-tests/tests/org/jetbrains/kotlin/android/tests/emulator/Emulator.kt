/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.kotlin.android.tests.emulator

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.text.StringUtil
import org.jetbrains.kotlin.android.tests.OutputUtils
import org.jetbrains.kotlin.android.tests.PathManager
import org.jetbrains.kotlin.android.tests.run.RunUtils
import org.jetbrains.kotlin.android.tests.run.RunUtils.RunSettings
import org.junit.Assert
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.regex.Matcher
import java.util.regex.Pattern

class Emulator(private val pathManager: PathManager, private val platform: String?) {
    private val createCommand: GeneralCommandLine
        get() {
            val commandLine = GeneralCommandLine()
            val androidCmdName = if (SystemInfo.isWindows) "avdmanager.bat" else "avdmanager"
            commandLine.exePath = pathManager.toolsFolderInAndroidSdk + "/bin/" + androidCmdName
            commandLine.addParameter("create")
            commandLine.addParameter("avd")
            commandLine.addParameter("--force")
            commandLine.addParameter("-n")
            commandLine.addParameter(AVD_NAME)
            commandLine.addParameter("-p")
            commandLine.addParameter(pathManager.getAndroidAvdRoot())
            commandLine.addParameter("-k")

            // Allow override of system image via system property
            val overrideImage = System.getProperty("kotlin.android.avd.systemImage")
            if (overrideImage != null && !overrideImage.isEmpty()) {
                commandLine.addParameter(overrideImage)
            } else if (platform == X86) {
                commandLine.addParameter("system-images;android-$SYSTEM_IMAGE_API;default;x86_64")
            } else {
                commandLine.addParameter("system-images;android-$SYSTEM_IMAGE_API;default;arm64-v8a")
            }

            commandLine.withEnvironment("ANDROID_SDK_ROOT", pathManager.androidSdkRoot)
            commandLine.withEnvironment("ANDROID_HOME", pathManager.androidSdkRoot)

            return commandLine
        }

    private val startCommand: GeneralCommandLine
        get() {
            val commandLine = GeneralCommandLine()

            // Prefer the new SDK layout: $SDK/emulator/emulator
            val sdkRoot = pathManager.androidSdkRoot
            val newLayoutEmulator = "$sdkRoot/emulator/emulator"

            val newEmulatorFile = File(newLayoutEmulator)
            if (newEmulatorFile.isFile() && newEmulatorFile.canExecute()) {
                commandLine.exePath = newEmulatorFile.absolutePath
            } else {
                // Fallback to the old path if needed
                commandLine.exePath = pathManager.emulatorFolderInAndroidSdk + "/emulator"
            }

            commandLine.addParameter("-avd")
            commandLine.addParameter(AVD_NAME)
            commandLine.addParameter("-no-audio")
            commandLine.addParameter("-no-window")
            commandLine.addParameter("-gpu")
            commandLine.addParameter("swiftshader_indirect")
            if (isRunningInCi) {
                println("Disabling emulator hardware acceleration in CI")
                commandLine.addParameter("-no-accel")
            } else {
                println("Using emulator hardware acceleration for local run")
            }
            return commandLine
        }

    private val waitCommand: GeneralCommandLine
        get() {
            val commandLine = createAdbCommand()
            commandLine.addParameter("wait-for-device")
            return commandLine
        }

    private val stopCommandForAdb: GeneralCommandLine
        get() {
            val commandLine = createAdbCommand()
            commandLine.addParameter("kill-server")
            return commandLine
        }

    private val stopCommand: GeneralCommandLine?
        get() {
            if (SystemInfo.isWindows) {
                val commandLine = GeneralCommandLine()
                commandLine.exePath = "taskkill"
                commandLine.addParameter("/F")
                commandLine.addParameter("/IM")
                commandLine.addParameter("emulator-$platform.exe")
                return commandLine
            }
            return null
        }

    private fun patchAvdConfigSystemImage() {
        val configIni = File(pathManager.getAndroidAvdRoot(), "config.ini")
        if (!configIni.isFile()) return

        try {
            val lines = Files.readAllLines(configIni.toPath())
            val patched: MutableList<String?> = ArrayList(lines.size)
            var replaced = false

            for (line in lines) {
                if (line.startsWith("image.sysdir.1=")) {
                    // Force the correct relative path under SDK root
                    if (platform == X86) {
                        patched.add("image.sysdir.1=system-images/android-$SYSTEM_IMAGE_API/default/x86_64/")
                    } else {
                        patched.add("image.sysdir.1=system-images/android-$SYSTEM_IMAGE_API/default/arm64-v8a/")
                    }
                    replaced = true
                } else {
                    patched.add(line)
                }
            }

            if (!replaced) {
                // If the key was missing, add it explicitly
                if (platform == X86) {
                    patched.add("image.sysdir.1=system-images/android-$SYSTEM_IMAGE_API/default/x86_64/")
                } else {
                    patched.add("image.sysdir.1=system-images/android-$SYSTEM_IMAGE_API/default/arm64-v8a/")
                }
            }

            Files.write(configIni.toPath(), patched)
        } catch (e: IOException) {
            throw RuntimeException(e.message, e)
        }
    }

    fun createEmulator() {
        println("Creating emulator...")
        OutputUtils.checkResult(RunUtils.execute(RunSettings(this.createCommand, "no", true, null, false)))
        // Fix up stale system image path in config.ini, otherwise, there will be androidSdk/androidSdk in path.
        patchAvdConfigSystemImage()
    }

    private fun createAdbCommand(): GeneralCommandLine {
        val commandLine = GeneralCommandLine()
        commandLine.exePath = pathManager.platformToolsFolderInAndroidSdk + "/" + "adb"
        return commandLine
    }

    fun startServer() {
        val commandLine = createAdbCommand()
        commandLine.addParameter("start-server")
        println("Start adb server...")
        OutputUtils.checkResult(RunUtils.execute(RunSettings(commandLine, null, true, "ADB START:", true)))
    }

    fun startEmulator() {
        startServer()
        println("Starting emulator with ANDROID_HOME/ANDROID_SDK_ROOT: " + pathManager.androidSdkRoot)
        val startCommand = this.startCommand
        startCommand.withEnvironment("ANDROID_SDK_ROOT", pathManager.androidSdkRoot)
        startCommand.withEnvironment("ANDROID_HOME", pathManager.androidSdkRoot)
        RunUtils.executeOnSeparateThread(RunSettings(startCommand, null, false, "START: ", true))
        printLog()
    }

    fun printLog() {
        val commandLine = createAdbCommand()
        commandLine.addParameter("logcat")
        commandLine.addParameter("-v")
        commandLine.addParameter("time")
        commandLine.addParameter("-s")
        commandLine.addParameter("dalvikvm:W")
        commandLine.addParameter("TestRunner:I")
        RunUtils.executeOnSeparateThread(RunSettings(commandLine, null, false, "LOGCAT: ", true))
    }

    fun waitEmulatorStart() {
        println("Waiting for emulator start...")
        OutputUtils.checkResult(RunUtils.execute(this.waitCommand))
        val bootCheckCommand = createAdbCommand()
        bootCheckCommand.addParameter("shell")
        bootCheckCommand.addParameter("getprop")
        bootCheckCommand.addParameter("sys.boot_completed")

        var counter = 0
        var execute = RunUtils.execute(bootCheckCommand)
        while (counter < 20) {
            val output = execute.output
            if (output.trim { it <= ' ' }.endsWith("1")) {
                println("Emulator fully booted!")
                return
            }
            println("Waiting for emulator boot ($counter)...")
            try {
                Thread.sleep(10000)
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
            counter++
            execute = RunUtils.execute(bootCheckCommand)
        }
        Assert.fail("Can't find booted emulator: " + execute.output)
    }

    fun waitForPackageManager() {
        println("Waiting for Package Manager...")
        val packageManagerCheckCommand = createAdbCommand()
        packageManagerCheckCommand.addParameters("shell", "pm", "path", "android")

        var counter = 0
        var execute = RunUtils.execute(packageManagerCheckCommand)
        while (counter < 20) {
            val output = execute.output
            if (execute.status && output.contains("package:")) {
                println("Package Manager is ready!")
                return
            }

            println("Waiting for Package Manager ($counter)...")
            try {
                Thread.sleep(10000)
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
            counter++
            execute = RunUtils.execute(packageManagerCheckCommand)
        }
        Assert.fail("Can't access Package Manager: " + execute.output)
    }

    fun stopEmulator() {
        println("Stopping emulator...")

        stopRedundantEmulators(pathManager)

        finishProcess("emulator64-$platform")
        finishProcess("emulator-$platform")
    }

    fun finishEmulatorProcesses() {
        println("Stopping adb...")
        OutputUtils.checkResult(RunUtils.execute(this.stopCommandForAdb))
        finishProcess("adb")
        stopDdmsProcess()
    }

    fun runTestsViaInstrumentation(suiteClassName: String?): String {
        println("Running tests via adb instrumentation for $suiteClassName...")
        val adbCommand = createAdbCommand()
        adbCommand.addParameters(
            "shell", "am", "instrument", "-w", "-r",
            "-e", "class", suiteClassName,
            "org.jetbrains.kotlin.android.tests.gradle/org.jetbrains.kotlin.android.tests.KotlinBoxInstrumentation"
        )
        val execute = RunUtils.execute(adbCommand)
        return execute.output
    }

    private fun stopRedundantEmulators(pathManager: PathManager) {
        val commandLineForListOfDevices = createAdbCommand()
        commandLineForListOfDevices.addParameter("devices")
        val runResult = RunUtils.execute(commandLineForListOfDevices)
        OutputUtils.checkResult(runResult)

        val matcher: Matcher = EMULATOR_PATTERN.matcher(runResult.output)
        var isDdmsStopped = false
        while (matcher.find()) {
            println("Stopping redundant emulator...")
            val commandLineForStoppingEmulators = GeneralCommandLine()
            if (SystemInfo.isWindows) {
                commandLineForStoppingEmulators.exePath = "taskkill"
                commandLineForStoppingEmulators.addParameter("/F")
                commandLineForStoppingEmulators.addParameter("/IM")
                commandLineForStoppingEmulators.addParameter("emulator-arm.exe")
                OutputUtils.checkResult(RunUtils.execute(commandLineForStoppingEmulators))
                break
            } else {
                if (!isDdmsStopped && SystemInfo.isUnix) {
                    stopDdmsProcess()
                    isDdmsStopped = true
                }
                commandLineForStoppingEmulators.exePath = pathManager.platformToolsFolderInAndroidSdk + "/adb"
                commandLineForStoppingEmulators.addParameter("-s")
                commandLineForStoppingEmulators.addParameter(matcher.group())
                commandLineForStoppingEmulators.addParameter("emu")
                commandLineForStoppingEmulators.addParameter("kill")
                OutputUtils.checkResult(RunUtils.execute(commandLineForStoppingEmulators))
            }
        }
        OutputUtils.checkResult(RunUtils.execute(commandLineForListOfDevices))
    }

    companion object {
        const val ARM: String = "arm"
        const val X86: String = "x86"
        private const val AVD_NAME = "kotlin_box_test_avd"
        private const val SYSTEM_IMAGE_API = "26"

        private val EMULATOR_PATTERN: Pattern = Pattern.compile("emulator-([0-9])*")

        private val isRunningInCi: Boolean
            get() = java.lang.Boolean.getBoolean("kotlin.test.android.teamcity")
                    || !StringUtil.isEmpty(System.getenv("TEAMCITY_VERSION"))

        //Only for Unix
        private fun stopDdmsProcess() {
            if (SystemInfo.isUnix) {
                val listOfEmulatorProcess = GeneralCommandLine()
                listOfEmulatorProcess.exePath = "sh"
                listOfEmulatorProcess.addParameter("-c")
                listOfEmulatorProcess.addParameter("ps aux | grep emulator")
                val runResult = RunUtils.execute(listOfEmulatorProcess)
                OutputUtils.checkResult(runResult)
                val pidFromPsCommand = OutputUtils.getPidFromPsCommand(runResult.output)
                if (pidFromPsCommand != null) {
                    val killCommand = GeneralCommandLine()
                    killCommand.exePath = "kill"
                    killCommand.addParameter(pidFromPsCommand)
                    RunUtils.execute(killCommand)
                }
            }
        }

        //Only for Unix
        private fun finishProcess(processName: String) {
            if (SystemInfo.isUnix) {
                val pidOfProcess = GeneralCommandLine()
                if (SystemInfo.isMac) {
                    pidOfProcess.exePath = "pgrep"
                    pidOfProcess.addParameter("-f")
                } else {
                    pidOfProcess.exePath = "pidof"
                }
                pidOfProcess.addParameter(processName)
                val runResult = RunUtils.execute(pidOfProcess)
                val processIdsStr = runResult.output.substring(("pidof $processName").length)
                val processIds = StringUtil.getWordsIn(processIdsStr)
                for (pid in processIds) {
                    val killCommand = GeneralCommandLine()
                    killCommand.exePath = "kill"
                    killCommand.addParameter("-s")
                    killCommand.addParameter("9")
                    killCommand.addParameter(pid)
                    RunUtils.execute(killCommand)
                }
            }
        }
    }
}
