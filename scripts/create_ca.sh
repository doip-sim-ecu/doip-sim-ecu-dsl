#!/bin/sh
rm ecu_ca.*
# generate rsa key for the CA
#openssl genpkey -algorithm ed25519 -out ecu_ca.key
# generate self-signed certificate for the ca
openssl req -nodes -new -newkey ed25519 -x509 -days 6000 -keyout ecu_ca.key -out ecu_ca.crt -subj "/CN=ECU-CA-Root"
# extract der
openssl x509 -in ecu_ca.crt -out ecu_ca.der -outform der
# show
openssl x509 -in ecu_ca.crt -noout -text
