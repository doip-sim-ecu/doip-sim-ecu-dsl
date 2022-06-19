import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.tooling.BuildException
import java.time.Duration
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import kotlin.concurrent.thread

open class CloseAndReleaseNexusArtifact @Inject constructor(
    private val extension: NexusReleaseExtension
) : DefaultTask() {
    private val client: NexusClient
    init {
        group = "publishing"
        client = NexusClient(logger, extension)
    }

    @TaskAction
    open fun closeAndRelease() {
        val repos = client.list()

        if (repos.isEmpty()) {
            throw Exception("No repositories are open. Nothing to do")
        }

        val threads = mutableListOf<Thread>()
        repos.forEach { repo ->
            val t = thread(start = true) {
                closeAndPromoteRepository(repo.repositoryId)
            }
            threads.add(t)
        }
        threads.forEach { it.join() }
    }

    private fun closeAndPromoteRepository(repositoryId: String) {
        try {
            var repo = waitWhileTransitioning(
                interval = extension.pollingInterval.get(),
                timeout = extension.maxWaitForTransitionChange.get(),
                repositoryId = repositoryId
            )

            if (repo.type == "open") {
                client.closeRepo(repositoryId)
                Thread.sleep(1000)
                repo = waitWhileTransitioning(
                    interval = extension.pollingInterval.get(),
                    timeout = extension.maxWaitForTransitionChange.get(),
                    repositoryId = repositoryId
                )
                if (repo.type != "closed") {
                    throw Exception("Repository $repositoryId couldn't be closed due to errors")
                }
            }

            if (repo.type == "closed") {
                client.promoteRepo(repositoryId)
            }
        } catch (e: HttpException) {
            throw BuildException("Error while closing/promoting (returned: ${e.body}", e)
        } catch (e: Exception) {
            throw BuildException("Error while closing/promoting", e)
        }
    }

    private fun waitWhileTransitioning(
        interval: Duration,
        timeout: Duration,
        repositoryId: String
    ): RepositoryData {
        val start = System.nanoTime()
        val end = start + timeout.toNanos()
        while (System.nanoTime() < end) {
            val state = client.getRepositoryData(repositoryId)
            if (!state.transitioning) {
                return state
            }
            logger.info("Waiting for $repositoryId while transitioning")
            Thread.sleep(interval.toMillis())
        }
        throw TimeoutException("Timeout while waiting for repository transition")
    }

    private fun repeatUntilActivityStatusIn(
        interval: Duration,
        timeout: Duration,
        repositoryId: String,
        vararg status: RepositoryStatus
    ): RepositoryStatus {
        val start = System.nanoTime()
        val end = start + timeout.toNanos()
        while (System.nanoTime() < end) {
            val state = client.getStatusByActivity(repositoryId)
            if (state.isIn(*status)) {
                return state
            }
            logger.info("Waiting for $repositoryId ($state) to transition into ${status.joinToString(", ")}")
            Thread.sleep(interval.toMillis())
        }
        throw TimeoutException("Timeout while waiting for state to change to any of ${status.joinToString(", ")}")
    }

}


