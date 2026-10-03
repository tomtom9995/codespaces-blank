# Build-Kontext ist das Repository-Wurzelverzeichnis (Backend braucht cms/content als eingebaute Texte).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY backend/gradlew backend/settings.gradle.kts backend/build.gradle.kts backend/
COPY backend/gradle backend/gradle
RUN cd backend && ./gradlew --no-daemon -q dependencies > /dev/null
COPY backend/src backend/src
COPY cms/content cms/content
RUN cd backend && ./gradlew --no-daemon -q buildFatJar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 nova
USER nova
WORKDIR /app
COPY --from=build /src/backend/build/libs/nova-backend.jar /app/nova-backend.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/nova-backend.jar"]
