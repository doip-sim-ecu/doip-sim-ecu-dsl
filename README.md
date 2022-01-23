# DoIP Simulation ECU DSL

This repository contains a kotlin based domain specific language (dsl) to quickly and intuitively write
custom DoIP ECU simulations.

Its main purpose is to enable programmers to be able to quickly write integration test for 
their DoIP clients, and therefore to allow better testing of error- and edge cases, as well
as creating regression tests.   

To see a fairly comprehensive example with explanations, look at the 
example project [doip-sim-ecu-dsl-example](https://github.com/doip-sim-ecu/doip-sim-ecu-dsl-example).

This project utilizes the [doip-simulation](https://github.com/doip/doip-simulation) from the doip project for 
the gateway/ecu functionality and basically adds a DSL around that.

