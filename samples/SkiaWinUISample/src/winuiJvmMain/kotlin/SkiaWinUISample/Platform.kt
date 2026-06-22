package SkiaWinUISample

actual fun sampleRuntimeDescription(): String =
    "JRE: ${System.getProperty("java.vendor")}, ${System.getProperty("java.runtime.version")}"

actual fun isSampleAutoExitRequested(): Boolean =
    System.getProperty("skiko.winui.sample.autoExit") == "true" ||
        System.getenv("SKIKO_WINUI_SAMPLE_AUTO_EXIT") == "true"
