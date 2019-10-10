#Build the Maven project
FROM maven:3.5.2-alpine as builder
COPY . /usr/src/app
WORKDIR /usr/src/app
RUN mvn clean install

FROM tomcat:alpine
MAINTAINER Mike Riley "michael.riley@gtri.gatech.edu"
RUN apk update
RUN apk add zip postgresql-client
	  
# Define environment variable
#ENV JDBC_USERNAME <DB Username>
#ENV JDBC_PASSWORD <DB Password>
#ENV JDBC_URL <JDBC URL>

# Copy GT-FHIR war file to webapps.
COPY --from=builder /usr/src/app/target/ecr-manager-0.0.2-SNAPSHOT.war $CATALINA_HOME/webapps/ecr-manager.war

EXPOSE 8080