plugins {
    `java-library`
    `maven-publish`
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

val awtJar = rootProject.tasks.named<Jar>("awtJar")
val skikoJvmApiClassesDir = layout.buildDirectory.dir("classes/java/main")
val filteredSkikoJvmApiClasses by tasks.registering(Sync::class) {
    group = "build"
    description = "Extracts AWT-free Skiko JVM API classes for non-AWT JVM backends."
    dependsOn(awtJar)
    from({ zipTree(awtJar.get().archiveFile.get().asFile) }) {
        include("META-INF/skiko.kotlin_module")
        include("org/jetbrains/skia/**")
        include("org/jetbrains/skiko/**")
        exclude("org/jetbrains/skiko/*AWT*.class")
        exclude("org/jetbrains/skiko/Actuals_awtKt*.class")
        exclude("org/jetbrains/skiko/ClipComponent*.class")
        exclude("org/jetbrains/skiko/ClipRectangle*.class")
        exclude("org/jetbrains/skiko/DisplayKt*.class")
        exclude("org/jetbrains/skiko/DrawingSurface*.class")
        exclude("org/jetbrains/skiko/FullscreenAdapter*.class")
        exclude("org/jetbrains/skiko/HardwareLayer*.class")
        exclude("org/jetbrains/skiko/LinuxDrawingSurface*.class")
        exclude("org/jetbrains/skiko/MainUIDispatcher_awtKt*.class")
        exclude("org/jetbrains/skiko/SkiaLayer*.class")
        exclude("org/jetbrains/skiko/SwingDispatcher*.class")
        exclude("org/jetbrains/skiko/SystemTheme_awtKt*.class")
        exclude("org/jetbrains/skiko/redrawer/**")
        exclude("org/jetbrains/skiko/swing/**")
    }
    into(skikoJvmApiClassesDir)
}

tasks.named("classes") {
    dependsOn(filteredSkikoJvmApiClasses)
}

tasks.named<Jar>("jar") {
    group = "build"
    description = "Builds an AWT-free Skiko JVM API jar for non-AWT JVM backends."
    archiveBaseName.set("skiko-jvm-api")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(filteredSkikoJvmApiClasses)
    from(skikoJvmApiClassesDir)
}

publishing {
    publications {
        create<MavenPublication>("jvmApi") {
            from(components["java"])
            artifactId = "skiko-jvm-api"
            pom {
                name.set("Skiko JVM API")
                description.set("AWT-free Skiko JVM API classes for non-AWT JVM backends")
            }
        }
    }
}
