# CI 产出的 jar 直接进镜像（宿主机 mvn package 后构建，避免容器内重复下载依赖）
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/seckill-2.0.0.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
