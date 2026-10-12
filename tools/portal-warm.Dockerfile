FROM muse-portal-builder:2026-10-11
USER root
RUN mkdir -p /opt/portal-gradle-cache && chown gradle:gradle /opt/portal-gradle-cache
COPY --chown=gradle:gradle app /seed/app
COPY --chmod=755 tools/portal-build-entrypoint.sh /opt/portal-build-entrypoint
ENV GRADLE_USER_HOME=/opt/portal-gradle-cache
USER gradle
RUN gradle -p /seed/app :protocol:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon --console=plain
USER root
RUN if [ -d /seed/app/app/src/main/assets/wake-vosk ]; then cp -a /seed/app/app/src/main/assets/wake-vosk /opt/portal-wake-model; chmod -R a+rX /opt/portal-wake-model; fi
RUN rm -rf /seed
USER gradle
WORKDIR /workspace
ENTRYPOINT ["/opt/portal-build-entrypoint"]
