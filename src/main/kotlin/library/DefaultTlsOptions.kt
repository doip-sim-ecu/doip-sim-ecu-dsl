package library

val DefaultTlsCipherSuitesTlsV1_2 =
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

val DefaultTlsCipherSuitesTlsV1_3 =
    listOf(
        "TLS_AES_128_GCM_SHA256",
        "TLS_AES_128_GCM_SHA384",
        "TLS_CHACHA20_POLY_1305_SHA256",
        "TLS_AES_128_CCM_SHA256",
        "TLS_AES_128_CCM_8_SHA256",
    )

val DefaultTlsCiphers = DefaultTlsCipherSuitesTlsV1_2 + DefaultTlsCipherSuitesTlsV1_3

val DefaultTlsProtocols =
    listOf(
        "TLSv1.2",
        "TLSv1.3",
    )
