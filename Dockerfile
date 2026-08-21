FROM eclipse-temurin:25-jdk
MAINTAINER mirdan
COPY target/be-batch-1.0.7.jar be-batch-1.0.7.jar
ENTRYPOINT ["java","-jar","/be-batch-1.0.7.jar"]
