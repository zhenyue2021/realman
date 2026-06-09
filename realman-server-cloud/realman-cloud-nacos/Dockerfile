FROM registry.cn-hangzhou.aliyuncs.com/dockerhub_mirror/java:21-anolis

MAINTAINER jeecgos@163.com

RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime

RUN mkdir -p /realman-cloud-nacos

WORKDIR /realman-cloud-nacos

EXPOSE 8848

ADD ./target/realman-cloud-nacos-*.jar app.jar

CMD sleep 30;java -Dfile.encoding=utf-8 -Djava.security.egd=file:/dev/./urandom -jar app.jar
