# DoIP Simulation ECU DSL

This repository contains a kotlin based domain specific language (dsl) to quickly and intuitively write
custom DoIP ECU simulations.

Its main purpose is to enable programmers to be able to quickly write integration test for their DoIP clients, and therefore to allow better testing of error- and edge cases, as well as creating regression tests.   

To see a fairly comprehensive example with explanations, look at the example code in [MyCustomGateway](doip-sim-ecu-dsl-example/src/main/kotlin/MyCustomGateway.kt)

It utilizes the [doip-simulation](https://github.com/doip/doip-simulation) from the doip project for the gateway/ecu functionality and basically adds a DSL around that.

