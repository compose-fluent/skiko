import org.gradle.api.tasks.JavaExec
import org.gradle.jvm.tasks.Jar

fun JavaExec.configureWinuiJvmSmokeClasspath() {
    val windowsRuntimeJar = tasks.named<Jar>("skikoWinuiWindowsRuntimeJar")
    mainClass.set("org.jetbrains.skiko.winui.WinUISkiaLayerSmoke")
    classpath = files(
        layout.buildDirectory.dir("classes/kotlin/winuiJvm/test"),
        layout.buildDirectory.dir("processedResources/winuiJvm/test"),
        layout.buildDirectory.dir("classes/kotlin/winuiJvm/main"),
        layout.buildDirectory.dir("processedResources/winuiJvm/main"),
        windowsRuntimeJar.flatMap { it.archiveFile },
    ) + configurations.named("winuiJvmTestRuntimeClasspath").get()
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "-Xmx256m",
        "-XX:MaxMetaspaceSize=256m",
        "-XX:HeapBaseMinAddress=32g",
        "-XX:ReservedCodeCacheSize=64m",
        "-XX:CICompilerCount=2",
    )
    systemProperty(
        "kotlin.winrt.runtimeAssetsRoot",
        layout.buildDirectory.dir("kotlin-winrt/application-package").get().asFile.absolutePath,
    )
}

fun JavaExec.dependsOnWinuiJvmSmokeInputs() {
    dependsOn(
        "compileTestKotlinWinuiJvm",
        "winuiJvmProcessResources",
        "winuiJvmTestProcessResources",
        "stageWinRtApplicationPackage",
        tasks.named("skikoWinuiWindowsRuntimeJar"),
    )
}

val isWindowsHost = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

tasks.register<JavaExec>("runWinuiJvmSmoke") {
    group = "verification"
    description = "Runs the WinUI JVM smoke that hosts WinUISkiaLayer in a WinUI Window."
    onlyIf { isWindowsHost }
    dependsOnWinuiJvmSmokeInputs()
    configureWinuiJvmSmokeClasspath()
    providers.gradleProperty("skiko.winui.smokeArgs").orNull
        ?.split(Regex("[, ]+"))
        ?.filter { it.isNotBlank() }
        ?.let(::args)
}

tasks.register<JavaExec>("runWinuiJvmFocusSmoke") {
    group = "verification"
    description = "Runs the WinUI JVM smoke that verifies dispatcher-delayed focus acquisition."
    onlyIf { isWindowsHost }
    dependsOnWinuiJvmSmokeInputs()
    configureWinuiJvmSmokeClasspath()
    args("--verify-focus-after-dispatcher")
}
