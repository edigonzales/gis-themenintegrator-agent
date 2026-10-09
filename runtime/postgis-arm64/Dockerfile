# Native counterpart of docker-postgis 18-3.6, based on upstream commit
# 2bcd236e3af9ec6e668db51eb37162a79f0eaeaa. See THIRD-PARTY-NOTICES.
FROM postgres:18-trixie@sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336

LABEL org.opencontainers.image.description="Local native PostgreSQL 18 / PostGIS 3.6 for GIS development" \
      org.opencontainers.image.source="https://github.com/postgis/docker-postgis" \
      org.opencontainers.image.revision="2bcd236e3af9ec6e668db51eb37162a79f0eaeaa"

ENV POSTGIS_MAJOR=3
ENV POSTGIS_VERSION=3.6.4+dfsg-2.pgdg13+1
RUN test "$(dpkg --print-architecture)" = arm64 \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        postgresql-${PG_MAJOR}-postgis-${POSTGIS_MAJOR}=${POSTGIS_VERSION} \
        postgresql-${PG_MAJOR}-postgis-${POSTGIS_MAJOR}-scripts=${POSTGIS_VERSION} \
    && rm -rf /var/lib/apt/lists/*

COPY initdb-postgis.sh /docker-entrypoint-initdb.d/10_postgis.sh
COPY update-postgis.sh /usr/local/bin/update-postgis.sh
# Entrypoint sources this script to supply its psql array; do not execute it separately.
RUN chmod 644 /docker-entrypoint-initdb.d/10_postgis.sh \
    && chmod 755 /usr/local/bin/update-postgis.sh
