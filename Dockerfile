#Build the Maven project
#FROM maven:3.8.5-jdk-11 as builder
FROM maven:3.6.3-openjdk-17 as builder
COPY . /usr/src/app
WORKDIR /usr/src/app
RUN mvn clean install

#FROM tomcat:jre17
FROM openjdk:17-jdk

# Define environment variable
#ENV JDBC_USERNAME <DB Username>
#ENV JDBC_PASSWORD <DB Password>
#ENV JDBC_URL <JDBC URL>

# Copy GT-FHIR war file to webapps.
#COPY --from=builder /usr/src/app/target/ecr-manager.war $CATALINA_HOME/webapps/ecr-manager.war
COPY --from=builder /usr/src/app/target/ecr-manager-0.5.0.jar /usr/src/myapp/ecr-manager.jar
WORKDIR /usr/src/myapp
CMD ["java", "-jar", "ecr-manager.jar"]

EXPOSE 8080
