import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.gradle.api.logging.Logger
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

val ConfiguredJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

class NexusClient(private val logger: Logger, private val config: NexusReleaseExtension) {
    inline fun <reified T> get(path: String): T =
        ConfiguredJson.decodeFromString(get(path))

    fun closeRepo(repositoryId: String) {
        logger.info("Closing repository $repositoryId")
        post("/service/local/staging/bulk/close", CloseRepository(repositoryId))
    }

    fun promoteRepo(repositoryId: String) {
        logger.info("Promoting repository $repositoryId")
        post("/service/local/staging/bulk/promote", PromoteRepository(repositoryId))
    }

    fun getRepositoryData(repositoryId: String): RepositoryData =
        get<RepositoryData>("/service/local/staging/repository/$repositoryId")

    fun getStatusByActivity(repositoryId: String): RepositoryStatus {
        var state = RepositoryStatus.UNKNOWN

        val data = get<JsonElement>("/service/local/staging/repository/$repositoryId/activity")

        data.jsonArray.forEach { element ->
            val name = element.jsonObject["name"]?.jsonPrimitive?.content
            if (name == "close") {
                if (state.isIn(RepositoryStatus.UNKNOWN, RepositoryStatus.OPEN)) {
                    state = RepositoryStatus.CLOSE_IN_PROGRESS
                }

                val events = element.jsonObject["events"]?.jsonArray
                var hasRulesFailed = false
                var hasRulesSuccess = false
                events?.forEach { event ->
                    val eventName = event.jsonObject["name"]?.jsonPrimitive?.content
                    if (eventName == "rulesFailed") {
                        hasRulesFailed = true
                    } else if (eventName == "rulesSuccess") {
                        hasRulesSuccess = true
                    }
                }

                if (state.isIn(RepositoryStatus.UNKNOWN, RepositoryStatus.OPEN, RepositoryStatus.CLOSE_IN_PROGRESS)) {
                    if (hasRulesFailed) {
                        state = RepositoryStatus.CLOSE_WITH_ERRORS
                    } else if (hasRulesSuccess) {
                        state = RepositoryStatus.CLOSE_SUCCESSFUL
                    } else {
                        state = RepositoryStatus.CLOSE_IN_PROGRESS
                    }
                }
            } else if (name == "open") {
                if (state.isIn(RepositoryStatus.UNKNOWN)) {
                    state = RepositoryStatus.OPEN
                }
            }
        }
        return state
    }

    fun list(): List<RepositoryData> {
        val repositories = get<ProfileRepository>("/service/local/staging/profile_repositories")
        return repositories.data
            .filter {
                (config.stagingId.orNull == null && config.stagingUserName.get() == it.userId) ||
                        it.repositoryId == config.stagingId.orNull
            }
    }

    fun get(path: String): String {
        val connection = connection("GET", path)

        handleError(connection)
        val data = connection.inputStream.use {
            it.bufferedReader().readText()
        }
        return data
    }

    inline fun <reified T> post(path: String, body: T): String {
        val data = ConfiguredJson.encodeToString(body)
        println(data)
        return post(path, data)
    }

    fun post(path: String, body: String): String {
        val connection = connection("POST", path, body)
        handleError(connection)
        val data = connection.inputStream.use {
            it.bufferedReader().readText()
        }
        return data
    }

    private fun handleError(connection: HttpURLConnection) {
        if (connection.responseCode < 200 || connection.responseCode >= 300) {
            val errorData = connection.errorStream.use {
                it.bufferedReader().readText()
            }
            throw HttpException("Invalid status code ${connection.responseCode}", body = errorData)
        }
    }

    private fun connection(method: String, path: String, body: String? = null): HttpURLConnection {
        val connection = createUrl(path).openConnection() as HttpURLConnection
        connection.connectTimeout = config.connectTimeout.get().toMillis().toInt()
        connection.requestMethod = method
        connection.setRequestProperty("Authorization", bearerAuth)
        connection.setRequestProperty("Accept", "application/json")
        if (method != "GET") {
            connection.setRequestProperty("Content-Type", "application/json")
        }

        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { outputStream ->
                outputStream.write(body.encodeToByteArray())
                outputStream.flush()
            }
        }

        connection.connect()

        return connection
    }

    private fun createUrl(path: String): URL = with(config.nexusUrl.get()) {
        URL(this.scheme, this.authority, path)
    }

    private val bearerAuth: String
        get() =
            "Bearer " + "${config.username.get()}:${config.password.get()}".encodeToByteArray().encodeBase64()
}

class HttpException(
    msg: String,
    e: Throwable? = null,
    val body: String? = null,
) : RuntimeException(msg, e)


fun ByteArray.encodeBase64(): String =
    Base64.getEncoder().encodeToString(this)

@Serializable
data class ProfileRepository(
    var data: List<RepositoryData>
)

@Serializable
data class RepositoryData(
    var profileId: String,
    var profileName: String?,
    var profileType: String?,
    var repositoryId: String,
    var type: String?,
    var policy: String?,
    var userId: String?,
    var transitioning: Boolean,
)

@Serializable
data class CloseRepository(
    var data: CloseRepositoryData,
) {
    constructor(stagedRepositoryId: String) : this(
        data = CloseRepositoryData(
            stagedRepositoryIds = listOf(
                stagedRepositoryId
            ),
            description = "Close $stagedRepositoryId",
        )
    )
}

@Serializable
data class CloseRepositoryData(
    var stagedRepositoryIds: List<String>,
    var description: String = "Close ${stagedRepositoryIds.joinToString(", ")}",
)

@Serializable
data class PromoteRepository(
    var data: PromoteRepositoryData,
) {
    constructor (stagedRepositoryId: String) : this(
        data = PromoteRepositoryData(
            stagedRepositoryIds = listOf(
                stagedRepositoryId
            )
        )
    )
}

@Serializable
data class PromoteRepositoryData(
    var stagedRepositoryIds: List<String>,
    var autoDropAfterRelease: Boolean = true,
    var description: String = "Release ${stagedRepositoryIds.joinToString(", ")}"
)

enum class RepositoryStatus {
    UNKNOWN,
    OPEN,
    CLOSE_IN_PROGRESS,
    CLOSE_SUCCESSFUL,
    CLOSE_WITH_ERRORS;

    fun isIn(vararg status: RepositoryStatus): Boolean =
        status.any { it == this }
}
