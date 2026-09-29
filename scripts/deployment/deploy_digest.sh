#!/bin/sh
# Image handoff only. This is NOT a readiness-gated rolling deployment.
set -eu

: "${POPPING_IMAGE:?Missing digest-pinned release image}"
: "${DOCKER_USERNAME:?Missing registry username}"
: "${DOCKER_PASSWORD:?Missing registry password}"
digest=${POPPING_IMAGE#chooh1010/popping-community@sha256:}
[ "$POPPING_IMAGE" != "$digest" ] && [ "${#digest}" -eq 64 ] || exit 2
case "$digest" in *[!0-9a-f]*) exit 2 ;; esac

# Preserve the existing remote working directory convention, but fail closed if
# its base Compose file does not already contain both expected application slots.
test -f docker-compose.yml
services=$(docker-compose -f docker-compose.yml config --services)
printf '%s\n' "$services" | grep -qx 'app-1'
printf '%s\n' "$services" | grep -qx 'app-2'

release_override=$(mktemp ./popping-release.XXXXXXXX.json)
# Keep this small non-secret file, including after partial failure: Compose
# records its path in container labels and later operations may need that path.
printf '{"services":{"app-1":{"image":"%s"},"app-2":{"image":"%s"}}}\n' \
    "$POPPING_IMAGE" "$POPPING_IMAGE" > "$release_override"

printf '%s' "$DOCKER_PASSWORD" | docker login --username "$DOCKER_USERNAME" --password-stdin
docker-compose -f docker-compose.yml -f "$release_override" pull app-1 app-2
expected_id=$(docker image inspect "$POPPING_IMAGE" --format '{{.Id}}')
test -n "$expected_id"
docker-compose -f docker-compose.yml -f "$release_override" up -d --no-deps app-1 app-2

for service in app-1 app-2; do
    cid=$(docker-compose -f docker-compose.yml -f "$release_override" ps -q "$service")
    test -n "$cid"
    test "$(docker inspect "$cid" --format '{{.Image}}')" = "$expected_id"
    test "$(docker inspect "$cid" --format '{{.Config.Image}}')" = "$POPPING_IMAGE"
    test "$(docker inspect "$cid" --format '{{.State.Running}}')" = true
done
printf 'Both application containers use %s; override=%s; business readiness is not asserted.\n' "$POPPING_IMAGE" "$release_override"
