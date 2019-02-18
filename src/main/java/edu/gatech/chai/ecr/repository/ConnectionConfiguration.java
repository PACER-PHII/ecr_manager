package edu.gatech.chai.ecr.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix="direct.datasource")
public class ConnectionConfiguration {
	@Autowired
	private Environment env;
	
	private String url;
	private String username;
	private String password;
	private String schema;
	
	public String getUrl() {
		return url;
	}
	public void setUrl(String url) {
		if (System.getenv("JDBC_URL") != null || !System.getenv("JDBC_URL").isEmpty()) {
			url = System.getenv("JDBC_URL");
		}
		this.url = url;
	}
	public String getUsername() {
		return username;
	}
	public void setUsername(String username) {
		if (System.getenv("JDBC_USERNAME") != null || !System.getenv("JDBC_USERNAME").isEmpty()) {
			username = System.getenv("JDBC_USERNAME");
		}
		this.username = username;
	}
	public String getPassword() {
		return password;
	}
	public void setPassword(String password) {
		if (System.getenv("JDBC_PASSWORD") != null || !System.getenv("JDBC_PASSWORD").isEmpty()) {
			password = System.getenv("JDBC_PASSWORD");
		}
		this.password = password;
	}
	public String getSchema() {
		return schema;
	}
	public void setSchema(String schema) {
		this.schema = schema;
	}
}
