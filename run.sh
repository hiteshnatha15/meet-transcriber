#!/usr/bin/env bash
# Run the Meet Transcriber app. Must be run from the project root (where pom.xml is).
set -e
cd "$(dirname "$0")"
if [ ! -f pom.xml ]; then
  echo "Error: pom.xml not found. Run this script from the project root (meet-transcriber/)."
  exit 1
fi
mvn spring-boot:run "$@"
