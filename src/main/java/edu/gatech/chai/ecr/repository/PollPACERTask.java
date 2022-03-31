package edu.gatech.chai.ecr.repository;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.codec.binary.Base64;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencsv.CSVParser;

import edu.gatech.chai.ecr.jpa.json.ECR;
import edu.gatech.chai.ecr.jpa.json.LabOrderCode;
import edu.gatech.chai.ecr.jpa.json.LabResult;
import edu.gatech.chai.ecr.jpa.json.Name;
import edu.gatech.chai.ecr.jpa.json.Patient;
import edu.gatech.chai.ecr.jpa.json.Provider;
import edu.gatech.chai.ecr.jpa.json.TypeableID;
import edu.gatech.chai.ecr.jpa.model.ECRData;
import edu.gatech.chai.ecr.jpa.model.ECRJob;
import edu.gatech.chai.ecr.jpa.repo.ECRDataRepository;
import edu.gatech.chai.ecr.jpa.repo.ECRJobRepository;
import edu.gatech.chai.ecr.repository.controller.ECRController;

@Component
public class PollPACERTask {
	private static final Logger logger = LoggerFactory.getLogger(PollPACERTask.class);
	ObjectMapper mapper = new ObjectMapper();

	@Autowired
	private ECRJobRepository ecrJobRepository;

	@Autowired
	private ECRDataRepository ecrDataRepository;

	private JsonNode searchPacerIndexService(String identifier, String name) {
		RestTemplate restTemplate = new RestTemplate();
		String pacerIndexServiceUrl = System.getenv("PACER_INDEX_SERVICE");

		if (pacerIndexServiceUrl.endsWith("/")) {
			pacerIndexServiceUrl = pacerIndexServiceUrl.substring(0, pacerIndexServiceUrl.length() - 1);
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
//			ObjectMapper mapper = new ObjectMapper();
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
						return pacerSource; // pacerSource.path("serverUrl").asText();
					}
				}
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		} else {
			logger.debug("Failed to access PACER Index Service at " + pacerIndexServiceUrl);
		}

		return null;
	}

	private int sendPacerRequest(String pacerJobManagerEndPoint, JsonNode query) {
		int retv = -1;

		CloseableHttpClient httpClient = HttpClients.custom().setSSLHostnameVerifier(new NoopHostnameVerifier()).build();
		HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory();
		requestFactory.setHttpClient(httpClient);

		// Generate request JSON
		RestTemplate restTemplate;
		if ("true".equalsIgnoreCase(System.getenv("TRUST_CERT"))) {
			restTemplate = new RestTemplate(requestFactory);
		} else {
			restTemplate = new RestTemplate();
		}
		
		JsonNode security = query.get("security");
		String authHeader = null;
		if (security != null) {
			authHeader = security.textValue();
		}

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		if (authHeader != null && !authHeader.isEmpty()) {
			headers.add("Authorization", authHeader);
		}

//		Map<String, String> vars = new HashMap<>();
//		vars.put("ecrId", ecrId.toString());
//		vars.put("patientId", patientId);

//		if (patientFullName == null) {
//			patientFullName = "";
//		}

//		String[] patientParam = patientIdentifier.split("\\|", 2);
//		String referenceId = null;
//		if (patientParam[1] != null) {
//			referenceId = patientParam[1];
//		} else {
//			referenceId = "";
//		}
//		String referenceId = patientIdentifier;
//		String requestJson = "{\"name\": \"STD_ECR_" + ecrId
//				+ "\", \"jobType\": \"ECR\", \"listElements\": [{\"referenceId\": \"" + referenceId + "\", \"name\": \""
//				+ patientFullName + "\"}]}";

		DateFormat dateFormat = new SimpleDateFormat("yyyyMMddHHmmss");
		Date date = new Date();
		JsonNode requestJson = mapper.createObjectNode();
		((ObjectNode) requestJson).put("name", "STD_ECR_" + dateFormat.format(date));
		((ObjectNode) requestJson).put("jobType", "ECR");
		((ObjectNode) requestJson).set("listElements", query.get("patients"));
//				
//				
//				"{\"name\": \"STD_ECR_"+dateFormat.format(date)+"\", \"jobType\": \"ECR\", \"listElements\": ";
//		requestJson.concat(query.get("patients").);
//		requestJson.concat("}");

		HttpEntity<JsonNode> entity = new HttpEntity<JsonNode>(requestJson, headers);

		ResponseEntity<String> response = restTemplate.postForEntity(pacerJobManagerEndPoint, entity, String.class);
		if (response.getStatusCode().equals(HttpStatus.CREATED) || response.getStatusCode().equals(HttpStatus.OK)) {
//			ObjectMapper mapper = new ObjectMapper();
			String ecrReport = null;
			try {
				ecrReport = response.getBody();
				logger.debug("updateECR received:" + ecrReport);

				if ("[]".equals(ecrReport.trim())) {
					logger.debug("Patient does not exist or no data found");
					return retv;
				}
//				ECR ecr = mapper.readValue(ecrReport, ECR.class);
				List<ECR> ecrs = mapper.readValue(ecrReport,
						mapper.getTypeFactory().constructCollectionType(List.class, ECR.class));

				for (ECR ecr : ecrs) {
					// First search ecr data to see if we have the matching patient using
					// received patient id.
					List<ECRData> ecrDatas = new ArrayList<ECRData>();

					Patient patient = ecr.getPatient();
					String patientIdentifier = null;
					if (patient != null) {
						// See if we can find a ECR record for this patient using
						// patient ID.
						List<TypeableID> ids = patient.getid();
						for (TypeableID pid : ids) {
							String tempPatientIdentifier = ECRData.stringPatientId(pid);
							ecrDatas = ecrDataRepository.findByPatientIdsContainingIgnoreCase(tempPatientIdentifier);
							if (ecrDatas.size() > 0) {
								logger.debug("ECR Data found with patientIDs (" + tempPatientIdentifier + ") in ECR");
								patientIdentifier = tempPatientIdentifier;
								break;
							}
						}
					}

					ECRData ecrData;
					if (ecrDatas.size() == 0 && ecr.getECRId() != null) {
						ecrDatas = ecrDataRepository.findByEcrIdOrderByVersionDesc(Integer.valueOf(ecr.getECRId()));
						if (ecrDatas.size() > 0) {
							logger.debug("ECR Data found with requested ecrId (" + ecr.getECRId() + ") in ECR");
						} else {
							ecrData = new ECRData(ecr, Integer.valueOf(ecr.getECRId()));
						}
					}

					if (ecrDatas.size() == 0) {
						// Couldn't locate this data.. and couldn't create a new one... skip over...
						continue;
					}

					if (ecrDatas.size() > 1) {
						logger.warn("Multiple (" + ecrDatas.size()
								+ ") ECR Data sets detected for query.\nFirst entry will be used.");
					}
					ecrData = ecrDatas.get(0);
					ecrData.update(ecr);
//					}

					ecrDataRepository.save(ecrData);

					// Ok, now we update job entry.
					ECRJob ecrJob = null;
					if (patientIdentifier != null) {
						List<ECRJob> ecrJobs = ecrJobRepository.findByPatientIdContainingIgnoreCase(patientIdentifier);
						if (ecrJobs.size() > 0) {
							ecrJob = ecrJobs.get(0);
						}
					}

					if (ecrJob == null) {
						List<ECRJob> ecrJobs = ecrJobRepository
								.findByReportIdOrderByIdDesc(Integer.valueOf(ecr.getECRId()));
						if (ecrJobs.size() > 0) {
							ecrJob = ecrJobs.get(0);
						}
					}

					if (ecrJob != null) {
						ecrJob.instantUpdate();
						ecrJobRepository.save(ecrJob);
					}
				}

				retv = 0;
			} catch (IOException e) {
				e.printStackTrace();
				retv = -1;
			}
		}

		return retv;
	}

	@Scheduled(fixedDelay = 60000)
	public void readBulkDataFromFile() {
		CSVParser parser = new CSVParser();
		String localFilePath = System.getenv("LOCAL_BULKDATA_PATH");
		String localPacerUrl = System.getenv("LOCAL_PACER_URL");
//		String localPacerSecurity = System.getenv("LOCAL_PACER_SECURITY");

		if (localPacerUrl == null || localPacerUrl.isEmpty()) {
			return;
		}

		if (localFilePath != null && !localFilePath.trim().isEmpty() && !"none".equalsIgnoreCase(localFilePath)) {
			logger.debug("LocalMappingFilePath is set to " + localFilePath);
			Path path = Paths.get(localFilePath);

			if (!Files.exists(path)) {
				try {
					path = Files.createDirectory(path);
				} catch (FileAlreadyExistsException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
					return;
				}
			}

			// get the list of files in this path.
			BufferedReader reader = null;
			try (Stream<Path> walk = Files.walk(path)) {
				List<String> result = walk.filter(Files::isRegularFile).map(x -> x.toString())
						.collect(Collectors.toList());

				for (String aFile : result) {
					reader = new BufferedReader(new FileReader(aFile));
					String line = reader.readLine();
					int i = 0;

					while (line != null) {
						String line_ = line.trim();
						if (line_.isEmpty() || line_.startsWith("#") || line_.charAt(1) == '#') {
							// This is comment line skip...
							line = reader.readLine();
							continue;
						}

						String[] parsedLine = parser.parseLine(line_);
						// First item must be patient identifier system type. The second one
						// must be the value.
						i++;
						if (parsedLine.length < 2) {
							logger.warn("Line #" + i + " has not enough data (" + line_ + "). Skipping.");
							line = reader.readLine();
							continue;
						}

						String patientIdentifier = (parsedLine[0] + "|" + parsedLine[1]).trim();

						ECR ecr = null;
						List<ECRData> ecrs = ecrDataRepository.findByPatientIdsContainingIgnoreCase(patientIdentifier);
						Integer id = null;
						ECRData ecrData = null;

						Provider provider = new Provider();
						provider.setname("LOCAL PROVIDER");
						TypeableID providerTId = new TypeableID();
						providerTId.settype("LOCAL_PROVIDER");
						providerTId.setvalue("1");
						provider.setid(providerTId);

						Patient patient;
						
						ecr = new ECR();
						patient = new Patient();
						TypeableID patientTId = new TypeableID();
						patientTId.settype(parsedLine[0]);
						patientTId.setvalue(parsedLine[1]);
						patient.setid(Arrays.asList(patientTId));
						ecr.setPatient(patient);
						ecr.setProvider(Arrays.asList(provider));

						if (parsedLine.length > 2) {
							// We have LOINC code to populate initial lab data.
							for (int j=2; j < parsedLine.length; j++) {
								LabResult labResult = new LabResult();
								String[] loincLine = parsedLine[j].split("\\^");
								
								if (loincLine.length > 2) {
									labResult.setDate(loincLine[2]);
								}
								
								if (loincLine.length > 1) {
									labResult.setdisplay(loincLine[1]);
								}
								
								labResult.setcode(loincLine[0]);
								labResult.setsystem("LN");
								
								LabOrderCode labOrder = new LabOrderCode();
								labOrder.getLaboratory_Results().add(labResult);
								patient.getlabOrderCode().add(labOrder);
								patient.getlaboratoryResults().add(labResult);
 							}
						}
						
						if (ecrs.size() == 0) {
							id = ECRController.getStaticCurrentId();
							ecr.setECRId(Integer.toString(id));
							ecrData = new ECRData(ecr, id);
						} else {
							ecrData = ecrs.get(0);
							ecrData.update(ecr);
							
//							ecr = ecrData.getECR();
//							id = Integer.getInteger(ecr.getECRId());
//							patient = ecr.getPatient();
//							if (ecr.getProvider().size() > 0) {
//								provider = ecr.getProvider().get(0);
//							} else {
//								ecr.setProvider(Arrays.asList(provider));
//							}
						}

//						TypeableID patientTId = new TypeableID();
//						patientTId.settype(parsedLine[0]);
//						patientTId.setvalue(parsedLine[1]);
//						patient.setid(Arrays.asList(patientTId));
//
//						Provider provider = new Provider();
//						provider.setname("LOCAL PROVIDER");
//						TypeableID providerTId = new TypeableID();
//						providerTId.settype("LOCAL_PROVIDER");
//						providerTId.setvalue("1");
//						provider.setid(providerTId);

//						if (ecrs.size() == 0) {
//							id = ECRController.getStaticCurrentId();
//							ecr = new ECR();
//							ecr.setECRId(Integer.toString(id));
//						} else {
//							ecrData = ecrs.get(0);
//							ecr = ecrData.getECR();
//							id = Integer.getInteger(ecr.getECRId());
//						}


						

//						ecr.setPatient(patient);
//						ecr.setProvider(Arrays.asList(provider));

//						if (ecrData == null) {
//							ecrData = new ECRData(ecr, id);
//						}

						ecrDataRepository.save(ecrData);

						List<ECRJob> ecrJobs = ecrJobRepository.findByReportIdOrderByIdDesc(ecrData.getId());
						ECRJob ecrJob;
						if (ecrJobs == null || ecrJobs.size() == 0) {
							ecrJob = new ECRJob(ecrData);
						} else {
							ecrJob = ecrJobs.get(0);
						}
						ecrJob.startRun();

						// Add this to the job.
						ecrJobRepository.save(ecrJob);

						// read next line
						line = reader.readLine();

					} // while
					reader.close();
					Files.deleteIfExists(Paths.get(aFile));
				}

			} catch (IOException e) {
				e.printStackTrace();
			}

		}
	}

	@Scheduled(fixedDelay = 120000)
	public void pollPACERTaskWithFixedRate() {
		List<ECRJob> ecrJobs = ecrJobRepository.findByStatusCode("R");

		// Create a map that contains each job.
		Map<String, JsonNode> ecrQueriesToSend = new HashMap<String, JsonNode>();
		//
		// Run through ecrJobs and aggregate them based on the PACER destinations to Map
		// with the following JSON object.
		// {"security": "Basic ###",
		// "patients": [{"referenceId": "type|id", "name": "full name", "ecrId":
		// "localEcrId"}, ...]
		// }
		for (ECRJob ecrJob : ecrJobs) {
			// It's confusing... ReportId in ECR Job is a primary key ID in ECR Data.
			Integer ecrDataKeyId = ecrJob.getReportId();
			Optional<ECRData> ecrDataOptional = ecrDataRepository.findById(ecrDataKeyId);
			
			ECRData ecrData = null;
			if (ecrDataOptional.isPresent()) {
				ecrData = ecrDataOptional.get();
			}

			if (ecrData == null) {
				logger.warn("No ECR Data for the outstanding ECR Job (" + ecrDataKeyId + ")");
				continue;
			}

			Integer ecrId = ecrData.getECRId();

			// We should have only one ecrData per ecrJob. The JpaRepository returns list.
			// So, we get the 0th entry.
//			ECRData ecrData = ecrDataList.get(0);
			ECR ecr = ecrData.getECR();
			List<Provider> providers = ecr.getProvider();
			if (providers.isEmpty()) {
				logger.warn("No Providers for the outstanding ECR Job (" + ecrDataKeyId + ")");
				continue; // We can't request without knowing a provider.
			}

			JsonNode pacerSource = null;
			String pacerJobManagerEndPoint = null;
			String authHeader = null;
			for (Provider provider : providers) {
				TypeableID providerId = provider.getid();
//				if ("LOCAL".equals(providerId.gettype())) {
//					String[] endpoint_info = providerId.getvalue().split("\\^");
//					pacerJobManagerEndPoint = endpoint_info[0];
//					if (endpoint_info.length == 2) {
//						String authInfo = endpoint_info[1].trim();
//						String[] authEntry = authInfo.split(" ");
//						if (authEntry.length == 2) {
//							byte[] encodedAuth = Base64
//									.encodeBase64(authEntry[1].getBytes(StandardCharsets.ISO_8859_1));
//							authHeader = authEntry[0] + " " + new String(encodedAuth);
//						}
//					}
//					break;
//				}
				String identifier = providerId.gettype() + "|" + providerId.getvalue();
				String name = provider.getname();

				// Search from pacer index service.
				pacerSource = searchPacerIndexService(identifier, name);
				if (pacerSource != null) {
					pacerJobManagerEndPoint = pacerSource.path("serverUrl").asText();
					logger.debug("Got PACER endpoint=" + pacerJobManagerEndPoint);
					break;
				}
			}

			if (pacerJobManagerEndPoint == null || pacerJobManagerEndPoint.isEmpty()) {
				logger.debug("No PACER Job Manger Endpoint Found. Skipping ECRid: " + ecr.getECRId());
				continue;
			}

			// Now we got a valid end point. We need to create an entry for the Map with a
			// PACER destination
			// as a key.
			JsonNode ecrQuery = ecrQueriesToSend.get(pacerJobManagerEndPoint);
			if (ecrQuery == null) {
				ecrQuery = mapper.createObjectNode();
			}

			if (authHeader == null && pacerSource != null) {
				JsonNode securityJson = pacerSource.path("security");
				if (securityJson.isMissingNode() == false) {
					String authType = securityJson.get("type").asText();
					if ("basic".equalsIgnoreCase(authType)) {
						String credential = securityJson.get("username").asText() + ":"
								+ securityJson.get("password").asText();
						byte[] encodedAuth = Base64.encodeBase64(credential.getBytes(StandardCharsets.ISO_8859_1));
						authHeader = "Basic " + new String(encodedAuth);
//						((ObjectNode) ecrQuery).put("security", authHeader);
					}
				}
			} 
			
			if (authHeader != null && !authHeader.isEmpty()){
				((ObjectNode) ecrQuery).put("security", authHeader);
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
					patientIdentifier = ECRData.stringPatientId(patientId);

					if (type.startsWith("http:") || type.startsWith("urn:") || type.startsWith("oid:"))
						break;
				}
			}

			JsonNode patientNodeArray;
			try {
				patientNodeArray = ecrQuery.withArray("patients");
			} catch (UnsupportedOperationException e) {
				((ObjectNode) ecrQuery).putArray("patients");
				patientNodeArray = ecrQuery.get("patients");
			}

			// Create patient node
			JsonNode patientNode = mapper.createObjectNode();
			if (patientIdentifier != null) {
				((ObjectNode) patientNode).put("recordId", ecrId);
				((ObjectNode) patientNode).put("referenceId", patientIdentifier);

				Name patientName = patient.getname();
				String patientFullName = null;
				if (patientName != null) {
					if (!patientName.getfamily().isEmpty() || !patientName.getgiven().isEmpty()) {
						patientFullName = patientName.toString();
					} else {
						patientFullName = "";
					}
					((ObjectNode) patientNode).put("name", patientFullName);
				}

				((ObjectNode) patientNode).put("labOrderDate", "2019-10-10");

				((ArrayNode) patientNodeArray).add(patientNode);
//				ecrList.add("{\"referenceId\": \"" + patientIdentifier + "\", \"name\": \""
//						+ patientFullName + "\"}");

				ecrQueriesToSend.put(pacerJobManagerEndPoint, ecrQuery);
			}
		}

		for (Map.Entry<String, JsonNode> entry : ecrQueriesToSend.entrySet()) {
			System.out.println("PACER url : " + entry.getKey() + " Value : " + entry.getValue().toString());
			logger.debug("Sending PACER Request to " + entry.getKey());
			sendPacerRequest(entry.getKey(), entry.getValue());
//					ecrJob.instantUpdate();
//					ecrJobRepository.save(ecrJob);
//			}
		}
	}
}
