FROM eclipse-temurin:17-jdk-alpine
MAINTAINER mirdan
COPY target/be-batch-1.0.2.jar be-batch-1.0.2.jar
ENTRYPOINT ["java","-jar","/be-batch-1.0.2.jar"]
