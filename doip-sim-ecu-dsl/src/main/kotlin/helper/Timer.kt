package helper

import doip.library.util.Helper
import doip.logging.LogManager
import java.util.*

private val logger = LogManager.getLogger(EcuTimerTask::class.java)

class EcuTimerTask(private val action: TimerTask.() -> Unit) : TimerTask() {
    private var _canBeRemoved = false

    override fun run() {
        try {
            action()
        } catch (e: Exception) {
            logger.error("Error while executing timer: " + Helper.getExceptionAsString(e))
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

fun Timer.scheduleEcuTimerTask(delay: Long, action: TimerTask.() -> Unit): EcuTimerTask {
    val task = EcuTimerTask(action)
    schedule(task, delay)
    return task
}
