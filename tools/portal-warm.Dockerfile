FROM muse-portal-builder:2026-10-11
USER root
RUN mkdir -p /opt/portal-gradle-cache && chown gradle:gradle /opt/portal-gradle-cache
COPY --chown=gradle:gradle app /seed/app
ENV GRADLE_USER_HOME=/opt/portal-gradle-cache
USER gradle
RUN gradle -p /seed/app :protocol:test :app:assembleDebug --no-daemon --console=plain
USER root
RUN rm -rf /seed
USER gradle
WORKDIR /workspace
