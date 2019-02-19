# ECR Manager

This project is a prototype ECR Manager for the CDC-STI project. It manages a database of Electronic Case Records (ECRs), allows for iterative updates, and triggers PACER.

## Requirements

ECR Manager is written using annotation based springframework-boot, and uses a postgres database backend. We use maven to compile the project, and deploy the war artifact in either tomcat 7 or tomcat 8.

## Installing

Please ensure that maven and java-jdk 7.0 or higher is installed

```
sudo apt-get install -y java-jdk-8 maven
```

Compile the main project.
```
mvn clean install -DskipTests
```

To deploy the project, copy the war artifact into your tomcat webapp directoy
```
cp target/ecr-manager-0.0.1-SNAPSHOT.war $CATALINA_BASE/webapps/ecr-manager
```

## Configuring

If you are handling network configuration manually, you must edit the main configuration file found at src/main/resources/application.properties

Set the following environment variables for database connections
export JDBC_URL = <jdbc_url>
export JDBC_USERNAME = <postgres username>
export JDBC_PASSWORD = <postgres password>

### REST API

In order to request an ECR record you call a ECR resource ENDPOINT
```
GET http://www.ecrmanager.com/ECR?id=1
```
The response will contain an ECR object in the response body in the form of application/json.

You can request other ECR resources via different search criteria: firstname, lastname, zipCode and diagnosisCode

```
GET http://www.ecrmanager.com/ECR?firstname=Abraham
GET http://www.ecrmanager.com/ECR?lastname=Lincoln
GET http://www.ecrmanager.com/ECR?zipcode=42748
GET http://www.ecrmanager.com/ECR?diagnosisCode=78563
```

In order to write a new ECR record, simply POST to the same endpoint. If you would like to update an endpoint, use the PUT action and include the ECR id as well. 
