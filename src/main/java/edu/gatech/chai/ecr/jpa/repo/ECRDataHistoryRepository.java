/*
 * Filename: /Users/mc142/Documents/workspace/PACERv1/ecr_manager/src/main/java/edu/gatech/chai/ecr/jpa/repo/ECRJobRepository copy.java
 * Path: /Users/mc142/Documents/workspace/PACERv1/ecr_manager/src/main/java/edu/gatech/chai/ecr/jpa/repo
 * Created Date: Saturday, March 25th 2023, 3:32:50 pm
 * Author: Myung Choi
 * 
 * Copyright (c) 2023 GTRI - Health Emerging and Advanced Technologies (HEAT)
 */
package edu.gatech.chai.ecr.jpa.repo;

import java.util.Date;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import edu.gatech.chai.ecr.jpa.model.ECRDataHistory;

@Repository
public interface ECRDataHistoryRepository extends JpaRepository<ECRDataHistory, Integer>{
	List<ECRDataHistory> findByEcrId(Integer ecrId);
	List<ECRDataHistory> findBySourceOrderByDate(String source);
	List<ECRDataHistory> findByDateOrderByDate(Date date);

	List<ECRDataHistory> findByEcrIdAndSourceOrderByDate(Integer ecrId, String source);
	List<ECRDataHistory> findByEcrIdAndSourceAndDateOrderByDate(Integer ecrId, String source, Date date);
	List<ECRDataHistory> findByEcrIdAndSourceAndDateLessThanOrderByDate(Integer ecrId, String source, Date date);
	List<ECRDataHistory> findByEcrIdAndSourceAndDateLessThanEqualOrderByDate(Integer ecrId, String source, Date date);
	List<ECRDataHistory> findByEcrIdAndSourceAndDateGreaterThanOrderByDate(Integer ecrId, String source, Date date);
	List<ECRDataHistory> findByEcrIdAndSourceAndDateGreaterThanEqualOrderByDate(Integer ecrId, String source, Date date);

	List<ECRDataHistory> findByEcrIdAndDateOrderByDate(Integer ecrId, Date date);
	List<ECRDataHistory> findByEcrIdAndDateLessThanOrderByDate(Integer ecrId, Date date);
	List<ECRDataHistory> findByEcrIdAndDateLessThanEqualOrderByDate(Integer ecrId, Date date);
	List<ECRDataHistory> findByEcrIdAndDateGreaterThanOrderByDate(Integer ecrId, Date date);
	List<ECRDataHistory> findByEcrIdAndDateGreaterThanEqualOrderByDate(Integer ecrId, Date date);

	List<ECRDataHistory> findBySourceAndDateOrderByDate(String source, Date date);
	List<ECRDataHistory> findBySourceAndDateLessThanOrderByDate(String source, Date date);
	List<ECRDataHistory> findBySourceAndDateLessThanEqualOrderByDate(String source, Date date);
	List<ECRDataHistory> findBySourceAndDateGreaterThanOrderByDate(String source, Date date);
	List<ECRDataHistory> findBySourceAndDateGreaterThanEqualOrderByDate(String source, Date date);

	List<ECRDataHistory> findByDateLessThanOrderByDate(Date date);
	List<ECRDataHistory> findByDateLessThanEqualOrderByDate(Date date);
	List<ECRDataHistory> findByDateGreaterThanOrderByDate(Date date);
	List<ECRDataHistory> findByDateGreaterThanEqualOrderByDate(Date date);
}