package edu.gatech.chai.ecr.jpa.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import edu.gatech.chai.ecr.jpa.model.ECRJob;

@Repository
public interface ECRJobRepository extends JpaRepository<ECRJob, Integer>{
	List<ECRJob> findByReportIdOrderByIdDesc(Integer reportId);
	List<ECRJob> findByStatusCode(String statusCode);
	List<ECRJob> findByPatientIdContainingIgnoreCase(String stringPatientId);
}