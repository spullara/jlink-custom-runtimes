#!/bin/sh
jlink     --add-modules java.desktop,java.sql,java.naming,java.management,java.instrument,java.security.jgss     --verbose     --strip-debug     --compress 2     --no-header-files     --no-man-pages     --output ./target/jlink-image
