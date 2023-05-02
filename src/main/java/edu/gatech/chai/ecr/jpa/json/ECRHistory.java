/*
 * Filename: /Users/mc142/Documents/workspace/PACERv1/ecr_manager/src/main/java/edu/gatech/chai/ecr/jpa/json/ECR copy.java
 * Path: /Users/mc142/Documents/workspace/PACERv1/ecr_manager/src/main/java/edu/gatech/chai/ecr/jpa/json
 * Created Date: Monday, May 1st 2023, 7:18:55 pm
 * Author: Myung Choi
 * 
 * Copyright (c) 2023 GTRI - Health Emerging and Advanced Technologies (HEAT)
 */
package edu.gatech.chai.ecr.jpa.json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

@JsonSerialize
@JsonDeserialize
public class ECRHistory {
	@JsonProperty("ecrId")
	private Integer ecrId;
	@JsonProperty("date")
	protected String date = "";
	@JsonProperty("source")
	protected String source = "";
	@JsonProperty("data")
	protected ECR data = new ECR ();
	
	public ECRHistory () {}
	
	@JsonIgnore
	public Integer getECRId() {
		return ecrId;
	}
	
	@JsonIgnore
	public void setECRId(Integer ecrId) {
		this.ecrId = ecrId;
	}

	public String getDate() {
		return date;
	}

	public void setDate(String date) {
		this.date = date;
	}

	public String getSource() {
		return source;
	}

	public void setSource(String source) {
		this.source = source;
	}

	public ECR getData() {
		return data;
	}

	public void setData(ECR data) {
		this.data = data;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((ecrId == null) ? 0 : ecrId.hashCode());
		result = prime * result + ((date == null) ? 0 : date.hashCode());
		result = prime * result + ((source == null) ? 0 : source.hashCode());
		result = prime * result + ((data == null) ? 0 : data.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (!(obj instanceof ECRHistory))
			return false;
		ECRHistory other = (ECRHistory) obj;
		if (ecrId == null) {
			if (other.ecrId != null)
				return false;
		} else if (!ecrId.equals(other.ecrId))
			return false;
		if (date == null) {
			if (other.date != null)
				return false;
		} else if (!date.equals(other.date))
			return false;
		if (source == null) {
			if (other.source != null)
				return false;
		} else if (!source.equals(other.source))
			return false;
		if (data == null) {
			if (other.data != null)
				return false;
		} else if (!data.equals(other.data))
			return false;
		return true;
	}

	@Override
	public String toString() {
		return "ECRHistory [ecrId=" + ecrId + ", date=" + date + ", source=" + source + ", data=" + data + "]";
	}
}
