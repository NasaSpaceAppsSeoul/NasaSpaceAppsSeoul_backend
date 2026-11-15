FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY dist/catalog-service.jar  catalog.jar
COPY dist/insight-service.jar  insight.jar
COPY deploy/fly/catalog-insight/start.sh start.sh
RUN chmod +x start.sh
EXPOSE 8081 8084
ENTRYPOINT ["/bin/bash", "/app/start.sh"]
