#!/usr/bin/env sh
# Self-executing JAR launcher prefix.
#
# This script is prepended to the Spring Boot fat jar at build time. Because:
#   - sh reads commands from the start and stops at `exec` (which never returns),
#   - the JVM scans the END of a jar for the central ZIP directory and ignores any prefix,
# the same file is BOTH a runnable shell script and a valid jar:
#
#   ./grafana-mcp.jar              ← runs the server via the JVM
#   java -jar grafana-mcp.jar      ← also works
#
# Env vars:
#   JAVA       Path to java executable (default: java on PATH)
#   JAVA_OPTS  Extra JVM flags appended before `-jar`
exec "${JAVA:-java}" \
  ${JAVA_OPTS:-} \
  -Xss512k \
  -XX:+UseSerialGC \
  -XX:TieredStopAtLevel=1 \
  -jar "$0" "$@"
