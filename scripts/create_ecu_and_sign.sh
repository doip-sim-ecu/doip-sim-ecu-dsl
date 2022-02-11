#!/bin/sh
if [ -z "$1" ]
then
  echo "No ecu name given"
  echo "Usage: $0 <ecu-name>"
  exit 1
fi

openssl req -nodes -newkey rsa:2048 -keyout "$1.key" -out "$1.csr"
openssl x509 -req -in "$1.csr" -days 2000 -CA ecu_ca.crt -CAkey ecu_ca.key -CAcreateserial -out "$1.crt"

#cat ecu_ca.crt "$1.crt" > "$1-fullchain.crt"

openssl x509 -in "$1.crt" -text -noout

openssl verify -CAfile ecu_ca.crt "$1.crt"


# to verify connection to the port
# openssl s_client -verifyCAfile ecu_ca.crt -connect <ip>:3496
