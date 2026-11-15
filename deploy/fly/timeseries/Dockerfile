FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY dist/timeseries-service.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", \
  "-Xmx200m", "-Xms16m", "-XX:+UseSerialGC", \
  "-XX:MaxMetaspaceSize=64m", "-XX:ReservedCodeCacheSize=24m", \
  "-Xss256k", "-Dspring.main.lazy-initialization=true", \
  "-jar", "/app/app.jar"]
