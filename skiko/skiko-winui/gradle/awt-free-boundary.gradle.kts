val checkWinuiAwtFreeBoundary by tasks.registering {
    group = "verification"
    description = "Verifies skiko-winui does not depend on AWT/Swing or AWT-only Skiko backend internals."

    val checkedFiles = provider {
        listOf(
            layout.projectDirectory.file("build.gradle.kts").asFile,
        ) + fileTree(layout.projectDirectory.dir("src")) {
            include("**/*.kt", "**/*.java", "**/*.cc", "**/*.cpp", "**/*.h", "**/*.hh")
        }.files
    }
    inputs.files(checkedFiles)

    doLast {
        val forbiddenPatterns = listOf(
            "java" + ".awt",
            "javax" + ".swing",
            "Hardware" + "Layer",
            "skiko" + "-awt-runtime",
            "localSkiko" + "RuntimeJar",
            "awt" + "Main",
            "exceptions" + "_handler",
            "jni" + "_helpers",
        )
        val violations = checkedFiles.get()
            .filter(File::isFile)
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (file.name == "build.gradle.kts" && line.trimStart().startsWith("exclude(")) {
                        return@mapIndexedNotNull null
                    }
                    val matched = forbiddenPatterns.firstOrNull(line::contains)
                    if (matched == null) {
                        null
                    } else {
                        "${file.relativeTo(projectDir)}:${index + 1}: forbidden '$matched': ${line.trim()}"
                    }
                }
            }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "skiko-winui must stay AWT-free. Forbidden references found:\n" +
                    violations.joinToString("\n")
            )
        }
    }
}

val checkWinuiJvmApiClasspathBoundary by tasks.registering {
    group = "verification"
    description = "Verifies winui-jvm does not compile against the Skiko AWT API variant."

    val compileClasspath = configurations.named("winuiJvmCompileClasspath")
    inputs.files(compileClasspath)

    doLast {
        val forbiddenComponents = compileClasspath.get()
            .incoming
            .resolutionResult
            .allComponents
            .map { component -> component.id.displayName }
            .filter { displayName ->
                displayName == "project :skiko" ||
                    displayName.startsWith("org.jetbrains.skiko:skiko:")
            }

        if (forbiddenComponents.isNotEmpty()) {
            throw GradleException(
                "winui-jvm must not compile against the Skiko AWT API variant. Forbidden components:\n" +
                    forbiddenComponents.distinct().joinToString("\n")
            )
        }

        val forbiddenEntries = listOf(
            "org/jetbrains/skiko/Actuals_awtKt",
            "org/jetbrains/skiko/HardwareLayer",
            "org/jetbrains/skiko/MainUIDispatcher_awtKt",
            "org/jetbrains/skiko/SkiaLayer",
            "org/jetbrains/skiko/SwingDispatcher",
            "org/jetbrains/skiko/SystemTheme_awtKt",
            "org/jetbrains/skiko/redrawer/",
            "org/jetbrains/skiko/swing/",
        )
        val artifactViolations = compileClasspath.get().files
            .flatMap { file ->
                when {
                    file.isFile && file.extension == "jar" ->
                        java.util.jar.JarFile(file).use { jar ->
                            jar.entries().asSequence().mapNotNull { entry ->
                                val matched = forbiddenEntries.firstOrNull(entry.name::startsWith)
                                if (matched == null) null else "${file.name}:${entry.name}"
                            }.toList()
                        }
                    file.isDirectory ->
                        file.walkTopDown()
                            .filter(File::isFile)
                            .mapNotNull { entry ->
                                val relativePath = entry.relativeTo(file).invariantSeparatorsPath
                                val matched = forbiddenEntries.firstOrNull(relativePath::startsWith)
                                if (matched == null) null else "${file.name}:$relativePath"
                            }
                            .toList()
                    else -> emptyList()
                }
            }

        if (artifactViolations.isNotEmpty()) {
            throw GradleException(
                "winui-jvm compile classpath contains AWT-only Skiko classes:\n" +
                    artifactViolations.distinct().joinToString("\n")
            )
        }
    }
}

tasks.named("check") {
    dependsOn(checkWinuiAwtFreeBoundary)
    dependsOn(checkWinuiJvmApiClasspathBoundary)
}
