import helper.decodeHex
import java.net.InetAddress
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

fun myCustomGateway(gateway: CreateGatewayFunc) {
    gateway("GATEWAY") {
        // The logical address for your gateway
        logicalAddress = 0x1010
        functionalAddress = 0xcafe

        // Optional: Define the network interface the gateway should bind to
        // For multiple gateways, you could run a container with multiple ips, and bind each gateway to one of them
        localAddress = InetAddress.getByName("0.0.0.0")
        // Optional: Define the port the gateway should bind to
        localPort = 13400


        // VIN - will be padded to the right with 0 until 17 chars are reached, if left empty, 0xFF will be used
        vin = "MYVIN"
        // Define the entity id (defaults to 6 byte of 0x00), typically the MAC of an ECU
        eid = "101010101010".decodeHex()
        // Define the group id (defaults to 6 byte of 0x00), should be used to address a group of ecus when no vin is known
        gid = "909090909090".decodeHex()


        // You can now define how requests for the gateway should be handled

        // Generally speaking there are 2 types of request matching
        // 1. Exact matches - they are done by binary matching and aren't limited in length
        //    They are always done when there's no variable content in the "pattern"
        // 2. Regular expressions - incoming requests are converted into normalized hex-strings (uppercase,
        //    no whitespaces), which then are matched against a regular expression defined in the request argument.
        //
        // If a string (instead of a Regex) is given, it's first converted into a Regex by removing the spaces,
        // converting it to uppercase and replacing [] with .* to be matched against the normalized request string
        //
        // Matching order is always the order in which the requests are defined. This might be changed in the future.
        //
        // Since converting all incoming request bytes would be fairly expensive (esp. to simulate flashing),
        // the amount of converted bytes for matching with regular expressions is limited to the value set by
        // requestMatchBytes here (default 10).
        // Note: This means any regular expression containing more bytes than requestRegexMatchBytes will NEVER match
        requestRegexMatchBytes = 10

        // Now, let's see some Exact matches examples:
        // To start off, let's use a simple example, let's acknowledge the presence of a tester
        request("3E 00") { ack() }
        // This is semantically exactly the same as
        request("3E 00") { respond("7E 3E") }
        // As well as
        request(byteArrayOf(0x3E, 0x00)) { respond(byteArrayOf(0x7E, 0x3E)) }

        // We can also define a name for the request, which might be used in logging in the future
        request("3E 00", "TesterPresent") { ack() }

        // It's also possible to send nrc's
        request("3E 00") { nrc() }
        // Which is semantically the same as
        request("3e 00") { nrc(NrcError.GeneralReject) }
        // And again
        request("3E 00") { respond("7F 3E 10") }
        // As well as
        request(byteArrayOf(0x3E, 0x00)) { respond(byteArrayOf(0x7F, 0x3E, 0x10)) }

        // This means you could also programmatically open a csv-file instead of defining each request, and
        // read in a list of requests and responses, to transform them into request(..) { respond(...) } pairs.

        // But since csv-files are static, where's the fun in that?
        // Since everything in between {} is a lambda function that'll be executed when the request matches,
        // we can actually do useful things - as an example, let's maintain an ecu session state machine
        var ecuSession = SessionState.DEFAULT
        request("10 01") { ack(); ecuSession = SessionState.DEFAULT }
        request("10 02") { ack(); ecuSession = SessionState.PROGRAMMING }
        request("10 03") { ack(); ecuSession = SessionState.DIAGNOSTIC }
        request("10 04") { ack(); ecuSession = SessionState.SAFETY }

        // Now, let's say we can only HardReset if we're in the PROGRAMMING session
        request("11 01", "HardReset") {
            if (ecuSession == SessionState.PROGRAMMING) {
                ack()
                // ... but there's more - since an ecu can't respond if it's rebooting, let's simulate that
                addEcuInterceptor("WaitWhileRebooting", 4000.milliseconds) { req ->
                    // We could access the request and respond
                    if (req.message[0] == 0xFF.toByte() || // Multiple ways to access the request message
                        this.message[0] == 0xFF.toByte() ||
                        this.request.message[0] == 0xFF.toByte()) {
                        // This case won't ever happen, just to show what's possible in this context
                        ack()
                        // You could respond in the same ways as shown in the gateway examples
                    }

                    // When true is returned, all request matching is forfeited, and no matching will
                    // be executed. When all interceptors return false, the normal request matching will
                    // commence afterwards
                    true
                }
            } else {
                nrc(NrcError.SecurityAccessDenied)
            }
        }

        // State checking could also be done with an extension function that uses the state, now every conditioned
        // response can just use the extension function to save you time from writing plenty of redundant code
        fun RequestResponseData.respondIfProgramming(response: RequestResponseHandler) {
            if (ecuSession == SessionState.PROGRAMMING) {
                response.invoke(this)
            } else {
                nrc(NrcError.SecurityAccessDenied)
            }
        }
        // Let's do this for the soft reset
        request("11 03") { respondIfProgramming { ack() } }

        // And now for some regex examples
        //
        // we can also do generic matching with regular expressions, either by using a string
        // which will be converted into a (hopefully correct) regular expression, or by specifying a Regex
        // directly

        // The regular expression conversion will replace the characters [] with .*, convert the expression to uppercase
        // and remove all spaces

        // So this expression
        request("3e 00 []") { ack() }
        // is semantically the same as
        request(Regex("3E00.*")) { ack() }


        // To integration-test code that uses this simulated ecu, we could define a custom write-service with
        // a parameter, which could introduce specific error conditions through state, until reset by a
        // different parameter

        // Misc. commands:

        // You can also decide to not handle it in this handler and continue calling other matches by calling continueMatching()
        request("3E 00") { if (true) continueMatching() }

        // You can also set and replace timers -
        // e.g. to reset your session to default when no tester present was sent in 5 seconds
        request("3E 00") {
            ack()
            addOrReplaceEcuTimer("RESETSESSION", 5.seconds) {
                ecuSession = SessionState.DEFAULT
            }
        }

        // Remember the session state example earlier?
        // If we do it that way, we actually have a hard time resetting the ecu into a defined initial state.
        // To get around this, you can save state in a storage container associated with the ecu or request, which are
        // persistent across different requests (ecu), or within multiple consecutive requests (request)
        //
        // These storages are reset, when the reset() method is called on the ecu or request
        @Suppress("UNUSED_CHANGED_VALUE", "UNUSED_VALUE")
        request("10 02") {
            var sessionState by ecu.storedProperty { SessionState.DEFAULT } // {} contains the initial value that'll be used when the property is initialized
            if (sessionState != SessionState.PROGRAMMING) {
                sessionState = SessionState.PROGRAMMING
            }
            ack()
            // this session state is persisted for the ecu, so if you get it in another request, the last
            // set value will be the current one (until reset is called)

            // We can also do this on the request level by using caller instead of ecu
            var requestCounter: Int by caller.storedProperty { 0 }
            requestCounter++

            // to reset the storage for the request
//            caller.reset()

            // to reset the storage for the ecu (and all its requests)
            // this can be pretty nifty when you want to reset your state after each integration test
            // with a custom write command
//            ecu.reset()
        }


        // Since usually there are other ECUs behind a gateway, we can define them too

        // Either by directly adding them
        ecu("ECU1") {
            physicalAddress = 0x1111
            functionalAddress = 0xcafe
            // Optional - when no request is matched, automatically send out of range nrc (default true)
            nrcOnNoMatch = true

            // Same requests and logic as in gateway
            request("10 01") { ack() }
        }

        // Since stacking all the requests for multiple ecus inside a single file would get a bit too large,
        // we can also call functions  with the ecu-creator reference, to add them indirectly
        exampleEcu2(::ecu)
    }
}

fun exampleEcu2(ecu: CreateEcuFunc) {
    ecu("EXAMPLEECU2") {
        physicalAddress = 0x2211
        functionalAddress = 0xcafe

        request("10 01") { ack() }
    }
}

enum class SessionState {
    DEFAULT,
    PROGRAMMING,
    DIAGNOSTIC,
    SAFETY
}
