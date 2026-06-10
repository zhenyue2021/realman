FROM registry.cn-hangzhou.aliyuncs.com/dockerhub_mirror/java:17-anolis

MAINTAINER jeecgos@163.com

RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime

RUN mkdir -p /realman-cloud-xxljob

WORKDIR /realman-cloud-xxljob

EXPOSE 9080

ADD ./target/realman-cloud-xxljob-*.jar app.jar

CMD java -Dfile.encoding=utf-8 -Djava.security.egd=file:/dev/./urandom -jar app.jar
