FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY dist/catalog-service.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", \
  "-Xmx180m", "-Xms32m", "-XX:+UseSerialGC", \
  "-XX:MaxMetaspaceSize=128m", "-XX:ReservedCodeCacheSize=32m", \
  "-Xss256k", "-Dspring.main.lazy-initialization=true", \
  "-jar", "/app/app.jar"]
