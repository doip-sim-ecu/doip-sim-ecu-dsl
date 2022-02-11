package library

val TlsCipherSuitesTlsV1_2 =
    setOf(
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA384",
        "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_128_CCM",
        "TLS_ECDHE_ECDSA_WITH_AES_128_CCM_8",
        "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA384",
//        "TLS_ECDHE_ECDSA_WITH_NULL_SHA", -- only for development purposes
    )

val TlsCipherSuitesTlsV1_3 =
    setOf(
        "TLS_AES_128_GCM_SHA256",
        "TLS_AES_128_GCM_SHA384",
        "TLS_CHACHA20_POLY_1305_SHA256",
        "TLS_AES_128_CCM_SHA256",
        "TLS_AES_128_CCM_8_SHA256",
    )
