FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="realman@realman-robot.com"

WORKDIR /app

ENV TZ=Asia/Shanghai
RUN apk add --no-cache tzdata && \
    cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone && \
    rm -rf /var/cache/apk/*

COPY target/realman-boot-iot-start-*.jar app.jar

ENV JAVA_OPTS="-XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=70.0 \
    -XX:InitialRAMPercentage=50.0 \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=200 \
    -Xlog:gc*:file=/app/logs/gc.log:time,uptime:filecount=5,filesize=20m \
    -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8085

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
