package library

import org.slf4j.Logger

public inline fun Logger.traceIf(t: Throwable? = null, supplier: () -> String) {
    if (this.isTraceEnabled) {
        this.trace(supplier.invoke(), t)
    }
}

public inline fun Logger.debugIf(t: Throwable? = null, supplier: () -> String) {
    if (this.isDebugEnabled) {
        this.debug(supplier.invoke(), t)
    }
}

public inline fun Logger.infoIf(t: Throwable? = null, supplier: () -> String) {
    if (this.isInfoEnabled) {
        this.info(supplier.invoke(), t)
    }
}

public inline fun Logger.errorIf(t: Throwable? = null, supplier: () -> String) {
    if (this.isErrorEnabled) {
        this.error(supplier.invoke(), t)
    }
}

