package helper

import java.util.*

class EcuTimerTask(private val action: TimerTask.() -> Unit) : TimerTask() {
    private var _canBeRemoved = false

    override fun run() {
        action()
        _canBeRemoved = true
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
