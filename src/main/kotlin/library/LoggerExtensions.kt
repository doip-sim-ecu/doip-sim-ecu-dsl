package library

import org.slf4j.Logger

fun Logger.traceIf(supplier: () -> String) {
    if (this.isTraceEnabled) {
        this.trace(supplier.invoke())
    }
}

fun Logger.debugIf(supplier: () -> String) {
    if (this.isDebugEnabled) {
        this.debug(supplier.invoke())
    }
}
