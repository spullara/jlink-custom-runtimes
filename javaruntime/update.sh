#!/bin/sh
mvn clean install; rm runtime.zip; zip -u runtime.zip bootstrap runtimehandler.jar ; aws lambda update-function-code --function-name java11-test-function --zip-file fileb://runtime.zip
