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

tasks.named("check") {
    dependsOn(checkWinuiAwtFreeBoundary)
}
