TODOs:

- Nothing at the moment

Maybe someday:
- Converter to convert odx-d and/or cdd into typed concrete 
  request/response objects.
  e.g. a request/response for a read/write could look like this:
  ```kotlin
  request(VINDataIdentifier_Read) { 
    respond(
      VINDataIdentifier(vin = "MYVIN...")
    ) 
  }

  request(VINDataIdentifier_Write) {
    vin = request.vin 
    ack()
  }
  ```
- Scope would be to create the files for a simulated ecu (in concrete projects)
  using automatically generated extension functions 
