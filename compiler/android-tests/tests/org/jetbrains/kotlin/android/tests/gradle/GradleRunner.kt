/*
 * Copyright 2010-2016 JetBrains s.r.o.
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
package org.jetbrains.kotlin.android.tests.gradle

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.text.Strings
import org.jetbrains.kotlin.android.tests.OutputUtils
import org.jetbrains.kotlin.android.tests.PathManager
import org.jetbrains.kotlin.android.tests.run.RunUtils

class GradleRunner(pathManager: PathManager) {
    private val listOfCommands = ArrayList<String?>()

    init {
        val cmdName = if (SystemInfo.isWindows) "gradlew.bat" else "gradlew"
        listOfCommands.add(pathManager.tmpFolder + "/" + cmdName)
        listOfCommands.add("--no-daemon")
        listOfCommands.add("--build-file")
        listOfCommands.add(pathManager.tmpFolder + "/build.gradle")
    }


    fun clean() {
        println("Building gradle project...")
        val result = RunUtils.execute(generateCommandLine("clean"))
        OutputUtils.checkResult(result)
    }

    fun assembleAndroidTest() {
        println("Building gradle project...")
        val build = generateCommandLine("assembleAndroidTest")
        build.addParameter("--stacktrace")
        build.addParameter("--warn")
        val result = RunUtils.execute(build)
        OutputUtils.checkResult(result)
    }

    fun installAndroidDebugTest(flavorName: String) {
        println("Install tests for flavor $flavorName...")
        val capitalizedFlavor = Strings.capitalize(flavorName)
        OutputUtils.checkResult(RunUtils.execute(generateCommandLine("install" + capitalizedFlavor + "Debug")))
        OutputUtils.checkResult(RunUtils.execute(generateCommandLine("install" + capitalizedFlavor + "DebugAndroidTest")))
    }

    fun uninstallDebugAndroidTest() {
        println("Uninstall tests...")
        RunUtils.execute(generateCommandLine("uninstallDebugAndroidTest"))
        RunUtils.execute(generateCommandLine("uninstallDebug"))
    }

    private fun generateCommandLine(taskName: String): GeneralCommandLine {
        val commandLine = GeneralCommandLine(listOfCommands)
        commandLine.addParameter(taskName)
        return commandLine
    }
}
