FROM registry.cn-hangzhou.aliyuncs.com/jeecgdocker/mysql:8.0.19

MAINTAINER jeecgos@163.com

ENV TZ=Asia/Shanghai

RUN ln -sf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

COPY nacos_init.sql /docker-entrypoint-initdb.d

COPY realman_boot_init.sql /docker-entrypoint-initdb.d

COPY ./tables_xxl_job.sql /docker-entrypoint-initdb.d