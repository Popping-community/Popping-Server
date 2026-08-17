#!/bin/bash
# Session failure injection, captured so the transcript is reproducible.
#
# Every command is printed and executed from the same string, so what the reader
# sees is exactly what ran - no tidied-up version that would not actually work.
#
# The instance to stop is read from the SERVERID cookie: HAProxy round-robins the
# first request, so hardcoding a container name kills the wrong one about half the
# time - and a run that killed the wrong instance still returns 200, which reads
# exactly like a passing test.
set -u
cd "$(dirname "$0")" || exit 1
rm -f cookies.txt

run() { printf '$ %s\n' "$1"; eval "$1"; printf '\n'; }

run "curl -si -c cookies.txt -X POST -d 'username=user86&password=password123' \\
    http://127.0.0.1:9091/login | grep -iE '^HTTP/|^location:|^set-cookie:'"

run "grep -E 'SERVERID|JSESSIONID' cookies.txt | awk '{print \$6, \$7}'"

run "curl -si -b cookies.txt http://127.0.0.1:9091/boards/new | head -1"

TARGET=$(grep -E "SERVERID" cookies.txt | awk '{print $7}')
CONTAINER="popping-${TARGET/app/app-}"
printf '# the session lives on %s, so stop that container\n' "$TARGET"
run "docker stop $CONTAINER"

sleep 8
run "curl -si -b cookies.txt http://127.0.0.1:9091/boards/new | grep -iE '^HTTP/|^location:'"
run "curl -si -b cookies.txt http://127.0.0.1:9091/boards/new | grep -iE '^HTTP/|^location:'"

run "docker start $CONTAINER"
