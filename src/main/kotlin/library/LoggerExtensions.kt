package library

import org.slf4j.Logger

fun Logger.traceIf(t: Throwable? = null, supplier: () -> String) {
    if (this.isTraceEnabled) {
        this.trace(supplier.invoke(), t)
    }
}

fun Logger.debugIf(t: Throwable? = null, supplier: () -> String) {
    if (this.isDebugEnabled) {
        this.debug(supplier.invoke(), t)
    }
}

fun Logger.infoIf(t: Throwable? = null, supplier: () -> String) {
    if (this.isInfoEnabled) {
        this.info(supplier.invoke(), t)
    }
}

fun Logger.errorIf(t: Throwable? = null, supplier: () -> String) {
    if (this.isErrorEnabled) {
        this.error(supplier.invoke(), t)
    }
}

