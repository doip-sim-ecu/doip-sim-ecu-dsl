[![](https://jitpack.io/v/doip-sim-ecu/doip-sim-ecu-dsl.svg)](https://jitpack.io/#doip-sim-ecu/doip-sim-ecu-dsl)

# DoIP Simulation ECU DSL

This repository contains a kotlin based domain specific language (dsl) to quickly and intuitively write
custom DoIP ECU simulations.

Its main purpose is to enable programmers to quickly write integration tests for DoIP clients, 
and therefore to allow better testing of error and edge cases, as well as creating 
regression tests.   

To see a fairly comprehensive example with explanations on how to use this library in a project,
look at the example project [doip-sim-ecu-dsl-example](https://github.com/doip-sim-ecu/doip-sim-ecu-dsl-example).

### Requirements
The doip-library & DSL is written in Kotlin/JVM 1.6.21 with the ktor-network 2.0.0 library. 
 
An ecu-simulation that utilizes this library will therefore require usage of compatible versions 
of these dependencies.
 
### Usage

#### 1. Add the libraries to your dependencies 

For build.gradle.kts:
```
repositories {
    ...
    maven("https://jitpack.io")
    ...
}

dependencies {
     ...
     implementation("com.github.doip-sim-ecu:doip-sim-ecu-dsl:<REPLACE_WITH_VERSION>")
     ...
}
```

For build.gradle:
```
repositories {
    ...
    maven { "https://jitpack.io" }
    ...
}

dependencies {
    ...
    implementation 'com.github.doip-sim-ecu:doip-sim-ecu-dsl:<REPLACE_WITH_VERSION>'
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


### Acknowledgements
In an earlier version this library utilized the [doip-simulation](https://github.com/doip/doip-simulation) project 
for the DoIP-stack. The stack has since been replaced by a custom kotlin implementation, 
whose concepts still bear some resemblance to its predecessor.    


### License

This software is licensed under the Apache-2.0 license.

See [LICENSE.txt](LICENSE.txt) for details.
