package helper

fun doip.logging.Logger.traceIf(supplier: () -> String) {
    if (this.isTraceEnabled) {
        this.trace(supplier.invoke())
    }
}

fun doip.logging.Logger.debugIf(supplier: () -> String) {
    if (this.isDebugEnabled) {
        this.debug(supplier.invoke())
    }
}
