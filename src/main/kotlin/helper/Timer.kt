package helper

import org.slf4j.LoggerFactory
import java.util.*

private val logger = LoggerFactory.getLogger(EcuTimerTask::class.java)

internal class EcuTimerTask(private val action: TimerTask.() -> Unit) : TimerTask() {
    private var _canBeRemoved = false

    override fun run() {
        try {
            action()
        } catch (e: Exception) {
            logger.error("Error while executing timer: " + e.message, e)
        } finally {
            _canBeRemoved = true
        }
    }

    override fun cancel(): Boolean {
        try {
            return super.cancel()
        } finally {
            _canBeRemoved = true
        }
    }

    val canBeRemoved
        get() = _canBeRemoved
}

internal fun Timer.scheduleEcuTimerTask(delay: Long, action: TimerTask.() -> Unit): EcuTimerTask {
    val task = EcuTimerTask(action)
    schedule(task, delay)
    return task
}
