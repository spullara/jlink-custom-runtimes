#!/bin/sh
JAVE_HOME=~/Downloads/jdk-11.0.2-linux/ jlink     --add-modules java.desktop,java.net.http,java.sql,java.naming,java.management,java.instrument,java.security.jgss     --verbose     --strip-debug     --compress 2     --no-header-files     --no-man-pages     --output ./target/jlink-image-linux --module-path ~/Downloads/jdk-11.0.2-linux/jmods
