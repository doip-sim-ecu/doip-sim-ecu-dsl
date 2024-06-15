import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import java.net.URI
import java.time.Duration


open class NexusReleaseExtension(project: Project) {
    val connectTimeout: Property<Duration> = project.objects.property(Duration::class.java).apply {
        set(Duration.ofSeconds(60))
    }

    val nexusUrl: Property<URI> = project.objects.property(URI::class.java).apply {
        set(URI("https://s01.oss.sonatype.org"))
    }

    val stagingId: Property<String> = project.objects.property(String::class.java)
    val stagingUserName: Property<String> = project.objects.property(String::class.java)
    val username: Property<String> = project.objects.property(String::class.java)
    val password: Property<String> = project.objects.property(String::class.java)

    val pollingInterval: Property<Duration> = project.objects.property(Duration::class.java).apply {
        set(Duration.ofSeconds(30))
    }
    val maxWaitForTransitionChange: Property<Duration> = project.objects.property(Duration::class.java).apply {
        set(Duration.ofMinutes(150))
    }
}

class NexusReleasePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply(MavenPublishPlugin::class.java)

        val extension = project.extensions.create("nexusRelease", NexusReleaseExtension::class.java, project)

        project.tasks.register(
            "closeAndReleaseNexusArtifact",
            CloseAndReleaseNexusArtifact::class.java,
            extension
        )
    }
}

