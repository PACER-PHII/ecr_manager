/*
 * Filename: /Users/mc142/Documents/workspace/PACERv1/ecr_manager/src/main/java/edu/gatech/chai/ecr/jpa/repo/ECRJobRepository copy.java
 * Path: /Users/mc142/Documents/workspace/PACERv1/ecr_manager/src/main/java/edu/gatech/chai/ecr/jpa/repo
 * Created Date: Saturday, March 25th 2023, 3:32:50 pm
 * Author: Myung Choi
 * 
 * Copyright (c) 2023 GTRI - Health Emerging and Advanced Technologies (HEAT)
 */
package edu.gatech.chai.ecr.jpa.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import edu.gatech.chai.ecr.jpa.model.ECRDataHistory;

@Repository
public interface ECRDataHistoryRepository extends JpaRepository<ECRDataHistory, Integer>{
	List<ECRDataHistory> findByEcrId(Integer ecrId);
	List<ECRDataHistory> findBySourceOrderByDate(String source);
}