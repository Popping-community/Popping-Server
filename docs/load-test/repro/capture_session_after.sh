#!/bin/bash
# Same failure injection as before, now that sessions live in Redis.
#
# Which instance to stop no longer needs looking up: without the sticky cookie
# the session is not tied to one, which is the whole point of the change. The
# before-version had to read SERVERID to avoid killing the wrong container.
set -u
cd "$(dirname "$0")" || exit 1
rm -f cookies-after.txt

run() { printf '$ %s\n' "$1"; eval "$1"; printf '\n'; }

run "curl -si -c cookies-after.txt -X POST -d 'username=user86&password=password123' \\
    http://127.0.0.1:9091/login | grep -iE '^HTTP/|^set-cookie:'"

run "grep -E 'SESSION|SERVERID' cookies-after.txt | awk '{print \$6, substr(\$7,1,24)}'"

# The cookie carries the session id base64-encoded. Decode it and ask Redis for
# that exact key - scanning for any key would prove only that some session exists.
run "SID=\$(grep SESSION cookies-after.txt | awk '{print \$7}' | base64 -d) && \
    echo \"session id: \$SID\" && \
    docker exec popping-redis redis-cli EXISTS \"spring:session:sessions:\$SID\""

run "curl -si -b cookies-after.txt http://127.0.0.1:9091/boards/new | head -1"

run "docker stop popping-app-1"
sleep 20

printf '# same cookie, the instance that served the login is gone\n'
for i in 1 2 3; do
  run "curl -si -b cookies-after.txt http://127.0.0.1:9091/boards/new | head -1"
done

run "docker start popping-app-1"
