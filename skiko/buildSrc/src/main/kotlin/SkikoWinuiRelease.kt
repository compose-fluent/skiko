import org.gradle.api.GradleException
import org.gradle.api.Project
import java.util.Properties

/** A release's upstream identity is committed alongside the fork's source. */
data class SkikoWinuiRelease(
    val version: String,
    val upstreamVersion: String,
    val upstreamTag: String,
    val upstreamCommit: String,
) {
    val tag: String get() = "skiko-winui/v$version"

    fun buildVersion(project: Project): String {
        val release = project.providers.gradleProperty("deploy.release").orNull.toBoolean()
        val override = project.providers.gradleProperty("skiko.version").orNull
        if (release && override != null && override != version) {
            throw GradleException("Release skiko.version must match skiko-winui-release.properties: $version")
        }
        return override ?: if (release) version else "$version-SNAPSHOT"
    }

    companion object {
        fun load(project: Project): SkikoWinuiRelease {
            val file = project.rootProject.file("../skiko-winui-release.properties")
            if (!file.isFile) throw GradleException("Missing skiko-winui release configuration: $file")
            val properties = Properties().apply { file.inputStream().use(::load) }
            fun required(key: String): String = properties.getProperty(key)?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: throw GradleException("Missing $key in $file")
            return SkikoWinuiRelease(
                required("release.version"),
                required("upstream.version"),
                required("upstream.tag"),
                required("upstream.commit"),
            ).also {
                if (!it.upstreamVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) ||
                    !it.version.matches(Regex("${Regex.escape(it.upstreamVersion)}(?:-(?:alpha|beta|rc)[0-9]{2})?")) ||
                    it.upstreamTag != "v${it.upstreamVersion}" ||
                    !it.upstreamCommit.matches(Regex("[0-9a-f]{40}"))) {
                    throw GradleException("Invalid version/tag/commit mapping in $file")
                }
            }
        }
    }
}
