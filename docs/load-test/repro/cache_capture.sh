#!/bin/bash
# Cross-instance cache staleness, captured so the transcript is reproducible.
#
# Every command is printed and executed from the same string, so the reader can
# paste any line and get the same result.
#
# Requests go to the containers over the docker network rather than through
# HAProxy: routing through the proxy leaves which instance served a request
# uncontrolled, and an uncontrolled run looks exactly like a passing one.
set -u
NET=popping-server_popping-net
STAMP=$(date +%H%M%S)

run() { printf '$ %s\n' "$1"; eval "$1"; printf '\n'; }
count_cmd() {
  echo "docker run --rm --network $NET curlimages/curl -s \\
    http://$1:9091/boards/board-1/121/comments | grep -o '\"id\"' | wc -l"
}
find_cmd() {
  echo "docker run --rm --network $NET curlimages/curl -s \\
    http://$1:9091/boards/board-1/121/comments | grep -c 'xinst-$STAMP'"
}

printf '# warm both instances with the same post\n'
run "$(count_cmd popping-app-1)"
run "$(count_cmd popping-app-2)"

printf '# write one comment, on app-1 only\n'
run "docker run --rm --network $NET curlimages/curl -s \\
    -H 'Content-Type: application/json' \\
    -d '{\"content\":\"xinst-$STAMP\",\"guestNickname\":\"t\",\"guestPassword\":\"pw123456\"}' \\
    http://popping-app-1:9091/boards/board-1/121/comments/guest"

sleep 2
printf '# how many comments does each instance return now?\n'
run "$(count_cmd popping-app-1)"
run "$(count_cmd popping-app-2)"

printf '# and does the new comment appear in the body?\n'
run "$(find_cmd popping-app-1)"
run "$(find_cmd popping-app-2)"
