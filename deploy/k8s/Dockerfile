# 通用镜像：构建时通过 --build-arg JAR=<服务 jar 路径> 指定
FROM eclipse-temurin:21-jre
ARG JAR
WORKDIR /app
COPY ${JAR} /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
