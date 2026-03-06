FROM registry.cn-hangzhou.aliyuncs.com/dockerhub_mirror/java:17-anolis

MAINTAINER jeecgos@163.com

RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime

#RUN mkdir -p /jeecg-boot/config/jeecg/

WORKDIR /realman-boot

EXPOSE 8080

#ADD ./src/main/resources/jeecg ./config/jeecg
ADD ./target/realman-system-start-3.9.1.jar ./

RUN mkdir -p /realman-boot/config

CMD sleep 60;java -Dfile.encoding=UTF-8 -Djava.security.egd=file:/dev/./urandom -jar realman-system-start-3.9.1.jar