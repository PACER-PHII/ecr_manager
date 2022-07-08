package edu.gatech.chai.ecr.jpa.model;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

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
import edu.gatech.chai.ecr.jpa.json.Name;
import edu.gatech.chai.ecr.jpa.json.Patient;
import edu.gatech.chai.ecr.jpa.json.TypeableID;
import edu.gatech.chai.ecr.jpa.json.utils.AddressUtil;
import edu.gatech.chai.ecr.jpa.json.utils.ECRJsonConverter;

@Entity
@Table(name = "ecr_data", schema = "ecr")
public class ECRData {
	private static final Logger log = LoggerFactory.getLogger(ECRData.class);

	@Id
	@Column(name = "case_report_key")
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Integer id;
	@Column(name = "case_data", length=4096)
	@Convert(converter = ECRJsonConverter.class)
	private ECR data;
	@Column(name = "case_report_id")
	private Integer ecrId;
	@Column(name = "version")
	private Integer version;
	@Column(name = "created_date")
	@Temporal(TemporalType.TIMESTAMP)
	private Date created_date;
	@Column(name = "last_updated")
	@Temporal(TemporalType.TIMESTAMP)
	private Date last_updated;
	@Column(name = "patient_ids")
	private String patientIds;
	@Column(name = "last_name")
	private String lastName;
	@Column(name = "first_name")
	private String firstName;
	@Column(name = "zip_code")
	private String zipCode;
	@Column(name = "diagnosis")
	private String diagnosisCode;
	@Column(name = "first_date")
	@Temporal(TemporalType.TIMESTAMP)
	private Date firstDate;
	@Column(name = "last_date")
	@Temporal(TemporalType.TIMESTAMP)
	private Date lastDate;
	
	public ECRData() {}
	
	public ECRData(ECR ecr, int id) {
		ecr.setId(Integer.toString(id));
		data = ecr;
		ecrId = id;
		version = 1;
		Patient patient = ecr.getPatient();
		List<TypeableID> patientIdList = patient.getid();
		patientIds = ECRData.stringPatientIds(patientIdList);
		Name patientName = patient.getname();
		if(patientName != null) {
			if(!patientName.getfamily().isEmpty())
				lastName = patientName.getfamily();
			if(!patientName.getgiven().isEmpty())
				firstName = patientName.getgiven();
		}
		zipCode = AddressUtil.findZip(patient.getstreetAddress());
		if (patient.getDiagnosis() != null && patient.getDiagnosis().size() > 0) {
			diagnosisCode = patient.getDiagnosis().get(0).getCode();
		}
		created_date = new Date();
		last_updated = new Date();
	}
	
	public ECRData(ECRData oldData) {
		data = oldData.getECR();
		ecrId = oldData.getECRId();
		version = oldData.getVersion();
		patientIds = oldData.getPatientIds();
		lastName = oldData.getLastName();
		firstName = oldData.getFirstName();
		zipCode = oldData.getZipCode();
		diagnosisCode = oldData.getDiagnosisCode();
		created_date = oldData.getCreated_date();
		last_updated = oldData.getLast_updated();
	}
	
	public Integer getId() {
		return id;
	}
	
	public void setId(Integer id) {
		this.id = id;
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

	public Integer getVersion() {
		return version;
	}

	public void setVersion(Integer version) {
		this.version = version;
	}

	public Date getCreated_date() {
		return created_date;
	}

	public void setCreated_date(Date created_date) {
		this.created_date = created_date;
	}

	public Date getLast_updated() {
		return last_updated;
	}

	public void setLast_updated(Date last_updated) {
		this.last_updated = last_updated;
	}
	
	public String getPatientIds() {
		return patientIds;
	}
	
	public void setPatientIds(String patientIds) {
		this.patientIds = patientIds;
	}
		
	public String getLastName() {
		return lastName;
	}

	public void setLastName(String lastName) {
		this.lastName = lastName;
	}

	public String getFirstName() {
		return firstName;
	}

	public void setFirstName(String firstName) {
		this.firstName = firstName;
	}

	public String getZipCode() {
		return zipCode;
	}

	public void setZipCode(String zipCode) {
		this.zipCode = zipCode;
	}

	public String getDiagnosisCode() {
		return diagnosisCode;
	}

	public void setDiagnosisCode(String diagnosisCode) {
		this.diagnosisCode = diagnosisCode;
	}

	public Date getFirstDate() {
		return firstDate;
	}

	public void setFirstDate(Date firstDate) {
		this.firstDate = firstDate;
	}

	public Date getLastDate() {
		return lastDate;
	}

	public void setLastDate(Date lastDate) {
		this.lastDate = lastDate;
	}

	public void update(ECR ecr) {
		this.version = this.version + 1;
		this.last_updated = new Date();
		this.data.update(ecr);
	}
	
	static public String stringPatientId(TypeableID patientId) {
		if (patientId.getvalue().isBlank()) {
			log.warn("PatientId does not have a value.");
			return "";
		}

		return patientId.gettype().trim()+"|"+patientId.getvalue().trim();
	}
	
	public TypeableID typeablePatientId(String patientId) {
		if (patientId == null || patientId.isEmpty())
			return null;
		
		String[] patientTypeableId = patientId.split("|");
		if (patientTypeableId.length != 2) {
			return null;
		}
		
		TypeableID retVal = new TypeableID();
		retVal.settype(patientTypeableId[0]);
		retVal.setvalue(patientTypeableId[1]);
		
		return retVal;
	}
	
	public List<TypeableID> typeablePatientIds() {
		List<TypeableID> retVal = new ArrayList<TypeableID>();
		if (patientIds == null || patientIds.isEmpty()) {
			return retVal;
		}
		
		String[] patientIdList = patientIds.split("\\^");
		for (String patientId : patientIdList) {
			TypeableID tPat = typeablePatientId(patientId);
			if (tPat != null) {
				retVal.add(tPat);
			}
		}
		
		return retVal;
	}
	
	static public String stringPatientIds(List<TypeableID> patientIdList) {
		String retVal = new String();
		for (TypeableID patientId : patientIdList) {
			String patientIdString = ECRData.stringPatientId(patientId);
			if (patientIdString.isBlank()) continue;

			if (retVal.isEmpty()) {
				retVal = retVal.concat(patientIdString);
			} else {
				retVal = retVal.concat("^"+patientIdString);
			}
		}
		
		return retVal;
	}
}