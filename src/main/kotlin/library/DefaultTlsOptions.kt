package library

public val DefaultTlsCipherSuitesTlsV1_2: List<String> =
    listOf(
        "TLS_ECDHE_ECDSA_WITH_NULL_SHA", // for development/debugging purposes, it's by default disabled through the jdk's java.security
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA384",
        "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_128_CCM",
        "TLS_ECDHE_ECDSA_WITH_AES_128_CCM_8",
        "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA384",
    )

public val DefaultTlsCipherSuitesTlsV1_3: List<String> =
    listOf(
        "TLS_AES_128_GCM_SHA256",
        "TLS_AES_128_GCM_SHA384",
        "TLS_CHACHA20_POLY_1305_SHA256",
        "TLS_AES_128_CCM_SHA256",
        "TLS_AES_128_CCM_8_SHA256",
    )

public val DefaultTlsCiphers: List<String> = DefaultTlsCipherSuitesTlsV1_2 + DefaultTlsCipherSuitesTlsV1_3

public val DefaultTlsProtocols: List<String> =
    listOf(
        "TLSv1.2",
        "TLSv1.3",
    )
