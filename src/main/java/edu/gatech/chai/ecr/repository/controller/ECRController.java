package edu.gatech.chai.ecr.repository.controller;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
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

import ch.qos.logback.core.rolling.TriggeringPolicy;
import edu.gatech.chai.ecr.jpa.json.CodeableConcept;
import edu.gatech.chai.ecr.jpa.json.Diagnosis;
import edu.gatech.chai.ecr.jpa.json.ECR;
import edu.gatech.chai.ecr.jpa.json.Facility;
import edu.gatech.chai.ecr.jpa.json.ImmunizationHistory;
import edu.gatech.chai.ecr.jpa.json.LabOrderCode;
import edu.gatech.chai.ecr.jpa.json.LabResult;
import edu.gatech.chai.ecr.jpa.json.Medication;
import edu.gatech.chai.ecr.jpa.json.Name;
import edu.gatech.chai.ecr.jpa.json.ParentGuardian;
import edu.gatech.chai.ecr.jpa.json.Patient;
import edu.gatech.chai.ecr.jpa.json.Provider;
import edu.gatech.chai.ecr.jpa.json.TestResult;
import edu.gatech.chai.ecr.jpa.json.TypeableID;
import edu.gatech.chai.ecr.jpa.json.utils.AddressUtil;
import edu.gatech.chai.ecr.jpa.model.ECRData;
import edu.gatech.chai.ecr.jpa.model.ECRJob;
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
	
	@RequestMapping(value = "/ECR", method = RequestMethod.POST)
	public ResponseEntity<ECR> postNewECR(@RequestBody ECR ecr) {
		ECRData data = null;
		Name name = ecr.getPatient().getname();
		if (name != null) {
			String firstName = name.getgiven();
			String lastName = name.getfamily();
			String zipCode = AddressUtil.findZip(ecr.getPatient().getstreetAddress());
			List<ECRData> ecrs = ecrDataRepository.findByLastNameAndFirstNameAndZipCodeOrderByVersionDesc(lastName,
					firstName, zipCode, PageRequest.of(0, PAGE_SIZE));
			if (ecrs != null && ecrs.size() > 0) {
				data = ecrs.get(0);
				data.update(ecr);
			}
		}

		if (data == null) {
			data = new ECRData(ecr, currentId.incrementAndGet());
		}
		ecrDataRepository.save(data);
		
		// See if this is in the job list.
		List<ECRJob> ecrJobs = ecrJobRepository.findByReportIdOrderByIdDesc(data.getECRId());
		if (ecrJobs == null || ecrJobs.size() == 0) {
			ECRJob ecrJob = new ECRJob(data);
			ecrJob.startRun();
			
			// Add this to the job.
			ecrJobRepository.save(ecrJob);
		}
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

	@RequestMapping(value = "/exportCSV", method = RequestMethod.GET, produces = "text/csv")
	public ResponseEntity<Resource> exportCSV(
			@RequestParam(name = "page", defaultValue = "0", required = false) Integer page) {
		
		String[] csvHeader = {
			"id", "^provider_id~provider_name~phone~fax~email~facility~address~country^",
			"facility_id", "facility_name", "facility_phone", "facility_address", "facility_fax", "facility_hospital_unit",
			"patient_id", "patient_name", "^patient_parents_guardians_name~phone~email", "patient_street_address",
			"patient_birth_date", "patient_sex", "patient_patientclass", "patient_race", "patient_ethnicity",
			"patient_preferred_language", "patient_occupation", "patient_pregnant", "^patient_travel_history",
			"patient_insurance_type", "^patient_immunization_history", "patient_visit_datetime", 
			"patient_admission_datetime", "patient_date_of_onset", "^patient_symptoms", "^patient_lab_order_code",
			"patient_placer_order_code", "^patient_diagnosis", "^patient_medication_provided", "patient_death_date",
			"patient_date_discharged", "patient_laboratory_results", "^patient_trigger_code", "^patient_lab_tests_performed",
			"sendingApplication", "^notes"};

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

		List<List<String>> csv = new ArrayList<>();
		for (ECR ecr : ecrReturnList) {
			List<String> row = new ArrayList<String>();
			row.add(ecr.getId());
			String providers = "";
			for (Provider provider : ecr.getProvider()) {
				String providerData = provider.getid() + "~" + provider.getname() + "~" + provider.getphone()
					+ "~" + provider.getfax() + "~" + provider.getemail() + "~" + provider.getfacility()
					+ "~" + provider.getaddress() + "~" + provider.getcountry();

				if (providers.isBlank()) {
					providers = providerData;
				} else {
					providers = providers.concat("^" + providerData);
				}
			}
			row.add(providers);

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

			List<ParentGuardian> parentGuardians = patient.getparentsGuardians();
			String parentGs = "";
			for (ParentGuardian parentGuardian : parentGuardians) {
				if (parentGs.isBlank()) {
					parentGs = parentGuardian.getname() + "~" + parentGuardian.getphone() + "~" + parentGuardian.getemail();
				} else {
					parentGs = parentGs.concat("^" + parentGuardian.getname() + "~" + parentGuardian.getphone() + "~" + parentGuardian.getemail());
				}
			}
			row.add(parentGs);
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
			String travelHistories = "";
			for (String travelHistory : patient.gettravelHistory()) {
				if (travelHistories.isBlank()) {
					travelHistories = travelHistory;
				} else {
					travelHistories = travelHistories.concat("^" + travelHistory);
				}
			}
			row.add(travelHistories);
			row.add(patient.getinsuranceType().toString());
			String immunizationHistories = "";
			for ( ImmunizationHistory immunicationHistory : patient.getimmunizationHistory()) {
				if (immunizationHistories.isBlank()) {
					immunizationHistories = immunicationHistory.toString();
				} else {
					immunizationHistories = immunizationHistories.concat("^"+immunicationHistory.toString());
				}
			}
			row.add(immunizationHistories);
			row.add(patient.getvisitDateTime());
			row.add(patient.getadmissionDateTime());
			row.add(patient.getdateOfOnset());
			String symptoms = "";
			for (CodeableConcept symptom : patient.getsymptoms()) {
				if (symptoms.isBlank()) {
					symptoms = symptom.toString();
				} else {
					symptoms = symptoms.concat("^" + symptom.toString());
				}
			}
			row.add(symptoms);
			String labOrderCodes = "";
			for (LabOrderCode labOrderCode : patient.getlabOrderCode()) {
				if (labOrderCodes.isBlank()) {
					labOrderCodes = labOrderCode.toString();
				} else {
					labOrderCodes = labOrderCodes.concat("^" + labOrderCode.toString());
				}
			}
			row.add(labOrderCodes);
			row.add(patient.getplacerOrderCode());
			String diagnoses = "";
			for (Diagnosis diagnosis : patient.getDiagnosis()) {
				if (diagnoses.isBlank()) {
					diagnoses = diagnosis.toString();
				} else {
					diagnoses = diagnoses.concat("^" + diagnosis.toString());
				}
			}
			row.add(diagnoses);
			String medications = "";
			for (Medication medicationProvided : patient.getMedicationProvided()) {
				if (medications.isBlank()) {
					medications = medicationProvided.toString();
				} else {
					medications = medications.concat("^" + medicationProvided.toString());
				}
			}
			row.add(medications);
			row.add(patient.getdeathDate());
			row.add(patient.getdateDischarged());
			String labResults = "";
			for (LabResult labResult : patient.getlaboratoryResults()) {
				if (labResults.isBlank()) {
					labResults = labResult.toString();
				} else {
					labResults = labResults.concat("^" + labResult.toString());
				}
			}
			row.add(labResults);
			String triggerCodes = "";
			for (CodeableConcept triggerCode : patient.gettriggerCode()) {
				if (triggerCodes.isBlank()) {
				 	triggerCodes = triggerCodes.toString();
				} else {
					triggerCodes = triggerCodes.concat("^" + triggerCodes.toString());
				}
			}
			row.add(triggerCodes);
			String labTestsPerformeds = "";
			for (TestResult labTestsPerformed : patient.getlabTestsPerformed()) {
				if (labTestsPerformeds.isBlank()) {
					labTestsPerformeds = labTestsPerformed.toString();
				} else {
					labTestsPerformeds = labTestsPerformeds.concat("^" + labTestsPerformed.toString());
				}
			}
			row.add(labTestsPerformeds);
			row.add(ecr.getSendingApplication());
			String notes = "";
			for (String note : ecr.getNotes()) {
				if (notes.isBlank()) {
					notes = note;
				} else {
					notes = notes.concat("^" + note);
				}
			}
			row.add(notes);

			csv.add(row);	
		}

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

	    String csvFileName = "ecr.csv";

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
			targetList.add(data.getECR());
		}
		return targetList;
	}
}
