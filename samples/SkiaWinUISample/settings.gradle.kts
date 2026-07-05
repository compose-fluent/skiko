rootProject.name = "SkiaWinUISample"

if (providers.gradleProperty("skiko.winui.useLocalProject").map(String::toBoolean).getOrElse(false)) {
    includeBuild("../../skiko") {
        dependencySubstitution {
            substitute(module("io.github.compose-fluent:skiko-winui")).using(project(":"))
            substitute(module("io.github.compose-fluent:skiko-winui-mingw")).using(project(":"))
        }
    }
}
