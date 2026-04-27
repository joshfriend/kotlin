import jdk.jfr.consumer.RecordingFile
import java.nio.file.Paths

val disableInputsCheck = project.providers.gradleProperty("kotlin.test.instrumentation.disable.inputs.check").orNull?.toBoolean() == true

tasks.withType<Test>().configureEach {
    if (!disableInputsCheck) {
        val jfrFile = layout.buildDirectory.dir("jfr").get().asFile.resolve("test.jfr")
        val jfcFile = rootProject.file("gradle-file-read.jfc")
        val projectPath = projectDir.toPath()

        jvmArgs(
            "-XX:StartFlightRecording:" +
                    "settings=${jfcFile.absolutePath}," +
                    "disk=true," +
                    "dumponexit=true," +
                    "filename=${jfrFile.absolutePath}"
        )

        doFirst {
            jfrFile.parentFile.mkdirs()
        }

        doLast {
            println("Collecting accessed files...")
            val accessedFiles = RecordingFile.readAllEvents(jfrFile.toPath())
                .filter { it.eventType.name == "jdk.FileRead" }
                .mapNotNull { it.getString("path") }
                .map(Paths::get)
                .map { if (!it.isAbsolute) projectPath.resolve(it) else it }
                .filter { it.startsWith(projectPath) }
                .toSet()

            println("Collecting declared inputs")
            val declaredInputs = inputs.files.asFileTree.map { it.toPath() }.toSet()

            println("Looking for undeclared inputs")
            val undeclaredInputs = accessedFiles - declaredInputs

            println("Looking for used inputs")
            val usedInputs = accessedFiles.intersect(declaredInputs)

            println("Accessed files (${accessedFiles.size}):")
            accessedFiles.forEach { println(it) }
            println("Declared inputs (${declaredInputs.size})")
            println("Used inputs: ${usedInputs.size}")
            usedInputs.forEach { println(it) }
            println("Undeclared inputs (${undeclaredInputs.size}):")
            undeclaredInputs.forEach { println(it) }

            if (undeclaredInputs.isNotEmpty()) {
                error(buildString {
                    appendLine("Undeclared inputs found! (${undeclaredInputs.size})")
                    appendLine("First 10:")
                    undeclaredInputs.take(10).forEach { appendLine(it) }
                })
            }
        }
    }
}
