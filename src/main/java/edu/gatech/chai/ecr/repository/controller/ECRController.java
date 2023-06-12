package edu.gatech.chai.ecr.repository.controller;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.PostConstruct;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import edu.gatech.chai.ecr.jpa.json.CodeableConcept;
import edu.gatech.chai.ecr.jpa.json.Diagnosis;
import edu.gatech.chai.ecr.jpa.json.ECR;
import edu.gatech.chai.ecr.jpa.json.ECRHistory;
import edu.gatech.chai.ecr.jpa.json.Facility;
import edu.gatech.chai.ecr.jpa.json.ImmunizationHistory;
import edu.gatech.chai.ecr.jpa.json.LabOrderCode;
import edu.gatech.chai.ecr.jpa.json.LabResult;
import edu.gatech.chai.ecr.jpa.json.Medication;
import edu.gatech.chai.ecr.jpa.json.ParentGuardian;
import edu.gatech.chai.ecr.jpa.json.Patient;
import edu.gatech.chai.ecr.jpa.json.Provider;
import edu.gatech.chai.ecr.jpa.json.TestResult;
import edu.gatech.chai.ecr.jpa.json.TypeableID;
import edu.gatech.chai.ecr.jpa.model.ECRData;
import edu.gatech.chai.ecr.jpa.model.ECRDataHistory;
import edu.gatech.chai.ecr.jpa.model.ECRJob;
import edu.gatech.chai.ecr.jpa.repo.ECRDataHistoryRepository;
import edu.gatech.chai.ecr.jpa.repo.ECRDataRepository;
import edu.gatech.chai.ecr.jpa.repo.ECRJobRepository;
import edu.gatech.chai.ecr.repository.ConnectionConfiguration;

@CrossOrigin
@RestController
public class ECRController {

	private static final Logger log = LoggerFactory.getLogger(ECRController.class);
	private static final Integer PAGE_SIZE = 50;

	private static final String GET_CASE_REPORT_SEQ = "select coalesce(MAX(case_report_id),0) as seq_id from ecr.ecr_data;";
	@Autowired
	protected ConnectionConfiguration connectionConfig;
	protected ECRDataRepository ecrDataRepository;
	protected ECRJobRepository ecrJobRepository;
	protected ECRDataHistoryRepository ecrDataHistoryRepository;
	private static AtomicInteger currentId;

	@Autowired
	public void setEcrDataRepository (ECRDataRepository ecrDataRepository) {
		this.ecrDataRepository = ecrDataRepository;
	}

	public ECRDataRepository getEcrDataRepository() {
		return ecrDataRepository;
	}
	
	@Autowired
	public void setEcrJobRepository(ECRJobRepository ecrJobRepository) {
		this.ecrJobRepository = ecrJobRepository;
	}

	public ECRJobRepository getEcrJobRepository() {
		return ecrJobRepository;
	}

	@Autowired
	public void setEcrDataHistoryRepository(ECRDataHistoryRepository ecrDataHistoryRepository) {
		this.ecrDataHistoryRepository = ecrDataHistoryRepository;
	}

	public ECRDataHistoryRepository ECRDataHistoryRepository() {
		return ecrDataHistoryRepository;
	}

	@RequestMapping(value = "/ECR", method = RequestMethod.POST)
	public ResponseEntity<ECR> postNewECR(@RequestBody ECR ecr, @RequestParam(name = "source", defaultValue = "elr", required = false) String source) {
		// First create ecr history data
		ECRDataHistory ecrDataHistory = new ECRDataHistory(ecr, source);

		ECRData data = null;
		Patient patient = ecr.getPatient();
		List<TypeableID> patientIdList = patient.getid();
		if (patientIdList != null) {
			for (TypeableID patientIdType : patient.getid()) {
				String patientId = ECRData.stringPatientId(patientIdType);
				List<ECRData> ecrs = ecrDataRepository.findByPatientIdsContainingIgnoreCase(patientId);
				if (ecrs != null && ecrs.size() > 0) {
					data = ecrs.get(0);
					data.update(ecr);
					ecr.setECRId(Integer.toString(data.getECRId()));
					log.info("ELR received for an existing case, case_report_key=" + data.getId());
					break;
				}
			}
		}

		// Name name = ecr.getPatient().getname();
		// if (name != null) {
		// 	String firstName = name.getgiven();
		// 	String lastName = name.getfamily();
		// 	String zipCode = AddressUtil.findZip(ecr.getPatient().getstreetAddress());
		// 	List<ECRData> ecrs = ecrDataRepository.findByLastNameAndFirstNameAndZipCodeOrderByVersionDesc(lastName,
		// 			firstName, zipCode, PageRequest.of(0, PAGE_SIZE));
		// 	if (ecrs != null && ecrs.size() > 0) {
		// 		data = ecrs.get(0);
		// 		data.update(ecr);
		// 		log.info("ELR received for an existing case, id="+ecr.getECRId());
		// 	}
		// }

		if (data == null) {
			data = new ECRData(ecr, currentId.incrementAndGet());
		}
		ecrDataRepository.save(data);

		// See if this is in the job list.
		List<ECRJob> ecrJobs = ecrJobRepository.findByReportIdOrderByIdDesc(data.getId());
		ECRJob ecrJob;
		Date now = new Date();
		if (ecrJobs == null || ecrJobs.size() == 0) {
			ecrJob = new ECRJob(data);
		} else {
			ecrJob = ecrJobs.get(0);
			ecrJob.setLastUpdateDate(now);
		}

		Calendar c = Calendar.getInstance();
		c.setTime(now);
		c.add(Calendar.MINUTE, 2);
		ecrJob.setNextRunDate(c.getTime());
		
		// Set/Reset the Max Update to 3
		ecrJob.setUpdateCount(0);
		ecrJob.startRun();
			
		// Add this to the job.
		ecrJobRepository.save(ecrJob);

		// set ecrId in ecr data history and save it to the history table.
		ecrDataHistory.setECRId(data.getECRId());
		ecrDataHistoryRepository.save(ecrDataHistory);
				
		return new ResponseEntity<ECR>(data.getECR(), HttpStatus.CREATED);
	}

	@RequestMapping(value = "/ECR", method = RequestMethod.GET)
	public ResponseEntity<List<ECR>> getECR(
			@RequestParam(name = "page", defaultValue = "0", required = false) Integer page) {
		List<ECRData> data = new ArrayList<ECRData>();
		int diffPull = -1;
		while (diffPull != 0 && data.size() < PAGE_SIZE) {
			Pageable pageable = PageRequest.of(page, PAGE_SIZE);
			List<ECRData> incomingData = ecrDataRepository.findAll(pageable).getContent();
			int oldSize = data.size();
			data.addAll(incomingData);
			data = lintVersionsFromECRDataList(data);
			diffPull = data.size() - oldSize;
			page = page + 1;
		}
		data = data.size() < PAGE_SIZE ? data.subList(0, data.size()) : data.subList(0, PAGE_SIZE);
		List<ECR> ecrReturnList = transformECRDataToECR(data);
		return new ResponseEntity<List<ECR>>(ecrReturnList, HttpStatus.OK);
	}

	private List<ECRHistory> transformECRDataHistoryToECRHistory(List<ECRDataHistory> dataHistories) {
		List<ECRHistory> ecrHistories = new ArrayList<ECRHistory>();
		for (ECRDataHistory dataHistory : dataHistories) {
			ECRHistory ecrHistory = new ECRHistory();
			ecrHistory.setECRId(dataHistory.getECRId());
			ecrHistory.setData(dataHistory.getECR());
			ecrHistory.setDate(dataHistory.getDate().toString());
			ecrHistory.setSource(dataHistory.getSource());
			ecrHistories.add(ecrHistory);
		}

		return ecrHistories;
	}

	@RequestMapping(value = "/ECRhistory", method = RequestMethod.GET)
	public ResponseEntity<List<ECRHistory>> getECRHisotry(
			@RequestParam(name = "case_id", defaultValue = "-1", required = false) Integer ecrId) {
		List<ECRDataHistory> data = new ArrayList<ECRDataHistory>();
		if (ecrId < 0) {
			data.addAll(ecrDataHistoryRepository.findAll());
		} else {
			data.addAll(ecrDataHistoryRepository.findByEcrId(ecrId));
		}
		
		List<ECRHistory> ecrReturnList = transformECRDataHistoryToECRHistory(data);
		return new ResponseEntity<List<ECRHistory>>(ecrReturnList, HttpStatus.OK);
	}

	@RequestMapping(value = "/exportCSV", method = RequestMethod.GET, produces = "text/csv")
	public ResponseEntity<Resource> exportCSV() {
	// public ResponseEntity<Resource> exportCSV(
	// 		@RequestParam(name = "page", defaultValue = "0", required = false) Integer page) {

		List<String> csvHeaderList = new ArrayList<String>();

		// List<ECRData> data = new ArrayList<ECRData>();
		// int diffPull = -1;
		// while (diffPull != 0 && data.size() < PAGE_SIZE) {
		// 	Pageable pageable = PageRequest.of(page, PAGE_SIZE);
		// 	List<ECRData> incomingData = ecrDataRepository.findAll(pageable).getContent();
		// 	int oldSize = data.size();
		// 	data.addAll(incomingData);
		// 	data = lintVersionsFromECRDataList(data);
		// 	diffPull = data.size() - oldSize;
		// 	page = page + 1;
		// }
		// data = data.size() < PAGE_SIZE ? data.subList(0, data.size()) : data.subList(0, PAGE_SIZE);

		List<ECRData> data = ecrDataRepository.findAll();
		List<ECR> ecrReturnList = transformECRDataToECR(data);

		List<List<String>> csv = new ArrayList<>();

		// CSV Header
		csvHeaderList.add("id");

		// First find the size of columns. As some ECR may have multiple entries, we need walk over all of lists.
		int index = 1;
		int maxNumOfProviders = 0;
		for (ECR ecr : ecrReturnList) {
			int numOfProviders = ecr.getProvider().size();
			if (numOfProviders > maxNumOfProviders) {
				int numToWrite = numOfProviders - maxNumOfProviders;
				for (int i = 0; i < numToWrite; i++) {
					csvHeaderList.add("Provider_id_" + index);
					csvHeaderList.add("provider_name_" + index);
					csvHeaderList.add("provider_phone_" + index);
					csvHeaderList.add("provider_email_" + index);
					csvHeaderList.add("provider_fax_" + index);
					csvHeaderList.add("provider_facility_" + index);
					csvHeaderList.add("provider_address_" + index);
					csvHeaderList.add("provider_country_" + index);
					index++;
				}
				maxNumOfProviders = numOfProviders;
			}
		}

		csvHeaderList.add("facility_id");
		csvHeaderList.add("facility_name");
		csvHeaderList.add("facility_phone");
		csvHeaderList.add("facility_address");
		csvHeaderList.add("facility_fax");
		csvHeaderList.add("facility_hospital_unit");
		csvHeaderList.add("patient_id");
		csvHeaderList.add("patient_name");

		index = 1;
		int maxNumOfParentGuardians = 0;
		for (ECR ecr : ecrReturnList) {
			int numOfParentGuardians = ecr.getPatient().getparentsGuardians().size();
			if (numOfParentGuardians > maxNumOfParentGuardians) {
				int numToWrite = numOfParentGuardians - maxNumOfParentGuardians;
				for (int i = 0; i < numToWrite; i++) {
					csvHeaderList.add("patient_guardian_name_" + index);
					csvHeaderList.add("patient_guardian_phone_" + index);
					csvHeaderList.add("patient_guardian_email_" + index);
					index++;
				}
				maxNumOfParentGuardians = numOfParentGuardians;
			}
		}

		csvHeaderList.add("patient_address");
		csvHeaderList.add("patient_brithDate");
		csvHeaderList.add("patient_sex");
		csvHeaderList.add("patient_patientClass");
		csvHeaderList.add("patient_race");
		csvHeaderList.add("patient_ethnicity");
		csvHeaderList.add("patient_preferredLanguage");
		csvHeaderList.add("patient_occupation");
		csvHeaderList.add("patient_pregnant");

		index = 1;
		int maxNumOfTravelHistory = 0;
		for (ECR ecr : ecrReturnList) {
			int numOfTravelHistory = ecr.getPatient().gettravelHistory().size();
			if (numOfTravelHistory > maxNumOfTravelHistory) {
				int numToWrite = numOfTravelHistory - maxNumOfTravelHistory;
				for (int i = 0; i < numToWrite; i++) {
					csvHeaderList.add("patient_travelHistory_" + index);
					index++;
				}
				maxNumOfTravelHistory = numOfTravelHistory;
			}
		}

		csvHeaderList.add("patient_insuranceType");

		index = 1;
		int maxNumOfImmunizationHistory = 0;
		for (ECR ecr : ecrReturnList) {
			int numOfImmunizationlHistory = ecr.getPatient().getimmunizationHistory().size();
			if (numOfImmunizationlHistory > maxNumOfImmunizationHistory) {
				int numToWrite = numOfImmunizationlHistory - maxNumOfImmunizationHistory;
				for (int i = 0; i < numToWrite; i++) {
					csvHeaderList.add("patient_immunizationHistory_" + index);
					index++;
				}
				maxNumOfImmunizationHistory = numOfImmunizationlHistory;
			}
		}

		csvHeaderList.add("patient_visitDateTime");
		csvHeaderList.add("patient_admissionDateTime");
		csvHeaderList.add("patient_dateOfOnset");

		index = 1;
		int maxNumOfSymptoms = 0;
		for (ECR ecr : ecrReturnList) {
			int numOfSymptoms = ecr.getPatient().getsymptoms().size();
			if (numOfSymptoms > maxNumOfSymptoms) {
				int numToWrite = numOfSymptoms - maxNumOfSymptoms;
				for (int i = 0; i < numToWrite; i++) {
					csvHeaderList.add("patient_symptoms_" + index);
					index++;
				}
				maxNumOfSymptoms = numOfSymptoms;
			}
		}

		index = 1;
		int maxNumOfLabOrderCode = 0;
		for (ECR ecr : ecrReturnList) {
			int numOfLabOrderCode = ecr.getPatient().getlabOrderCode().size();
			if (numOfLabOrderCode > maxNumOfLabOrderCode) {
				int numToWrite = numOfLabOrderCode - maxNumOfLabOrderCode;
				for (int i = 0; i < numToWrite; i++) {
					csvHeaderList.add("patient_labOrderCode_" + index);
					index++;
				}
				maxNumOfLabOrderCode = numOfLabOrderCode;
			}
		}

		csvHeaderList.add("patient_placerOrderCode");

		index = 1;
		int maxNumOfDiagnosis = 0;
		for (ECR ecr : ecrReturnList) {
			int numOfDiagnosis = ecr.getPatient().getDiagnosis().size();
			if (numOfDiagnosis > maxNumOfDiagnosis) {
				int numToWrite = numOfDiagnosis - maxNumOfDiagnosis;
				for (int i = 0; i < numToWrite; i++) {
					csvHeaderList.add("patient_diagnosis_" + index);
					index++;
				}
				maxNumOfDiagnosis = numOfDiagnosis;
			}
		}

		index = 1;
		int maxNumOfMedicationProvided = 0;
		for (ECR ecr : ecrReturnList) {
			int numOfMedicationProvided = ecr.getPatient().getMedicationProvided().size();
			if (numOfMedicationProvided > maxNumOfMedicationProvided) {
				int numToWrite = numOfMedicationProvided - maxNumOfMedicationProvided;
				for (int i = 0; i < numToWrite; i++) {
					csvHeaderList.add("patient_medicationProvided_" + index);
					index++;
				}
				maxNumOfMedicationProvided = numOfMedicationProvided;
			}
		}

		csvHeaderList.add("patient_deathDate");
		csvHeaderList.add("patient_dateDischarged");

		index = 1;
		int maxNumOfLaboratoryResult = 0;
		for (ECR ecr : ecrReturnList) {
			int numOfLaboratoryResult = ecr.getPatient().getlaboratoryResults().size();
			if (numOfLaboratoryResult > maxNumOfLaboratoryResult) {
				int numToWrite = numOfLaboratoryResult - maxNumOfLaboratoryResult;
				for (int i = 0; i < numToWrite; i++) {
					csvHeaderList.add("patient_laboratoryResult_" + index);
					index++;
				}
				maxNumOfLaboratoryResult = numOfLaboratoryResult;
			}
		}

		index = 1;
		int maxNumOfTriggerCode = 0;
		for (ECR ecr : ecrReturnList) {
			int numOfTriggerCode = ecr.getPatient().gettriggerCode().size();
			if (numOfTriggerCode > maxNumOfTriggerCode) {
				int numToWrite = numOfTriggerCode - maxNumOfTriggerCode;
				for (int i = 0; i < numToWrite; i++) {
					csvHeaderList.add("patient_triggerCode_" + index);
					index++;
				}
				maxNumOfTriggerCode = numOfTriggerCode;
			}
		}

		index = 1;
		int maxNumOfLabTestsPerformed = 0;
		for (ECR ecr : ecrReturnList) {
			int numOfLabTestsPerformed = ecr.getPatient().getlabTestsPerformed().size();
			if (numOfLabTestsPerformed > maxNumOfLabTestsPerformed) {
				int numToWrite = numOfLabTestsPerformed - maxNumOfLabTestsPerformed;
				for (int i = 0; i < numToWrite; i++) {
					csvHeaderList.add("patient_labTestsPerformed_" + index);
					index++;
				}
				maxNumOfLabTestsPerformed = numOfLabTestsPerformed;
			}
		}

		csvHeaderList.add("sendingApplication");

		index = 1;
		int maxNumOfNote = 0;
		for (ECR ecr : ecrReturnList) {
			int numOfNote = ecr.getNotes().size();
			if (numOfNote > maxNumOfNote) {
				int numToWrite = numOfNote - maxNumOfNote;
				for (int i = 0; i < numToWrite; i++) {
					csvHeaderList.add("note_" + index);
					index++;
				}
				maxNumOfNote = numOfNote;
			}
		}

		for (ECR ecr : ecrReturnList) {
			List<String> row = new ArrayList<String>();

			row.add(ecr.getECRId());
			for (Provider provider : ecr.getProvider()) {
				row.add(ECRData.stringPatientId(provider.getid()));
				row.add(provider.getname());
				row.add(provider.getphone());
				row.add(provider.getemail());
				row.add(provider.getfax());
				row.add(provider.getfacility());
				row.add(provider.getaddress());
				row.add(provider.getcountry());
			}
			for (int i = 0; i < maxNumOfProviders-ecr.getProvider().size(); i++) {
				row.add("");
				row.add("");
				row.add("");
				row.add("");
				row.add("");
				row.add("");
				row.add("");
				row.add("");
			}

			Facility facility = ecr.getFacility();
			row.add(facility.getid());
			row.add(facility.getname());
			row.add(facility.getphone());
			row.add(facility.getaddress());
			row.add(facility.getfax());
			row.add(facility.gethospitalUnit());

			Patient patient = ecr.getPatient();
			String idStr = ECRData.stringPatientIds(patient.getid());
			row.add(idStr);
			row.add(patient.getname().toString());

			for (ParentGuardian parentGuardian : patient.getparentsGuardians()) {
				row.add(parentGuardian.getname().toString());
				row.add(parentGuardian.getphone());
				row.add(parentGuardian.getemail());					
			}
			for (int i = 0; i < maxNumOfParentGuardians-patient.getparentsGuardians().size(); i++) {
				row.add("");
				row.add("");
				row.add("");
			}
			
			row.add(patient.getstreetAddress());
			row.add(patient.getbirthDate());
			row.add(patient.getsex());
			row.add(patient.getpatientClass());
			row.add(patient.getrace().toString());
			row.add(patient.getethnicity().toString());
			row.add(patient.getpreferredLanguage().toString());
			row.add(patient.getoccupation());
			if (patient.ispregnant()) {
				row.add("true");
			} else {
				row.add("false");
			}

			for (String travelHistory : patient.gettravelHistory()) {
				row.add(travelHistory);
			}
			for (int i = 0; i < maxNumOfTravelHistory-patient.gettravelHistory().size(); i++) {
				row.add("");
			}

			row.add(patient.getinsuranceType().toString());

			for ( ImmunizationHistory immunizationHistory : patient.getimmunizationHistory()) {
				row.add(immunizationHistory.toString());
			}
			for (int i = 0; i < maxNumOfImmunizationHistory-patient.getimmunizationHistory().size(); i++) {
				row.add("");
			}

			row.add(patient.getvisitDateTime());
			row.add(patient.getadmissionDateTime());
			row.add(patient.getdateOfOnset());
			
			for (CodeableConcept symptom : patient.getsymptoms()) {
				row.add(symptom.toString());
			}
			for (int i = 0; i < maxNumOfSymptoms-patient.getsymptoms().size(); i++) {
				row.add("");
			}

			for (LabOrderCode labOrderCode : patient.getlabOrderCode()) {
				row.add(labOrderCode.toString());
			}
			for (int i = 0; i < maxNumOfLabOrderCode-patient.getlabOrderCode().size(); i++) {
				row.add("");
			}

			row.add(patient.getplacerOrderCode());

			for (Diagnosis diagnosis : patient.getDiagnosis()) {
				row.add(diagnosis.toString());
			}
			for (int i = 0; i < maxNumOfDiagnosis-patient.getDiagnosis().size(); i++) {
				row.add("");
			}

			for (Medication medicationProvided : patient.getMedicationProvided()) {
				row.add(medicationProvided.toString());
			}
			for (int i = 0; i < maxNumOfMedicationProvided-patient.getMedicationProvided().size(); i++) {
				row.add("");
			}

			row.add(patient.getdeathDate());
			row.add(patient.getdateDischarged());

			for (LabResult labResult : patient.getlaboratoryResults()) {
				row.add(labResult.toString());
			}
			for (int i = 0; i < maxNumOfLaboratoryResult-patient.getlaboratoryResults().size(); i++) {
				row.add("");
			}

			for (CodeableConcept triggerCode : patient.gettriggerCode()) {
				row.add(triggerCode.toString());
			}
			for (int i = 0; i < maxNumOfTriggerCode-patient.gettriggerCode().size(); i++) {
				row.add("");
			}

			for (TestResult labTestsPerformed : patient.getlabTestsPerformed()) {
				row.add(labTestsPerformed.toString());
			}
			for (int i = 0; i < maxNumOfLabTestsPerformed-patient.getlabTestsPerformed().size(); i++) {
				row.add("");
			}

			row.add(ecr.getSendingApplication());

			for (String note : ecr.getNotes()) {
				row.add(note);
			}
			for (int i = 0; i < maxNumOfNote-ecr.getNotes().size(); i++) {
				row.add("");
			}

			csv.add(row);	
		}

		String[] csvHeader = csvHeaderList.toArray(String[]::new);
		ByteArrayInputStream byteArrayOutputStream;

		// closing resources by using a try with resources
		// https://www.baeldung.com/java-try-with-resources
		try (
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			// defining the CSV printer
			CSVPrinter csvPrinter = new CSVPrinter(
				new PrintWriter(out),
				// withHeader is optional
				CSVFormat.DEFAULT.withHeader(csvHeader)
			);
		) {
			// populating the CSV content
			for (List<String> record : csv)
				csvPrinter.printRecord(record);

			// writing the underlying stream
			csvPrinter.flush();
			byteArrayOutputStream = new ByteArrayInputStream(out.toByteArray());
	    } catch (IOException e) {
    	    throw new RuntimeException(e.getMessage());
    	}

	    InputStreamResource fileInputStream = new InputStreamResource(byteArrayOutputStream);

		Date now = new Date();
		SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMddHHmmss");
	    String csvFileName = "ecr_" + formatter.format(now) + ".csv";

		// setting HTTP headers
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + csvFileName);
		// defining the custom Content-Type
		headers.set(HttpHeaders.CONTENT_TYPE, "text/csv");

		return new ResponseEntity<>(
				fileInputStream,
				headers,
				HttpStatus.OK
		);
	}

	@RequestMapping(value = "/ECR", method = RequestMethod.GET, params = "id")
	public ResponseEntity<ECR> getECRByECRId(@RequestParam Integer id) {
		ECR ret = new ECR();
		List<ECRData> ecrDatas = ecrDataRepository.findByEcrIdOrderByVersionDesc(id);
		if (ecrDatas != null && !ecrDatas.isEmpty()) {
			ECRData data = ecrDatas.get(0);
			ret = data.getECR();
		}

		return new ResponseEntity<ECR>(ret, HttpStatus.OK);
	}

	@RequestMapping(value = "/ECR", method = RequestMethod.GET, params = "lastName")
	public ResponseEntity<List<ECR>> getECRByLastName(@RequestParam String lastName,
			@RequestParam(name = "page", defaultValue = "0", required = false) Integer page) {
		List<ECRData> data = new ArrayList<ECRData>();
		int diffPull = -1;
		while (diffPull != 0 && data.size() < PAGE_SIZE) {
			Pageable pageable = PageRequest.of(page, PAGE_SIZE);
			List<ECRData> incomingData = ecrDataRepository.findByLastNameOrderByVersionDesc(lastName, pageable);
			int oldSize = data.size();
			data.addAll(incomingData);
			data = lintVersionsFromECRDataList(data);
			diffPull = data.size() - oldSize;
			page = page + 1;
		}
		data = data.size() < PAGE_SIZE ? data.subList(0, data.size()) : data.subList(0, PAGE_SIZE);
		List<ECR> ecrReturnList = transformECRDataToECR(data);
		return new ResponseEntity<List<ECR>>(ecrReturnList, HttpStatus.OK);
	}

	@RequestMapping(value = "/ECR", method = RequestMethod.GET, params = "firstName")
	public ResponseEntity<List<ECR>> getECRByFirstName(@RequestParam String firstName,
			@RequestParam(name = "page", defaultValue = "0", required = false) Integer page) {
		List<ECRData> data = new ArrayList<ECRData>();
		int diffPull = -1;
		while (diffPull != 0 && data.size() < PAGE_SIZE) {
			Pageable pageable = PageRequest.of(page, PAGE_SIZE);
			List<ECRData> incomingData = ecrDataRepository.findByFirstNameOrderByVersionDesc(firstName, pageable);
			int oldSize = data.size();
			data.addAll(incomingData);
			data = lintVersionsFromECRDataList(data);
			diffPull = data.size() - oldSize;
			page = page + 1;
		}
		data = data.size() < PAGE_SIZE ? data.subList(0, data.size()) : data.subList(0, PAGE_SIZE);
		List<ECR> ecrReturnList = transformECRDataToECR(data);
		return new ResponseEntity<List<ECR>>(ecrReturnList, HttpStatus.OK);
	}

	@RequestMapping(value = "/ECR", method = RequestMethod.GET, params = "zipCode")
	public ResponseEntity<List<ECR>> getECRByZipCode(@RequestParam String zipCode,
			@RequestParam(name = "page", defaultValue = "0", required = false) Integer page) {
		List<ECRData> data = new ArrayList<ECRData>();
		int diffPull = -1;
		while (diffPull != 0 && data.size() < PAGE_SIZE) {
			Pageable pageable = PageRequest.of(page, PAGE_SIZE);
			List<ECRData> incomingData = ecrDataRepository.findByZipCodeOrderByVersionDesc(zipCode, pageable);
			int oldSize = data.size();
			data.addAll(incomingData);
			data = lintVersionsFromECRDataList(data);
			diffPull = data.size() - oldSize;
			page = page + 1;
		}
		data = data.size() < PAGE_SIZE ? data.subList(0, data.size()) : data.subList(0, PAGE_SIZE);
		List<ECR> ecrReturnList = transformECRDataToECR(data);
		return new ResponseEntity<List<ECR>>(ecrReturnList, HttpStatus.OK);
	}

	@RequestMapping(value = "/ECR", method = RequestMethod.GET, params = "diagnosisCode")
	public ResponseEntity<List<ECR>> getECRByDiagnosisCode(@RequestParam String diagnosisCode,
			@RequestParam(name = "page", defaultValue = "0", required = false) Integer page) {
		List<ECRData> data = new ArrayList<ECRData>();
		int diffPull = -1;
		while (diffPull != 0 && data.size() < PAGE_SIZE) {
			Pageable pageable = PageRequest.of(page, PAGE_SIZE);
			List<ECRData> incomingData = ecrDataRepository.findByDiagnosisCodeOrderByVersionDesc(diagnosisCode,
					pageable);
			int oldSize = data.size();
			data.addAll(incomingData);
			data = lintVersionsFromECRDataList(data);
			diffPull = data.size() - oldSize;
			page = page + 1;
		}
		data = data.size() < PAGE_SIZE ? data.subList(0, data.size()) : data.subList(0, PAGE_SIZE);
		List<ECR> ecrReturnList = transformECRDataToECR(data);
		return new ResponseEntity<List<ECR>>(ecrReturnList, HttpStatus.OK);
	}

	@RequestMapping(value = "/ECR", method = RequestMethod.GET, params = { "lastName", "firstName" })
	public ResponseEntity<List<ECR>> getECRByLastNameAndFirstName(@RequestParam String lastName,
			@RequestParam String firstName,
			@RequestParam(name = "page", defaultValue = "0", required = false) Integer page) {
		List<ECRData> data = new ArrayList<ECRData>();
		int diffPull = -1;
		while (diffPull != 0 && data.size() < PAGE_SIZE) {
			Pageable pageable = PageRequest.of(page, PAGE_SIZE);
			List<ECRData> incomingData = ecrDataRepository.findByLastNameAndFirstNameOrderByVersionDesc(lastName,
					firstName, pageable);
			int oldSize = data.size();
			data.addAll(incomingData);
			data = lintVersionsFromECRDataList(data);
			diffPull = data.size() - oldSize;
			page = page + 1;
		}
		data = data.size() < PAGE_SIZE ? data.subList(0, data.size()) : data.subList(0, PAGE_SIZE);
		List<ECR> ecrReturnList = transformECRDataToECR(data);
		return new ResponseEntity<List<ECR>>(ecrReturnList, HttpStatus.OK);
	}

	@RequestMapping(value = "/ECR", method = RequestMethod.GET, params = { "lastName", "zipCode" })
	public ResponseEntity<List<ECR>> getECRByLastNameAndZipCode(@RequestParam String lastName,
			@RequestParam String zipCode,
			@RequestParam(name = "page", defaultValue = "0", required = false) Integer page) {
		List<ECRData> data = new ArrayList<ECRData>();
		int diffPull = -1;
		while (diffPull != 0 && data.size() < PAGE_SIZE) {
			Pageable pageable = PageRequest.of(page, PAGE_SIZE);
			List<ECRData> incomingData = ecrDataRepository.findByLastNameAndZipCodeOrderByVersionDesc(lastName, zipCode,
					pageable);
			int oldSize = data.size();
			data.addAll(incomingData);
			data = lintVersionsFromECRDataList(data);
			diffPull = data.size() - oldSize;
			page = page + 1;
		}
		data = data.size() < PAGE_SIZE ? data.subList(0, data.size()) : data.subList(0, PAGE_SIZE);
		List<ECR> ecrReturnList = transformECRDataToECR(data);
		return new ResponseEntity<List<ECR>>(ecrReturnList, HttpStatus.OK);
	}

	@RequestMapping(value = "/ECR", method = RequestMethod.GET, params = { "lastName", "diagnosisCode" })
	public ResponseEntity<List<ECR>> getECRByLastNameAndDiagnosisCode(@RequestParam String lastName,
			@RequestParam String diagnosisCode,
			@RequestParam(name = "page", defaultValue = "0", required = false) Integer page) {
		List<ECRData> data = new ArrayList<ECRData>();
		int diffPull = -1;
		while (diffPull != 0 && data.size() < PAGE_SIZE) {
			Pageable pageable = PageRequest.of(page, PAGE_SIZE);
			List<ECRData> incomingData = ecrDataRepository.findByLastNameAndDiagnosisCodeOrderByVersionDesc(lastName,
					diagnosisCode, pageable);
			int oldSize = data.size();
			data.addAll(incomingData);
			data = lintVersionsFromECRDataList(data);
			diffPull = data.size() - oldSize;
			page = page + 1;
		}
		data = data.size() < PAGE_SIZE ? data.subList(0, data.size()) : data.subList(0, PAGE_SIZE);
		List<ECR> ecrReturnList = transformECRDataToECR(data);
		return new ResponseEntity<List<ECR>>(ecrReturnList, HttpStatus.OK);
	}

	@RequestMapping(value = "/ECR", method = RequestMethod.GET, params = { "firstName", "zipCode" })
	public ResponseEntity<List<ECR>> getECRByFirstNameAndZipCode(@RequestParam String firstName,
			@RequestParam String zipCode,
			@RequestParam(name = "page", defaultValue = "0", required = false) Integer page) {
		List<ECRData> data = new ArrayList<ECRData>();
		int diffPull = -1;
		while (diffPull != 0 && data.size() < PAGE_SIZE) {
			Pageable pageable = PageRequest.of(page, PAGE_SIZE);
			List<ECRData> incomingData = ecrDataRepository.findByFirstNameAndZipCodeOrderByVersionDesc(firstName,
					zipCode, pageable);
			int oldSize = data.size();
			data.addAll(incomingData);
			data = lintVersionsFromECRDataList(data);
			diffPull = data.size() - oldSize;
			page = page + 1;
		}
		data = data.size() < PAGE_SIZE ? data.subList(0, data.size()) : data.subList(0, PAGE_SIZE);
		List<ECR> ecrReturnList = transformECRDataToECR(data);
		return new ResponseEntity<List<ECR>>(ecrReturnList, HttpStatus.OK);
	}

	@RequestMapping(value = "/ECR", method = RequestMethod.GET, params = { "firstName", "diagnosisCode" })
	public ResponseEntity<List<ECR>> getECRByFirstNameAndDiagnosisCode(@RequestParam String firstName,
			@RequestParam String diagnosisCode,
			@RequestParam(name = "page", defaultValue = "0", required = false) Integer page) {
		List<ECRData> data = new ArrayList<ECRData>();
		int diffPull = -1;
		while (diffPull != 0 && data.size() < PAGE_SIZE) {
			Pageable pageable = PageRequest.of(page, PAGE_SIZE);
			List<ECRData> incomingData = ecrDataRepository.findByFirstNameAndDiagnosisCodeOrderByVersionDesc(firstName,
					diagnosisCode, pageable);
			int oldSize = data.size();
			data.addAll(incomingData);
			data = lintVersionsFromECRDataList(data);
			diffPull = data.size() - oldSize;
			page = page + 1;
		}
		data = data.size() < PAGE_SIZE ? data.subList(0, data.size()) : data.subList(0, PAGE_SIZE);
		List<ECR> ecrReturnList = transformECRDataToECR(data);
		return new ResponseEntity<List<ECR>>(ecrReturnList, HttpStatus.OK);
	}

	@RequestMapping(value = "/ECR", method = RequestMethod.GET, params = { "zipCode", "diagnosisCode" })
	public ResponseEntity<List<ECR>> getECRByZipCodeAndDiagnosisCode(@RequestParam String zipCode,
			@RequestParam String diagnosisCode,
			@RequestParam(name = "page", defaultValue = "0", required = false) Integer page) {
		List<ECRData> data = new ArrayList<ECRData>();
		int diffPull = -1;
		while (diffPull != 0 && data.size() < PAGE_SIZE) {
			Pageable pageable = PageRequest.of(page, PAGE_SIZE);
			List<ECRData> incomingData = ecrDataRepository.findByZipCodeAndDiagnosisCodeOrderByVersionDesc(zipCode,
					diagnosisCode, pageable);
			int oldSize = data.size();
			data.addAll(incomingData);
			data = lintVersionsFromECRDataList(data);
			diffPull = data.size() - oldSize;
			page = page + 1;
		}
		data = data.size() < PAGE_SIZE ? data.subList(0, data.size()) : data.subList(0, PAGE_SIZE);
		List<ECR> ecrReturnList = transformECRDataToECR(data);
		return new ResponseEntity<List<ECR>>(ecrReturnList, HttpStatus.OK);
	}

	@RequestMapping(value = "/ECR", method = RequestMethod.GET, params = { "lastName", "firstName", "zipCode" })
	public ResponseEntity<List<ECR>> getECRByLastNameAndFirstNameAndZipCode(@RequestParam String lastName,
			@RequestParam String firstName, @RequestParam String zipCode,
			@RequestParam(name = "page", defaultValue = "0", required = false) Integer page) {
		List<ECRData> data = new ArrayList<ECRData>();
		int diffPull = -1;
		while (diffPull != 0 && data.size() < PAGE_SIZE) {
			Pageable pageable = PageRequest.of(page, PAGE_SIZE);
			List<ECRData> incomingData = ecrDataRepository
					.findByLastNameAndFirstNameAndZipCodeOrderByVersionDesc(lastName, firstName, zipCode, pageable);
			int oldSize = data.size();
			data.addAll(incomingData);
			data = lintVersionsFromECRDataList(data);
			diffPull = data.size() - oldSize;
			page = page + 1;
		}
		data = data.size() < PAGE_SIZE ? data.subList(0, data.size()) : data.subList(0, PAGE_SIZE);
		List<ECR> ecrReturnList = transformECRDataToECR(data);
		return new ResponseEntity<List<ECR>>(ecrReturnList, HttpStatus.OK);
	}

	@RequestMapping(value = "/ECR", method = RequestMethod.GET, params = { "lastName", "firstName", "diagnosisCode" })
	public ResponseEntity<List<ECR>> getECRByLastNameAndFirstNameAndDiagnosisCode(@RequestParam String lastName,
			@RequestParam String firstName, @RequestParam String diagnosisCode,
			@RequestParam(name = "page", defaultValue = "0", required = false) Integer page) {
		List<ECRData> data = new ArrayList<ECRData>();
		int diffPull = -1;
		while (diffPull != 0 && data.size() < PAGE_SIZE) {
			Pageable pageable = PageRequest.of(page, PAGE_SIZE);
			List<ECRData> incomingData = ecrDataRepository.findByLastNameAndFirstNameAndDiagnosisCodeOrderByVersionDesc(
					lastName, firstName, diagnosisCode, pageable);
			int oldSize = data.size();
			data.addAll(incomingData);
			data = lintVersionsFromECRDataList(data);
			diffPull = data.size() - oldSize;
			page = page + 1;
		}
		data = data.size() < PAGE_SIZE ? data.subList(0, data.size()) : data.subList(0, PAGE_SIZE);
		List<ECR> ecrReturnList = transformECRDataToECR(data);
		return new ResponseEntity<List<ECR>>(ecrReturnList, HttpStatus.OK);
	}

	@RequestMapping(value = "/ECR", method = RequestMethod.GET, params = { "lastName", "zipCode", "diagnosisCode" })
	public ResponseEntity<List<ECR>> getECRByLastNameAndZipCodeAndDiagnosisCode(@RequestParam String lastName,
			@RequestParam String zipCode, @RequestParam String diagnosisCode,
			@RequestParam(name = "page", defaultValue = "0", required = false) Integer page) {
		List<ECRData> data = new ArrayList<ECRData>();
		int diffPull = -1;
		while (diffPull != 0 && data.size() < PAGE_SIZE) {
			Pageable pageable = PageRequest.of(page, PAGE_SIZE);
			List<ECRData> incomingData = ecrDataRepository.findByLastNameAndZipCodeAndDiagnosisCodeOrderByVersionDesc(
					lastName, zipCode, diagnosisCode, pageable);
			int oldSize = data.size();
			data.addAll(incomingData);
			data = lintVersionsFromECRDataList(data);
			diffPull = data.size() - oldSize;
			page = page + 1;
		}
		data = data.size() < PAGE_SIZE ? data.subList(0, data.size()) : data.subList(0, PAGE_SIZE);
		List<ECR> ecrReturnList = transformECRDataToECR(data);
		return new ResponseEntity<List<ECR>>(ecrReturnList, HttpStatus.OK);
	}

	@RequestMapping(value = "/ECR", method = RequestMethod.GET, params = { "firstName", "zipCode", "diagnosisCode" })
	public ResponseEntity<List<ECR>> getECRByFirstNameAndZipCodeAndDiagnosisCode(@RequestParam String firstName,
			@RequestParam String zipCode, @RequestParam String diagnosisCode,
			@RequestParam(name = "page", defaultValue = "0", required = false) Integer page) {
		List<ECRData> data = new ArrayList<ECRData>();
		int diffPull = -1;
		while (diffPull != 0 && data.size() < PAGE_SIZE) {
			Pageable pageable = PageRequest.of(page, PAGE_SIZE);
			List<ECRData> incomingData = ecrDataRepository.findByFirstNameAndZipCodeAndDiagnosisCodeOrderByVersionDesc(
					firstName, zipCode, diagnosisCode, pageable);
			int oldSize = data.size();
			data.addAll(incomingData);
			data = lintVersionsFromECRDataList(data);
			diffPull = data.size() - oldSize;
			page = page + 1;
		}
		data = data.size() < PAGE_SIZE ? data.subList(0, data.size()) : data.subList(0, PAGE_SIZE);
		List<ECR> ecrReturnList = transformECRDataToECR(data);
		return new ResponseEntity<List<ECR>>(ecrReturnList, HttpStatus.OK);
	}

	@RequestMapping(value = "/ECR", method = RequestMethod.GET, params = { "LastName", "firstName", "zipCode",
			"diagnosisCode" })
	public ResponseEntity<List<ECR>> getECRByLastNameAndFirstNameAndZipCodeAndDiagnosisCode(
			@RequestParam String lastName, @RequestParam String firstName, @RequestParam String zipCode,
			@RequestParam String diagnosisCode,
			@RequestParam(name = "page", defaultValue = "0", required = false) Integer page) {
		List<ECRData> data = new ArrayList<ECRData>();
		int diffPull = -1;
		while (diffPull != 0 && data.size() < PAGE_SIZE) {
			Pageable pageable = PageRequest.of(page, PAGE_SIZE);
			List<ECRData> incomingData = ecrDataRepository
					.findByLastNameAndFirstNameAndZipCodeAndDiagnosisCodeOrderByVersionDesc(lastName, firstName,
							zipCode, diagnosisCode, pageable);
			int oldSize = data.size();
			data.addAll(incomingData);
			data = lintVersionsFromECRDataList(data);
			diffPull = data.size() - oldSize;
			page = page + 1;
		}
		data = data.size() < PAGE_SIZE ? data.subList(0, data.size()) : data.subList(0, PAGE_SIZE);
		List<ECR> ecrReturnList = transformECRDataToECR(data);
		return new ResponseEntity<List<ECR>>(ecrReturnList, HttpStatus.OK);
	}

	@RequestMapping(value = "/ECR", method = RequestMethod.PUT, params = "id")
	public ResponseEntity<ECR> updateECR(@RequestBody ECR ecr, @RequestParam String id) {
		String ecrId = "";
		if (id == null || id.isBlank()) {
			ecrId = ecr.getECRId();
		} else {
			ecrId = id;
		}
		ECRData data = ecrDataRepository.findByEcrIdOrderByVersionDesc(Integer.valueOf(ecrId)).get(0);
		ECRData updatingData = new ECRData(data);
		updatingData.setId(currentId.incrementAndGet());
		updatingData.update(ecr);
		ecrDataRepository.save(updatingData);
		return new ResponseEntity<ECR>(updatingData.getECR(), HttpStatus.OK);
	}

	public Connection getConnection() throws SQLException {
		return DriverManager.getConnection(connectionConfig.getUrl(), connectionConfig.getUsername(),
				connectionConfig.getPassword());
	}

	@PostConstruct
	public void setCurrentId() {
		log.info(" CONNECTION --- Setting the currentId");
		try {
			currentId = new AtomicInteger(getCurrentId());
		} catch (SQLException e) {
			currentId = new AtomicInteger(1234);
			log.warn("Error pulling the currentId");
			log.warn(e.getMessage());
		}
	}
	
	public static int getStaticCurrentId() {
		return ECRController.currentId.incrementAndGet();
	}

	public Integer getCurrentId() throws SQLException {
		log.info(" CONNECTION --- Calling get the currentId");
		log.info(" CONNECTION --- connectionURL:" + connectionConfig.getUrl());
		log.info(" CONNECTION --- connectionUsername:" + connectionConfig.getUsername());
		log.info(" CONNECTION --- connectionPassword:" + connectionConfig.getPassword());
		Connection conn;
		conn = getConnection();
		PreparedStatement runETLStatement = conn.prepareStatement(GET_CASE_REPORT_SEQ);
		ResultSet rs = runETLStatement.executeQuery();
		int returnValue = 0;
		while (rs.next()) {
			returnValue = rs.getInt("seq_id");
		}
		return returnValue;
	}

	// Lints out the old versions of ECRData
	public List<ECRData> lintVersionsFromECRDataList(List<ECRData> sourceList) {
		List<ECRData> returnList = new ArrayList<ECRData>(sourceList);
		for (ECRData a : sourceList) {
			for (ECRData b : sourceList) {
				if (a.getECRId().equals(b.getECRId())) {
					if (a.getVersion().compareTo(b.getVersion()) < 0)
						returnList.remove(a);
					else if (a.getVersion().compareTo(b.getVersion()) > 0)
						returnList.remove(b);
				}
			}
		}
		return returnList;
	}

	// Transform a list to ECR
	public List<ECR> transformECRDataToECR(List<ECRData> sourceList) {
		List<ECR> targetList = new ArrayList<ECR>();
		for (ECRData data : sourceList) {
			ECR ecr = data.getECR();
			List<ECRJob> ecrJob = ecrJobRepository.findByReportIdOrderByIdDesc(data.getECRId());
			if (!ecrJob.isEmpty()) {
				ecr.setStatus(ecrJob.get(0).getStatusCode());
			}
			targetList.add(ecr);
		}
		return targetList;
	}
}
