import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar

val skikoVersion = providers.gradleProperty("skiko.version")
    .orElse("0.0.0-SNAPSHOT")
val kotlinWinRtVersion = providers.gradleProperty("kotlinWinRt.version")
    .orElse("0.1.0-SNAPSHOT")

extensions.configure<PublishingExtension>("publishing") {
    repositories {
        maven {
            name = "BuildRepo"
            url = rootProject.layout.buildDirectory.dir("repo").get().asFile.toURI()
        }
    }
    publications {
        create("skikoWinuiJvm", MavenPublication::class.java) {
            groupId = "org.jetbrains.skiko"
            artifactId = "skiko-winui-winuijvm"
            version = skikoVersion.get()
            artifact(tasks.named("winuiJvmJar"))
            pom {
                name.set("Skiko WinUI JVM")
                description.set("AWT-free Skiko WinUI JVM backend")
            }
            pom.withXml {
                val dependencies = asNode().appendNode("dependencies")
                dependencies.appendNode("dependency").apply {
                    appendNode("groupId", "org.jetbrains.skiko")
                    appendNode("artifactId", "skiko")
                    appendNode("version", skikoVersion.get())
                    appendNode("scope", "compile")
                }
                dependencies.appendNode("dependency").apply {
                    appendNode("groupId", "io.github.composefluent.winrt")
                    appendNode("artifactId", "winrt-runtime")
                    appendNode("version", kotlinWinRtVersion.get())
                    appendNode("scope", "compile")
                }
            }
        }
        create("skikoWinuiWindowsRuntime", MavenPublication::class.java) {
            groupId = "org.jetbrains.skiko"
            artifactId = "skiko-winui-windows"
            version = skikoVersion.get()
            artifact(tasks.named<Jar>("skikoWinuiWindowsRuntimeJar"))
            pom {
                name.set("Skiko WinUI Windows Runtime")
                description.set("Windows native runtime for the AWT-free Skiko WinUI backend")
            }
        }
    }
}

tasks.register("publishSkikoWinuiToMavenLocal") {
    group = "publishing"
    description = "Publishes the current skiko-winui JVM and Windows runtime artifacts to Maven Local."
    dependsOn(
        "publishSkikoWinuiJvmPublicationToMavenLocal",
        "publishSkikoWinuiWindowsRuntimePublicationToMavenLocal",
    )
}

tasks.register("publishSkikoWinuiToBuildRepo") {
    group = "publishing"
    description = "Publishes the current skiko-winui JVM and Windows runtime artifacts to the local BuildRepo."
    dependsOn(
        "publishSkikoWinuiJvmPublicationToBuildRepoRepository",
        "publishSkikoWinuiWindowsRuntimePublicationToBuildRepoRepository",
    )
}
