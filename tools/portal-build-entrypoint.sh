#!/bin/sh
set -eu
# Optional, reviewed public model baked into the builder. Never download at build time,
# and never mount the owner's home, credentials, microphone, Docker socket or ADB.
if [ -d /opt/portal-wake-model ]; then
    mkdir -p /workspace/app/app/src/main/assets/wake-vosk
    cp -a /opt/portal-wake-model/. /workspace/app/app/src/main/assets/wake-vosk/
fi
exec "$@"
