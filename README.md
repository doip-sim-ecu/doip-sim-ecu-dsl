[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.github.doip-sim-ecu/doip-sim-ecu-dsl/badge.svg)](https://central.sonatype.com/artifact/io.github.doip-sim-ecu/doip-sim-ecu-dsl)
[![JitPack](https://jitpack.io/v/doip-sim-ecu/doip-sim-ecu-dsl.svg)](https://jitpack.io/#doip-sim-ecu/doip-sim-ecu-dsl)

# DoIP Simulation ECU DSL

This repository contains a kotlin based domain specific language (dsl) to quickly and intuitively write
custom DoIP ECU simulations.

Its main purpose is to enable programmers to quickly write integration tests for DoIP clients, 
and therefore to allow better testing of error and edge cases, as well as creating 
regression tests.   

To see a fairly comprehensive example with explanations on how to use this library in a project, look at 
the example project [doip-sim-ecu-dsl-example](https://github.com/doip-sim-ecu/doip-sim-ecu-dsl-example).

There's another example of how a [DoIP ECU UDS simulation](https://github.com/doip-sim-ecu/doip-sim-uds-ecu-example) 
could be used in a test setup (e.g. with additional REST interface).  

### Requirements
The doip-library & DSL is written in Kotlin/JVM with the ktor-network library. 
 
An ecu-simulation that utilizes this library will therefore require usage of compatible versions 
of these dependencies.
 
### Usage

#### 1. Add the libraries to your dependencies 

For build.gradle.kts:
```
repositories {
    ...
    mavenCentral()
    ...
}

dependencies {
     ...
     implementation("io.github.doip-sim-ecu:doip-sim-ecu-dsl:<REPLACE_WITH_VERSION>")
     ...
}
```

For build.gradle:
```
repositories {
    ...
    mavenCentral()
    ...
}

dependencies {
    ...
    implementation 'io.github.doip-sim-ecu:doip-sim-ecu-dsl:<REPLACE_WITH_VERSION>'
    ...
}
```

#### 2. Define your DoIP-Entity, ECU topology and request-responses
See [MyCustomGateway.kt](https://github.com/doip-sim-ecu/doip-sim-ecu-dsl-example/blob/main/src/main/kotlin/MyCustomGateway.kt)
in the example project for a detailed description of the syntax & semantics.

Essentially:
```
gateway("NAME") {
    logicalAddress = 0x1010
    ...
    // Request-Handling for the Gateway 
    request("11 03") { ack() }
    ...
    ecu("ECU") {
        physicalAddress = 0x1011
        ...
        // Request-Handling for the (non-doip)-ECU behind the gateway  
        request("11 03") { ack() }
    } 
}
```

### 3. Start the simulation
Call `start()` in your main-method, which will register the DoIP-Entities on the configured 
network-interfaces and sent out Vehicle-Announcement-Messages. 

The simulation will now respond to UDP and TCP-Requests on their configured ports (Default: 13400), 
and handle incoming messages/requests.


### CAN / ISO-TP support

Besides DoIP, ECUs can also be simulated on a CAN bus, speaking UDS over ISO-TP
(ISO 15765-2). The ISO-TP layer (segmentation, reassembly and flow control, including
CAN FD escape encodings) is implemented in pure Kotlin on top of a raw CAN frame
transport, of which there are two:

| Transport | Use case | Platform |
|---|---|---|
| `socketCan("vcan0")` | SocketCAN network devices: real hardware, `vcan`, `vxcan` pairs (e.g. into a container) | Linux only, requires the optional `tel.schich:javacan-core` dependency |
| `socketcand(host, port, bus)` | CAN over TCP via a [socketcand](https://github.com/linux-can/socketcand) daemon (rawmode); also usable from Windows/macOS or containers without `NET_ADMIN` | any |

ECUs on a CAN bus reuse the same request-matcher DSL as DoIP ECUs, but are addressed
by a physical request/response CAN id pair (plus an optional functional id):

```kotlin
network {
    canBus("BUS1") {
        transport = socketCan("vcan0")
        // or: transport = socketcand(host = "172.22.1.5", bus = "can0")
        functionalRequestId = 0x7DF
        // fd = true; txDlc = 64    // CAN FD (SocketCAN only)

        ecu("ECU1") {
            requestId = 0x712
            responseId = 0x7A2
            isoTp { blockSize = 8; stMin = 0 }   // optional ISO-TP tuning

            request("22 F1 86") { respond("62 F1 86 01") }
            request("3E 00") { ack() }
        }
    }
}
start()
```

#### Dual-homed ECUs (DoIP + CAN)

An ECU that should be reachable via DoIP *and* CAN at the same time can be defined
once inside a `multiTransport` block. It is then a **single ECU instance**: stored
properties, timers, interceptors and the busy-state are shared across both
transports - like a real ECU with two network interfaces.

```kotlin
network {
    multiTransport(
        gateway("GW") { logicalAddress = 0x1010 },
        canBus("BUS1") { transport = socketCan("vcan0") },
    ) {
        ecu("ECU1") {
            logicalAddress = 0x1011               // DoIP addressing
            requestId = 0x712; responseId = 0x7A2 // CAN addressing
            request("22 F1 86") { respond("62 F1 86 01") }
        }
    }
}
```

`gateway`, `doipEntity` and `canBus` return their configuration objects, so they can
also be defined first and passed by reference. A `multiTransport` block accepts any
number of CAN buses but at most one DoIP entity.

If two *independent* ECUs (with separate state) should merely share their request
definitions, use a plain lambda instead:

```kotlin
val ecu1Requests: RequestsData.() -> Unit = {
    request("22 F1 86") { respond("62 F1 86 01 02 03") }
}
network {
    gateway("GW") { ecu("ECU1") { logicalAddress = 0x1011; ecu1Requests() } }
    canBus("BUS1") { ecu("ECU1") { requestId = 0x712; responseId = 0x7A2; ecu1Requests() } }
}
```

For SocketCAN, add the optional dependency (and the natives for your architecture)
to your own project:

```kotlin
implementation("tel.schich:javacan-core:3.5.2")
implementation("tel.schich:javacan-core:3.5.2:x86_64")   // natives; or javacan-core-arch-detect
```

A virtual CAN interface for local testing is set up with:

```bash
sudo modprobe vcan
sudo ip link add dev vcan0 type vcan
sudo ip link set up vcan0
# for CAN FD: sudo ip link set vcan0 mtu 72 (before setting it up)

# a vxcan tunnel pair (e.g. one end moved into a container/network namespace):
sudo ip link add vxcan0 type vxcan peer name vxcan1
```

The simulation can be cross-checked with [can-utils](https://github.com/linux-can/can-utils),
e.g. `candump vcan0` or `isotpsend -s 712 -d 7a2 vcan0`. Linux-only integration tests are
excluded by default and can be enabled with `gradlew test -PcanIntegrationTests`.

Limitations: CAN FD is not available via socketcand (the upstream protocol has no FD
frame message), `hardResetEntityFor` is a DoIP-entity concept and is ignored on CAN, and
sub-millisecond STmin values (0xF1-0xF9) are rounded up to 1 ms.


### Acknowledgements
In an earlier version this library utilized the [doip-simulation](https://github.com/doip/doip-simulation) project 
for the DoIP-stack. The stack has since been replaced by a custom kotlin implementation, 
whose concepts still bear some resemblance to its predecessor.    


### License

This software is licensed under the Apache-2.0 license.

See [LICENSE.txt](LICENSE.txt) for details.
