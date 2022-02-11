#!/bin/sh
# generate rsa key for the CA
openssl genrsa -out ecu_ca.key 2048
# generate self-signed certificate for the ca
openssl req -new -x509 -days 6000 -key ecu_ca.key -out ecu_ca.crt -config ecu_ca.conf
# extract der
openssl x509 -in ecu_ca.crt -out ecu_ca.der -outform der
# show
openssl x509 -in ecu_ca.crt -noout -text
