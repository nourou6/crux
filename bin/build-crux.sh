#!/bin/bash

# Runs the appropriate Maven build commands to produce a Java-executable JAR (i.e., 'java -jar crux.jar [OPTIONS]')
# then runs a second process to make the JAR file a directly OS-executable JAR (i.e., 'crux.jar [OPTIONS]')

if [ -e pom.xml ]; then
  echo "Building library..."
  mvn clean package assembly:single -Dmaven.install.skip=true
  status=$?
  if [ $status -eq 0 ]; then
    # make crux-1.0-all.jar executable
    if [ -e target/crux-*-all.jar ]; then
      bin/make-executable-jar.sh target/crux-*-all.jar
    fi
  fi
  exit $status #error code of mvn command
else
  echo "ERROR: must be run from the directory with pom.xml"
  exit 1;
fi
