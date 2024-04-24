package library

import SimEcu
import kotlin.time.Duration

public abstract class DoipEntityHandledException(message: String) : RuntimeException(message)

public class DoipEntityHardResetException(
    public val ecu: SimEcu,
    public val duration: Duration, message: String
) : DoipEntityHandledException(message)

public class DisableServerSocketException(
    public val duration: Duration
) : DoipEntityHandledException("Disabling server socket for ${duration.inWholeMilliseconds} ms")
