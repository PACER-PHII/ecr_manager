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

		List<String> csvHeaderList = new ArrayList<String>();

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
		int totalEcrSize = ecrReturnList.size();

		// First find the size of columns. As some ECR may have multiple entries, we need walk over all of lists.
		int numOfColumns = 0;
		int maxNumOfProviders = 0;
		for (ECR ecr : ecrReturnList) {
			int numOfProviders = ecr.getProvider().size();
			if (numOfProviders > maxNumOfProviders) {
				maxNumOfProviders = numOfProviders;
			}


		}
		List<String>[] rows = new ArrayList[totalEcrSize];
		for (int i = 0; i < totalEcrSize; i++) {
			rows[i] = new ArrayList<String>();
		}
		for (ECR ecr : ecrReturnList) {
			List<String> row = new ArrayList<String>();
			row.add(ecr.getId());

			int index = 1;
			for (Provider provider : ecr.getProvider()) {
				csvHeaderList.add("provider_id_" + index);
				row.add(ECRData.stringPatientId(provider.getid()));

				csvHeaderList.add("provider_name_" + index);
				row.add(provider.getname());
				
				csvHeaderList.add("provider_phone_" + index);
				row.add(provider.getphone());

				csvHeaderList.add("provider_fax_" + index);
				row.add(provider.getfax());

				csvHeaderList.add("provider_email_" + index);
				row.add(provider.getemail());

				csvHeaderList.add("provider_facility_" + index);
				row.add(provider.getfacility());

				csvHeaderList.add("provider_address_" + index);
				row.add(provider.getaddress());
				
				csvHeaderList.add("provider_country_" + index);
				row.add(provider.getcountry());

				index++;
			}

			Facility facility = ecr.getFacility();
			csvHeaderList.add("facility_id");
			row.add(facility.getid());
			
			csvHeaderList.add("facility_name");
			row.add(facility.getname());

			csvHeaderList.add("facility_phone");
			row.add(facility.getphone());

			csvHeaderList.add("facility_address");
			row.add(facility.getaddress());

			csvHeaderList.add("facility_fax");
			row.add(facility.getfax());

			csvHeaderList.add("facility_hospital_unit");
			row.add(facility.gethospitalUnit());

			Patient patient = ecr.getPatient();
			String idStr = ECRData.stringPatientIds(patient.getid());

			csvHeaderList.add("patient_id");
			row.add(idStr);

			csvHeaderList.add("patient_name");
			row.add(patient.getname().toString());

			List<ParentGuardian> parentGuardians = patient.getparentsGuardians();
			String parentGs = "";
			index = 1;
			for (ParentGuardian parentGuardian : parentGuardians) {
				csvHeaderList.add("patient_guardian_name_" + index);
				row.add(parentGuardian.getname().toString());

				csvHeaderList.add("patient_guardian_phone_" + index);
				row.add(parentGuardian.getphone());

				csvHeaderList.add("patient_guardian_email_" + index);
				row.add(parentGuardian.getemail());	
				
				index++;
			}

			csvHeaderList.add("patient_address");
			row.add(patient.getstreetAddress());

			csvHeaderList.add("patient_brithDate");
			row.add(patient.getbirthDate());

			csvHeaderList.add("patient_sex");
			row.add(patient.getsex());

			csvHeaderList.add("patient_patientClass");
			row.add(patient.getpatientClass());

			csvHeaderList.add("patient_race");
			row.add(patient.getrace().toString());

			csvHeaderList.add("patient_ethnicity");
			row.add(patient.getethnicity().toString());

			csvHeaderList.add("patient_preferredLanguage");
			row.add(patient.getpreferredLanguage().toString());

			csvHeaderList.add("patient_occupation");
			row.add(patient.getoccupation());

			csvHeaderList.add("patient_pregnant");
			if (patient.ispregnant()) {
				row.add("true");
			} else {
				row.add("false");
			}

			index = 1;
			for (String travelHistory : patient.gettravelHistory()) {
				csvHeaderList.add("patient_travelHistory_" + index);
				row.add(travelHistory);

				index++;
			}

			csvHeaderList.add("patient_insuranceType");
			row.add(patient.getinsuranceType().toString());

			index = 1;
			for ( ImmunizationHistory immunizationHistory : patient.getimmunizationHistory()) {
				csvHeaderList.add("patient_immunizationHistory_" + index);
				row.add(immunizationHistory.toString());

				index++;
			}

			csvHeaderList.add("patient_visitDateTime");
			row.add(patient.getvisitDateTime());

			csvHeaderList.add("patient_admissionDateTime");
			row.add(patient.getadmissionDateTime());

			csvHeaderList.add("patient_dateOfOnset");
			row.add(patient.getdateOfOnset());
			
			index = 1;
			for (CodeableConcept symptom : patient.getsymptoms()) {
				csvHeaderList.add("patient_symptom_index" + index);
				row.add(symptom.toString());

				index++;
			}

			index = 1;
			for (LabOrderCode labOrderCode : patient.getlabOrderCode()) {
				csvHeaderList.add("patient_labOrderCode");
				row.add(labOrderCode.toString());

				index++;
			}

			csvHeaderList.add("patient_placerOrderCode");
			row.add(patient.getplacerOrderCode());

			index =1;
			for (Diagnosis diagnosis : patient.getDiagnosis()) {
				csvHeaderList.add("patient_diagnosis_" + index);
				row.add(diagnosis.toString());

				index++;
			}

			index = 1;
			for (Medication medicationProvided : patient.getMedicationProvided()) {
				csvHeaderList.add("patient_medicationProvided_" + index);
				row.add(medicationProvided.toString());

				index++;
			}

			csvHeaderList.add("patient_deathDate");
			row.add(patient.getdeathDate());

			csvHeaderList.add("patient_dateDischarged");
			row.add(patient.getdateDischarged());

			index = 1;
			for (LabResult labResult : patient.getlaboratoryResults()) {
				csvHeaderList.add("patient_laboratoryResult_" + index);
				row.add(labResult.toString());

				index++;
			}

			index = 1;
			for (CodeableConcept triggerCode : patient.gettriggerCode()) {
				csvHeaderList.add("patient_triggerCode_" + index);
				row.add(triggerCode.toString());

				index++;
			}

			index = 1;
			for (TestResult labTestsPerformed : patient.getlabTestsPerformed()) {
				csvHeaderList.add("patient_labTestsPerformed_" + index);
				row.add(labTestsPerformed.toString());

				index++;
			}

			csvHeaderList.add("sendingApplication");
			row.add(ecr.getSendingApplication());

			index = 1;
			for (String note : ecr.getNotes()) {
				csvHeaderList.add("note_" + index);
				row.add(note);
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
