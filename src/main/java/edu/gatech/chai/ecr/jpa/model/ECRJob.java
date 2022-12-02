package edu.gatech.chai.ecr.jpa.model;

import java.util.Calendar;
import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Entity
@Table(name = "ecr_job", schema = "ecr")
public class ECRJob {
	@Id
	@Column(name = "ecr_job_key")
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Integer id;
	@Column(name = "case_report_key")
	private Integer reportId;
	@Column(name = "patient_id")
	private String patientId;
	@Column(name = "next_run_date")
	@Temporal(TemporalType.TIMESTAMP)
	private Date nextRunDate;
	@Column(name = "status_code", length = 3)
	private String statusCode = "I";
	@Column(name = "update_count")
	private Integer updateCount = 0;
	@Column(name = "max_updates")
	private Integer maxUpdates = 4;
	@Column(name = "created_date")
	@Temporal(TemporalType.TIMESTAMP)
	private Date createdDate = new Date();
	@Column(name = "last_update_date")
	@Temporal(TemporalType.TIMESTAMP)
	private Date lastUpdateDate = new Date();

	private static final Logger logger = LoggerFactory.getLogger(ECRJob.class);

	public ECRJob() {
	}

	public ECRJob(ECRData ecrData) {
		reportId = Integer.valueOf(ecrData.getId());
		patientId = ECRData.stringPatientIds(ecrData.getECR().getPatient().getid());

//		reportId = Integer.valueOf(ecr.getECRId());
//		patientId = ECRData.stringPatientIds(ecr.getPatient().getid());
		
//		for(TypeableID id: ecr.getPatient().getid()) {
//			try{
//				patientId = Integer.parseInt(id.getvalue());
//				break;
//			}
//			catch(NumberFormatException e) {
//				continue;
//			}
//		}
	}

	public Integer getId() {
		return id;
	}

	public void setId(Integer id) {
		this.id = id;
	}

	public Integer getReportId() {
		return reportId;
	}

	public void setReportId(Integer reportId) {
		this.reportId = reportId;
	}

	public String getPatientId() {
		return patientId;
	}

	public void setPatientId(String patientId) {
		this.patientId = patientId;
	}

	public Date getNextRunDate() {
		return nextRunDate;
	}

	public void setNextRunDate(Date nextRunDate) {
		this.nextRunDate = nextRunDate;
	}

	public String getStatusCode() {
		return statusCode;
	}

	public void setStatusCode(String statusCode) {
		this.statusCode = statusCode;
	}

	public Integer getUpdateCount() {
		return updateCount;
	}

	public void setUpdateCount(Integer updateCount) {
		this.updateCount = updateCount;
	}

	public Integer getMaxUpdates() {
		return maxUpdates;
	}

	public void setMaxUpdates(Integer maxUpdates) {
		this.maxUpdates = maxUpdates;
	}

	public Date getCreatedDate() {
		return createdDate;
	}

	public void setCreatedDate(Date createdDate) {
		this.createdDate = createdDate;
	}

	public Date getLastUpdateDate() {
		return lastUpdateDate;
	}

	public void setLastUpdateDate(Date lastUpdateDate) {
		this.lastUpdateDate = lastUpdateDate;
	}

	public void startRun() {
		statusCode = "R";
	}

	public void startPeriodic() {
		statusCode = "P";
	}

	public void cancelJob() {
		statusCode = "I";
	}

	public static String R = "R";  	// Requesting
	public static String A = "A";	// Accomplished
	public static String C = "C";	// Cancelled
	public static String W = "W";	// Withdrawn
	public static String E = "E";  	// Error
	public static Integer HOLD_IN_MIN = 5;

	public void updateQueryStatus(String status) {
		lastUpdateDate = new Date();

		updateCount++;
		if (updateCount >= maxUpdates) {
			logger.info("Max Count reached for ECR JobId = " + id);
			statusCode = ECRJob.C;
			return;
		}

		if (status == null || status.isEmpty()) {
			logger.warn("Status is null or empty for ECR JobId = " + id);
			statusCode = ECRJob.C;
			return;
		}
		
		statusCode = status;
		if (ECRJob.R.equals(status)) {
			// Increase the wait time by 5 min
			logger.warn("Update try count = " + (updateCount-1) + " failed for ECR JobId = " + id);

			Calendar c = Calendar.getInstance();
			c.setTime(lastUpdateDate);
			c.add(Calendar.MINUTE, ECRJob.HOLD_IN_MIN*updateCount);
			nextRunDate = c.getTime();
		} 
	}
}
