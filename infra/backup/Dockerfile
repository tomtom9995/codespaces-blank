# Backup-Container: restic + Datenbank-Werkzeuge + Zeitplan (crond)
FROM alpine:3.22
# Nur für Build-Umgebungen hinter TLS-Proxys: APK_HTTP=1 nutzt http-Spiegel (Pakete sind trotzdem signaturgeprüft).
ARG APK_HTTP=0
RUN if [ "$APK_HTTP" = "1" ]; then sed -i "s#https://#http://#" /etc/apk/repositories; fi
RUN apk add --no-cache restic postgresql17-client mariadb-client docker-cli bash curl jq tar zstd age tzdata ca-certificates coreutils findutils
ENV TZ=Europe/Berlin
COPY backup.sh restore-test.sh maintenance.sh offsite-copy.sh archive.sh entrypoint.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/*.sh
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
