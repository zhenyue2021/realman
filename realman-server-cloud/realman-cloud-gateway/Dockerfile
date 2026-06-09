FROM registry.cn-hangzhou.aliyuncs.com/dockerhub_mirror/java:17-anolis

MAINTAINER jeecgos@163.com

RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime

RUN mkdir -p /realman-cloud-gateway

WORKDIR /realman-cloud-gateway

EXPOSE 9999

ADD ./target/realman-cloud-gateway-*.jar app.jar

CMD sleep 100;java -Dfile.encoding=utf-8 -Djava.security.egd=file:/dev/./urandom -jar app.jar
