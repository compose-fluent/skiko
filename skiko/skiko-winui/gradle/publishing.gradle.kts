import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar
import org.gradle.plugins.signing.SigningExtension
import java.util.Properties

plugins.apply("signing")

val skikoUpstreamProperties = Properties().apply {
    val file = rootProject.file("skiko/gradle.properties")
    if (file.isFile) {
        file.inputStream().use(::load)
    }
}
val skikoUpstreamDeployVersion = skikoUpstreamProperties.getProperty("deploy.version", "0.0.0")
val skikoVersion = providers.gradleProperty("skiko.version")
    .orElse(
        providers.gradleProperty("deploy.release")
            .map { isRelease -> if (isRelease.toBoolean()) skikoUpstreamDeployVersion else "$skikoUpstreamDeployVersion-SNAPSHOT" }
            .orElse("$skikoUpstreamDeployVersion-SNAPSHOT")
    )
val kotlinWinRtVersion = providers.gradleProperty("kotlinWinRt.version")
    .orElse("0.1.0-SNAPSHOT")
val kotlinWinRtGroup = providers.gradleProperty("kotlinWinRt.group")
    .orElse("io.github.compose-fluent")
val skikoWinuiGroup = providers.gradleProperty("skiko.winui.group")
    .orElse("io.github.compose-fluent")
val mavenCentralSnapshotUrl = providers.gradleProperty("skiko.winui.mavenCentralSnapshotUrl")
    .orElse("https://central.sonatype.com/repository/maven-snapshots/")
val hasSigningKey = providers.gradleProperty("signingInMemoryKey")
    .map(String::isNotBlank)
    .orElse(false)
val hasSigningPassword = providers.gradleProperty("signingInMemoryKeyPassword")
    .map(String::isNotBlank)
    .orElse(false)

tasks.register<Jar>("skikoWinuiSourcesJar") {
    group = "build"
    description = "Builds the skiko-winui sources jar for Maven publication."
    archiveClassifier.set("sources")
    from("src/commonMain/kotlin")
    from("src/winuiMain/kotlin")
    from("src/winuiJvmMain/kotlin")
}

tasks.register<Jar>("skikoWinuiJavadocJar") {
    group = "build"
    description = "Builds an empty skiko-winui javadoc jar for Maven Central publication."
    archiveClassifier.set("javadoc")
}

tasks.register<Jar>("skikoWinuiWindowsRuntimeSourcesJar") {
    group = "build"
    description = "Builds the skiko-winui Windows runtime sources jar for Maven publication."
    archiveClassifier.set("sources")
}

tasks.register<Jar>("skikoWinuiWindowsRuntimeJavadocJar") {
    group = "build"
    description = "Builds an empty skiko-winui Windows runtime javadoc jar for Maven Central publication."
    archiveClassifier.set("javadoc")
}

extensions.configure<PublishingExtension>("publishing") {
    repositories {
        maven {
            name = "BuildRepo"
            url = rootProject.layout.buildDirectory.dir("repo").get().asFile.toURI()
        }
        maven {
            name = "MavenCentral"
            url = uri(mavenCentralSnapshotUrl.get())
            credentials {
                username = providers.gradleProperty("mavenCentralUsername").orNull
                password = providers.gradleProperty("mavenCentralPassword").orNull
            }
        }
    }
    publications {
        create("skikoWinuiJvm", MavenPublication::class.java) {
            groupId = skikoWinuiGroup.get()
            artifactId = "skiko-winui"
            version = skikoVersion.get()
            artifact(tasks.named("winuiJvmJar"))
            artifact(tasks.named("skikoWinuiSourcesJar"))
            artifact(tasks.named("skikoWinuiJavadocJar"))
            pom {
                name.set("Skiko WinUI JVM")
                description.set("AWT-free Skiko WinUI JVM backend")
                url.set("https://github.com/compose-fluent/skiko")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                developers {
                    developer {
                        id.set("composefluent")
                        name.set("Compose Fluent")
                        url.set("https://github.com/compose-fluent")
                    }
                }
                scm {
                    url.set("https://github.com/compose-fluent/skiko")
                    connection.set("scm:git:git://github.com/compose-fluent/skiko.git")
                    developerConnection.set("scm:git:ssh://git@github.com/compose-fluent/skiko.git")
                }
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
                    appendNode("groupId", kotlinWinRtGroup.get())
                    appendNode("artifactId", "winrt-runtime-jvm")
                    appendNode("version", kotlinWinRtVersion.get())
                    appendNode("scope", "compile")
                }
                dependencies.appendNode("dependency").apply {
                    appendNode("groupId", skikoWinuiGroup.get())
                    appendNode("artifactId", "skiko-winui-windows")
                    appendNode("version", skikoVersion.get())
                    appendNode("scope", "runtime")
                }
            }
        }
        create("skikoWinuiWindowsRuntime", MavenPublication::class.java) {
            groupId = skikoWinuiGroup.get()
            artifactId = "skiko-winui-windows"
            version = skikoVersion.get()
            artifact(tasks.named<Jar>("skikoWinuiWindowsRuntimeJar"))
            artifact(tasks.named("skikoWinuiWindowsRuntimeSourcesJar"))
            artifact(tasks.named("skikoWinuiWindowsRuntimeJavadocJar"))
            pom {
                name.set("Skiko WinUI Windows Runtime")
                description.set("Windows native runtime for the AWT-free Skiko WinUI backend")
                url.set("https://github.com/compose-fluent/skiko")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                developers {
                    developer {
                        id.set("composefluent")
                        name.set("Compose Fluent")
                        url.set("https://github.com/compose-fluent")
                    }
                }
                scm {
                    url.set("https://github.com/compose-fluent/skiko")
                    connection.set("scm:git:git://github.com/compose-fluent/skiko.git")
                    developerConnection.set("scm:git:ssh://git@github.com/compose-fluent/skiko.git")
                }
            }
        }
    }
}

extensions.configure<SigningExtension>("signing") {
    if (hasSigningKey.zip(hasSigningPassword, Boolean::and).get()) {
        useInMemoryPgpKeys(
            providers.gradleProperty("signingInMemoryKeyId").orNull,
            providers.gradleProperty("signingInMemoryKey").get(),
            providers.gradleProperty("signingInMemoryKeyPassword").get(),
        )
        sign(project.extensions.getByType(PublishingExtension::class.java).publications)
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

tasks.register("publishSkikoWinuiToMavenCentral") {
    group = "publishing"
    description = "Publishes the current skiko-winui JVM and Windows runtime artifacts to Maven Central."
    dependsOn(
        "publishSkikoWinuiJvmPublicationToMavenCentralRepository",
        "publishSkikoWinuiWindowsRuntimePublicationToMavenCentralRepository",
    )
}
