package edu.gatech.chai.ecr.repository;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.gatech.chai.ecr.jpa.json.ECR;
import edu.gatech.chai.ecr.jpa.json.Name;
import edu.gatech.chai.ecr.jpa.json.Patient;
import edu.gatech.chai.ecr.jpa.json.Provider;
import edu.gatech.chai.ecr.jpa.json.TypeableID;
import edu.gatech.chai.ecr.jpa.model.ECRData;
import edu.gatech.chai.ecr.jpa.model.ECRJob;
import edu.gatech.chai.ecr.jpa.repo.ECRDataRepository;
import edu.gatech.chai.ecr.jpa.repo.ECRJobRepository;

@Component
public class PollPACERTask {
	private static final Logger logger = LoggerFactory.getLogger(PollPACERTask.class);

	@Autowired
	private ECRJobRepository ecrJobRepository;

	@Autowired
	private ECRDataRepository ecrDataRepository;

	private String searchPacerIndexService(String identifier, String name) {
		String retv = null;

		RestTemplate restTemplate = new RestTemplate();
		String pacerIndexServiceUrl = System.getenv("PACER_INDEX_SERVICE");

		if (pacerIndexServiceUrl.endsWith("/")) {
			pacerIndexServiceUrl = pacerIndexServiceUrl.substring(0, pacerIndexServiceUrl.length()-1);
		}
		
		Map<String, String> vars = new HashMap<>();
		String args = null;
		if (identifier != null && !identifier.isEmpty()) {
			args = "?organization-id={orgId}";
			vars.put("orgId", identifier);
		}
		if (name != null && !name.isEmpty()) {
			if (args == null) {
				args = "?provider-name={providerName}";
			} else {
				args += "&provider-name={providerName}";
			}
			vars.put("providerName", name);
		}
		
		pacerIndexServiceUrl += args;

		ResponseEntity<String> response = restTemplate.getForEntity(pacerIndexServiceUrl, String.class, vars);
		if (response.getStatusCode().equals(HttpStatus.OK)) {
			ObjectMapper mapper = new ObjectMapper();
			JsonNode root;
			try {
				root = mapper.readTree(response.getBody());
				JsonNode countNode = root.path("count");
				int count = countNode.asInt();
				if (count > 0) {
					JsonNode list = root.path("list");
					JsonNode pacerInfo = list.get(0);
					JsonNode pacerSource = pacerInfo.path("pacerSource");
					if (pacerSource.path("type").asText().equalsIgnoreCase("ECR")) {
						return pacerSource.path("serverUrl").asText();
					}
				}
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		} else {
			logger.debug("Failed to access PACER Index Service at " + pacerIndexServiceUrl);
		}

		return retv;
	}

	private int sendPacerRequest(String pacerJobManagerEndPoint, Integer ecrId, String patientIdentifier,
			String patientFullName) {
		int retv = -1;

		// Generate request JSON

		RestTemplate restTemplate = new RestTemplate();

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);

//		Map<String, String> vars = new HashMap<>();
//		vars.put("ecrId", ecrId.toString());
//		vars.put("patientId", patientId);

		if (patientFullName == null) {
			patientFullName = "";
		}

		String[] patientParam = patientIdentifier.split("\\|", 2);
		String referenceId = null;
		if (patientParam[1] != null) {
			referenceId = patientParam[1];
		} else {
			referenceId = "";
		}
		String requestJson = "{\"name\": \"STD ECR " + ecrId
				+ "\", \"recordType\": \"ECR\", \"listType\": \"SINGLE_USE\", \"listElements\": [{\"referenceId\": \""
				+ referenceId + "\", \"name\": \"" + patientFullName + "\"}]}";
		HttpEntity<String> entity = new HttpEntity<String>(requestJson, headers);

		ResponseEntity<String> response = restTemplate.postForEntity(pacerJobManagerEndPoint, entity, String.class);
		if (response.getStatusCode().equals(HttpStatus.CREATED) || response.getStatusCode().equals(HttpStatus.OK)) {
			ObjectMapper mapper = new ObjectMapper();
			String ecrReport = null;
			try {
				ecrReport = response.getBody();
				logger.debug("updateECR received:"+ecrReport);
				
				if ("[]".equals(ecrReport.trim())) {
					logger.debug("Patient does not exist or no data found");
					return retv;
				}
				ECR ecr = mapper.readValue(ecrReport, ECR.class);

				ECRData ecrData;
				List<ECRData> ecrDatas = ecrDataRepository
						.findByEcrIdOrderByVersionDesc(Integer.valueOf(ecr.getECRId()));
				if (ecrDatas.size() == 0) {
					ecrData = new ECRData(ecr, ecrId);
				} else {
					ecrData = ecrDatas.get(0);
					ecrData.update(ecr);
				}

				ecrDataRepository.save(ecrData);
				retv = 0;
			} catch (IOException e) {
				e.printStackTrace();
				retv = -1;
			}
		}

		return retv;
	}

	@Scheduled(fixedDelay = 120000)
	public void pollPACERTaskWithFixedRate() {
		List<ECRJob> ecrJobs = ecrJobRepository.findByStatusCode("R");
		for (ECRJob ecrJob : ecrJobs) {
			Integer ecrId = ecrJob.getReportId();
			List<ECRData> ecrDataList = ecrDataRepository.findByEcrIdOrderByVersionDesc(ecrId);
			if (ecrDataList.size() == 0)
				continue;

			ECRData ecrData = ecrDataList.get(0);
			ECR ecr = ecrData.getECR();
			List<Provider> providers = ecr.getProvider();
			if (providers.size() == 0)
				continue; // We can't request without knowing a provider.

			String pacerJobManagerEndPoint = null;
			for (Provider provider : providers) {
				TypeableID providerId = provider.getid();
				String identifier = providerId.gettype() + "|" + providerId.getvalue();
				String name = provider.getname();

				// Search from pacer index service.
				pacerJobManagerEndPoint = searchPacerIndexService(identifier, name);
				if (pacerJobManagerEndPoint != null && !pacerJobManagerEndPoint.isEmpty()) {
					logger.debug("Got PACER endpoint=" + pacerJobManagerEndPoint);
					break;
				}
			}

			if (pacerJobManagerEndPoint == null || pacerJobManagerEndPoint.isEmpty()) {
				logger.debug("No PACER Job Manger Endpoint Found. Skipping ECRid: " + ecr.getECRId());
				continue;
			}

			Patient patient = ecr.getPatient();
			List<TypeableID> patientIds = patient.getid();
			String patientIdentifier = null;
			for (TypeableID patientId : patientIds) {
				String type = patientId.gettype();
				String value = patientId.getvalue();

				if (value != null && !value.isEmpty()) {
					if (type == null)
						type = "";
					patientIdentifier = type + "|" + value;
					break;
				}
			}

			// Call PACER now.
			if (patientIdentifier != null) {
				Name patientName = patient.getname();
				String patientFullName = null;
				if (patientName != null) {
					patientFullName = patientName.toString();
				}
				logger.debug("Sending PACER Request to " + pacerJobManagerEndPoint + "with ecrId=" + ecrId
						+ " and patientIdentifier=" + patientIdentifier);
				if (sendPacerRequest(pacerJobManagerEndPoint, ecrId, patientIdentifier, patientFullName) == 0) {
					ecrJob.instantUpdate();
					ecrJobRepository.save(ecrJob);
				}

			}
		}
	}
}
