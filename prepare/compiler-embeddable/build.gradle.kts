import org.gradle.api.file.DuplicatesStrategy
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.support.serviceOf
import java.util.regex.Pattern.quote
import kotlin.io.path.exists

description = "Kotlin Compiler (embeddable)"

plugins {
    kotlin("jvm")
    id("project-tests-convention")
}

val testCompilationClasspath by configurations.creating
val testCompilerClasspath by configurations.creating {
    isCanBeConsumed = false
    extendsFrom(configurations["runtimeElements"])
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
    }
}

val nativeImageClasspath by configurations.creating {
    isCanBeConsumed = false
    extendsFrom(configurations["runtimeElements"])
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
    }
}

dependencies {
    api(project(":compiler:build-tools:kotlin-build-tools-api"))
    runtimeOnly(kotlinStdlib())
    runtimeOnly(project(":kotlin-script-runtime"))
    runtimeOnly(commonDependency("org.jetbrains.kotlin:kotlin-reflect")) { isTransitive = false }
    runtimeOnly(project(":kotlin-daemon-embeddable"))
    runtimeOnly(libs.kotlinx.coroutines.core) { isTransitive = false }
    testImplementation(libs.junit4)
    testImplementation(kotlinTest("junit"))
    testCompilationClasspath(kotlinStdlib())
}

sourceSets {
    "main" {}
    "test" { projectDefault() }
}

val runtimeJar = runtimeJar(embeddableCompiler()) {
    exclude("com/sun/jna/**")
    exclude("org/jetbrains/annotations/**")
    mergeServiceFiles()
}

val sourcesJar = sourcesJar {
    val compilerTask = project(":kotlin-compiler").tasks.named<Jar>("sourcesJar")
    dependsOn(compilerTask)
    val archiveOperations = serviceOf<ArchiveOperations>()
    from(compilerTask.map { it.archiveFile }.map { archiveOperations.zipTree(it) })
}

val javadocJar = javadocJar {
    val compilerTask = project(":kotlin-compiler").tasks.named<Jar>("javadocJar")
    dependsOn(compilerTask)
    val archiveOperations = serviceOf<ArchiveOperations>()
    from(compilerTask.map { it.archiveFile }.map { archiveOperations.zipTree(it) })
}

publish {
    setArtifacts(listOf(runtimeJar, sourcesJar, javadocJar))
}

projectTests {
    testTask(jUnitMode = JUnitMode.JUnit4) {
        dependsOn(runtimeJar)
        val testCompilerClasspathProvider = project.provider { testCompilerClasspath.asPath }
        val testCompilationClasspathProvider = project.provider { testCompilationClasspath.asPath }
        val runtimeJarPathProvider = project.provider { runtimeJar.get().outputs.files.asPath }
        doFirst {
            systemProperty(
                "compilerClasspath",
                "${runtimeJarPathProvider.get()}${File.pathSeparator}${testCompilerClasspathProvider.get()}"
            )
            systemProperty("compilationClasspath", testCompilationClasspathProvider.get())
        }
    }
}

val kotlincniTask = tasks.register<Exec>("kotlincni") {
    description = "Build a native image of the kotlin-compiler-embeddable"

    inputs.files(runtimeJar)
    inputs.files(nativeImageClasspath)

    val mainClass = "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"
    val outputFile = layout.buildDirectory.file("bin/kotlincni")
    outputs.file(outputFile)

    val javaLauncher = javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.GRAAL_VM)
    }
    val javaHome = javaLauncher.get().executablePath.asFile.toPath().parent.parent
    val classpathFiles = files(runtimeJar, nativeImageClasspath)

    doFirst {
        val nativeImageName = if (System.getProperty("os.name").lowercase().contains("windows")) "native-image.cmd" else "native-image"
        val nativeImageBin = javaHome.resolve("lib/svm/bin/$nativeImageName")
        if (!nativeImageBin.exists()) {
            throw GradleException("native-image not found at ${nativeImageBin.toAbsolutePath()} (JAVA_HOME=${javaHome.toAbsolutePath()})")
        }
        val fullClasspath = classpathFiles.joinToString(File.pathSeparator) { it.absolutePath }
        commandLine(
            nativeImageBin,
            "--add-opens", "java.base/java.lang=ALL-UNNAMED",
            "--add-opens", "java.base/java.io=ALL-UNNAMED",
            "--add-opens", "java.base/java.nio=ALL-UNNAMED",
            "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
            "--add-opens", "java.desktop/javax.swing=ALL-UNNAMED",
            "-H:+AddAllCharsets",
            "-H:+UnlockExperimentalVMOptions",
            "-H:+AllowJRTFileSystem",
            "-cp", fullClasspath,
            "-o", outputFile.get().asFile.absolutePath,
            mainClass,
        )
    }
}

val distDir: String by rootProject.extra

val kotlincniDist = tasks.register<Copy>("kotlincniDist") {
    description = "Build the kotlin-compiler-embeddable native distribution"
    duplicatesStrategy = DuplicatesStrategy.FAIL
    rename(quote("-${version}"), "")
    rename(quote("-${bootstrapKotlinVersion}"), "")
    dependsOn(kotlincniTask)
    destinationDir = File("$distDir/kotlincni")
    val binFiles = files(layout.buildDirectory.dir("bin"))
    val wrapperScriptFiles = files("bin/kotlincni.sh", "bin/kotlincni.bat")
    into("bin") {
        from(binFiles)
        from(wrapperScriptFiles) {
            filePermissions {
                unix("rwxr-xr-x")
            }
        }
    }
    val licenseFiles = files("$rootDir/license")
    into("license") {
        from(licenseFiles)
    }
    val resourceFiles = files("$rootDir/compiler/cli/cli-base/resources")
    into("resources") {
        from(resourceFiles)
    }
    val librariesStripVersionFiles = files(nativeImageClasspath)
    into("lib") {
        from(librariesStripVersionFiles) {
            rename {
                it.replace(Regex("-\\d.*\\.jar\$"), ".jar")
            }
        }
        filePermissions {
            unix("rw-r--r--")
        }
    }
}
