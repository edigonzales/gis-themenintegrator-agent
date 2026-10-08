FROM docker:29-cli@sha256:35cfcf893c20f2931f4909151b5407aaaeab711277de7ce8b8f521f095d54c32 AS docker-cli
FROM eclipse-temurin:25-jdk-noble@sha256:2feab631bffce6236d8bb5261a4abe19a8d6f85bad1c01166f74686c983d011f
RUN apt-get update && apt-get install -y --no-install-recommends git ca-certificates gh && rm -rf /var/lib/apt/lists/*
RUN git config --system --add safe.directory '*'
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=docker-cli /usr/local/libexec/docker/cli-plugins/docker-compose /usr/local/libexec/docker/cli-plugins/docker-compose
ENV GRADLE_USER_HOME=/cache/gradle
ENTRYPOINT []
