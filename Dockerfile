FROM eclipse-temurin:25-jdk
MAINTAINER mirdan
COPY target/be-batch-1.0.14.jar be-batch-1.0.14.jar
ENTRYPOINT ["java","-jar","/be-batch-1.0.14.jar"]
