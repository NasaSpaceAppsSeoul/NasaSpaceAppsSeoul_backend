#!/bin/bash
set -e

JVM_OPTS="-Xmx90m -Xms16m -XX:+UseSerialGC \
  -XX:MaxMetaspaceSize=60m -XX:ReservedCodeCacheSize=24m \
  -Xss256k -XX:+TieredCompilation -XX:TieredStopAtLevel=1 \
  -Dspring.main.lazy-initialization=true"

# catalog 먼저 기동
java $JVM_OPTS \
  -Dserver.port=8081 \
  -jar /app/catalog.jar &

# catalog 기동 완료 후 insight 시작 (동시 기동 시 OOM 방지)
sleep 25

java $JVM_OPTS \
  -Dserver.port=8084 \
  -jar /app/insight.jar &

# 하나라도 종료되면 컨테이너 중단
wait -n
