/*
 * Filename: /Users/mc142/Documents/workspace/PACERv1/ecr_manager/src/main/java/edu/gatech/chai/ecr/jpa/model/ECRData copy.java
 * Path: /Users/mc142/Documents/workspace/PACERv1/ecr_manager/src/main/java/edu/gatech/chai/ecr/jpa/model
 * Created Date: Thursday, March 23rd 2023, 2:41:58 am
 * Author: Myung Choi
 * 
 * Copyright (c) 2023 GTRI - Health Emerging and Advanced Technologies (HEAT)
 */
package edu.gatech.chai.ecr.jpa.model;

import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.gatech.chai.ecr.jpa.json.ECR;
import edu.gatech.chai.ecr.jpa.json.utils.ECRJsonConverter;

@Entity
@Table(name = "ecr_data_hisotry", schema = "ecr")
public class ECRDataHistory {
	private static final Logger log = LoggerFactory.getLogger(ECRDataHistory.class);

	@Id
	@Column(name = "case_report_history_key")
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Integer id;
	@Column(name = "source")
	private String source; // elr or ehr
	@Column(name = "case_report_id")
	private Integer ecrId;
	@Column(name = "case_data", length=40960)
	@Convert(converter = ECRJsonConverter.class)
	private ECR data;
	@Column(name = "date")
	@Temporal(TemporalType.TIMESTAMP)
	private Date date;
	
	public ECRDataHistory() {}

	public ECRDataHistory(ECR ecr, String source) {
		data = ecr;
		this.source = source;
		date = new Date();
	}

	public ECRDataHistory(ECR ecr, int id) {
		ecr.setId(Integer.toString(id));
		data = ecr;
		ecrId = id;
		date = new Date();
	}
	
	public ECRDataHistory(ECRDataHistory oldData) {
		data = oldData.getECR();
		ecrId = oldData.getECRId();
	}
	
	public Integer getId() {
		return id;
	}
	
	public void setId(Integer id) {
		this.id = id;
	}

	public String getSource() {
		return source;
	}

	public void setSource(String source) {
		this.source = source;
	}
	
	public ECR getECR() {
		return data;
	}
	
	public void setECR(ECR ecr) {
		this.data = ecr;
	}
	
	public Integer getECRId() {
		return ecrId;
	}
	
	public void setECRId(Integer ecrId) {
		this.ecrId = ecrId;
	}	
}