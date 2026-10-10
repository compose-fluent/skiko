import org.gradle.api.artifacts.ConfigurablePublishArtifact
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.MavenPom
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.Exec
import org.gradle.jvm.tasks.Jar
import org.gradle.plugins.signing.SigningExtension

plugins.apply("signing")

val skikoRelease = SkikoWinuiRelease.load(rootProject)
val skikoVersion = providers.provider { SkikoProperties(rootProject).deployVersion }
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
val winuiMingwEnabled = providers.gradleProperty("skiko.winui.mingw.enabled")
    .map(String::toBoolean)
    .orElse(true)

fun MavenPom.configureUpstreamIdentity() {
    properties.put("skiko.upstream.version", skikoRelease.upstreamVersion)
    properties.put("skiko.upstream.tag", skikoRelease.upstreamTag)
    properties.put("skiko.upstream.commit", skikoRelease.upstreamCommit)
    scm { tag.set(if (SkikoProperties(rootProject).isRelease) skikoRelease.tag else "HEAD") }
}

val verifySkikoWinuiRelease = tasks.register<Exec>("verifySkikoWinuiRelease") {
    group = "verification"
    description = "Checks that the pinned upstream Skiko commit is included in this release."
    workingDir(rootProject.file(".."))
    commandLine("git", "merge-base", "--is-ancestor", skikoRelease.upstreamCommit, "HEAD")
    isIgnoreExitValue = true
    doFirst {
        check(skikoVersion.get() == skikoRelease.version) {
            "Use -Pdeploy.release=true and the configured ${skikoRelease.version} release version."
        }
        logger.lifecycle("Checking upstream ${skikoRelease.upstreamTag} (${skikoRelease.upstreamCommit})")
    }
    doLast {
        check(executionResult.get().exitValue == 0) {
            "Merge pinned Skiko ${skikoRelease.upstreamTag} (${skikoRelease.upstreamCommit}) before releasing."
        }
    }
}

tasks.withType<PublishToMavenRepository>().configureEach {
    if (name.endsWith("ToMavenCentralRepository")) {
        doFirst {
            check(skikoVersion.get().endsWith("-SNAPSHOT")) {
                "MavenCentral is the SNAPSHOT repository. Use the release workflow to upload a Central Portal bundle."
            }
        }
    }
    if (name.endsWith("ToBuildRepoRepository") && SkikoProperties(rootProject).isRelease) {
        dependsOn(verifySkikoWinuiRelease)
    }
}

tasks.register<Jar>("skikoWinuiJavadocJar") {
    group = "build"
    description = "Builds an empty skiko-winui javadoc jar for Maven Central publication."
    archiveBaseName.set("skiko-winui")
    archiveClassifier.set("javadoc")
}

tasks.register<Jar>("skikoWinuiJvmJavadocJar") {
    group = "build"
    description = "Builds an empty skiko-winui-jvm javadoc jar for Maven Central publication."
    archiveBaseName.set("skiko-winui-jvm")
    archiveClassifier.set("javadoc")
}

tasks.register<Jar>("skikoWinuiMingwJavadocJar") {
    group = "build"
    description = "Builds an empty skiko-winui-mingw javadoc jar for Maven Central publication."
    archiveBaseName.set("skiko-winui-mingw")
    archiveClassifier.set("javadoc")
}

tasks.register<Jar>("skikoWinuiWindowsRuntimeSourcesJar") {
    group = "build"
    description = "Builds the skiko-winui Windows runtime sources jar for Maven publication."
    archiveBaseName.set("skiko-winui-windows")
    archiveClassifier.set("sources")
}

tasks.register<Jar>("skikoWinuiWindowsRuntimeJavadocJar") {
    group = "build"
    description = "Builds an empty skiko-winui Windows runtime javadoc jar for Maven Central publication."
    archiveBaseName.set("skiko-winui-windows")
    archiveClassifier.set("javadoc")
}

tasks.register<Jar>("skikoWinuiMingwRuntimeSourcesJar") {
    group = "build"
    description = "Builds the skiko-winui mingw runtime sources jar for Maven publication."
    archiveBaseName.set("skiko-winui-mingw-runtime")
    archiveClassifier.set("sources")
}

tasks.register<Jar>("skikoWinuiMingwRuntimeJavadocJar") {
    group = "build"
    description = "Builds an empty skiko-winui mingw runtime javadoc jar for Maven Central publication."
    archiveBaseName.set("skiko-winui-mingw-runtime")
    archiveClassifier.set("javadoc")
}

// The toolkit plugin attaches one AppX resources zip per target to the root publication without
// a classifier, which Maven rejects as duplicate artifacts once there is more than one target.
configurations.matching { it.name.startsWith("kotlinAppxResourcesElements") }.configureEach {
    val sourceSetName = name.removePrefix("kotlinAppxResourcesElements").replaceFirstChar(Char::lowercaseChar)
    if (sourceSetName.isNotEmpty()) {
        outgoing.artifacts.configureEach {
            (this as ConfigurablePublishArtifact).classifier = "$sourceSetName-appx-resources"
        }
    }
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
        fun MavenPublication.configureSkikoWinuiPom(
            displayName: String,
            displayDescription: String,
            javadocJarTaskName: String,
        ) {
            groupId = skikoWinuiGroup.get()
            version = skikoVersion.get()
            artifact(tasks.named(javadocJarTaskName))
            pom {
                configureUpstreamIdentity()
                name.set(displayName)
                description.set(displayDescription)
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

        named("kotlinMultiplatform", MavenPublication::class.java) {
            configureSkikoWinuiPom(
                displayName = "Skiko WinUI",
                displayDescription = "AWT-free Skiko WinUI backend",
                javadocJarTaskName = "skikoWinuiJavadocJar",
            )
            artifactId = "skiko-winui"
        }
        named("winuiJvm", MavenPublication::class.java) {
            configureSkikoWinuiPom(
                displayName = "Skiko WinUI JVM",
                displayDescription = "AWT-free Skiko WinUI JVM backend",
                javadocJarTaskName = "skikoWinuiJvmJavadocJar",
            )
            artifactId = "skiko-winui-jvm"
            pom.withXml {
                val root = asNode()
                val dependencies = (root.get("dependencies") as groovy.util.NodeList)
                    .filterIsInstance<groovy.util.Node>()
                    .firstOrNull()
                    ?: root.appendNode("dependencies")
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
                configureUpstreamIdentity()
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
        create("skikoWinuiMingwRuntime", MavenPublication::class.java) {
            groupId = skikoWinuiGroup.get()
            artifactId = "skiko-winui-mingw-runtime"
            version = skikoVersion.get()
            artifact(tasks.named<Jar>("skikoWinuiMingwRuntimeJar"))
            artifact(tasks.named("skikoWinuiMingwRuntimeSourcesJar"))
            artifact(tasks.named("skikoWinuiMingwRuntimeJavadocJar"))
            pom {
                configureUpstreamIdentity()
                name.set("Skiko WinUI MinGW Runtime")
                description.set("Kotlin/Native mingwX64 native runtime for the AWT-free Skiko WinUI backend")
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
        if (winuiMingwEnabled.get()) {
            named("winuiMingw", MavenPublication::class.java) {
                configureSkikoWinuiPom(
                    displayName = "Skiko WinUI MinGW",
                    displayDescription = "AWT-free Skiko WinUI Kotlin/Native mingwX64 backend",
                    javadocJarTaskName = "skikoWinuiMingwJavadocJar",
                )
                artifactId = "skiko-winui-mingw"
                pom.withXml {
                    val root = asNode()
                    val dependencies = (root.get("dependencies") as groovy.util.NodeList)
                        .filterIsInstance<groovy.util.Node>()
                        .firstOrNull()
                        ?: root.appendNode("dependencies")
                    dependencies.appendNode("dependency").apply {
                        appendNode("groupId", skikoWinuiGroup.get())
                        appendNode("artifactId", "skiko-winui-mingw-runtime")
                        appendNode("version", skikoVersion.get())
                        appendNode("scope", "runtime")
                    }
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
    description = "Publishes the current skiko-winui JVM, mingw, and Windows runtime artifacts to Maven Local."
    val publicationTasks = mutableListOf(
        "publishKotlinMultiplatformPublicationToMavenLocal",
        "publishWinuiJvmPublicationToMavenLocal",
        "publishSkikoWinuiWindowsRuntimePublicationToMavenLocal",
    )
    if (winuiMingwEnabled.get()) {
        publicationTasks += "publishWinuiMingwPublicationToMavenLocal"
        publicationTasks += "publishSkikoWinuiMingwRuntimePublicationToMavenLocal"
    }
    dependsOn(publicationTasks)
}

tasks.register("publishSkikoWinuiToBuildRepo") {
    group = "publishing"
    description = "Publishes the current skiko-winui JVM, mingw, and Windows runtime artifacts to the local BuildRepo."
    val publicationTasks = mutableListOf(
        "publishKotlinMultiplatformPublicationToBuildRepoRepository",
        "publishWinuiJvmPublicationToBuildRepoRepository",
        "publishSkikoWinuiWindowsRuntimePublicationToBuildRepoRepository",
    )
    if (winuiMingwEnabled.get()) {
        publicationTasks += "publishWinuiMingwPublicationToBuildRepoRepository"
        publicationTasks += "publishSkikoWinuiMingwRuntimePublicationToBuildRepoRepository"
    }
    dependsOn(publicationTasks)
}

tasks.register("publishSkikoWinuiToMavenCentral") {
    group = "publishing"
    description = "Publishes skiko-winui SNAPSHOT artifacts to the Maven Central snapshots repository."
    val publicationTasks = mutableListOf(
        "publishKotlinMultiplatformPublicationToMavenCentralRepository",
        "publishWinuiJvmPublicationToMavenCentralRepository",
        "publishSkikoWinuiWindowsRuntimePublicationToMavenCentralRepository",
    )
    if (winuiMingwEnabled.get()) {
        publicationTasks += "publishWinuiMingwPublicationToMavenCentralRepository"
        publicationTasks += "publishSkikoWinuiMingwRuntimePublicationToMavenCentralRepository"
    }
    dependsOn(publicationTasks)
}
