/**
 * Copyright 2025-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.coralservice.controller;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.linkedin.coral.coralservice.entity.TestDataRequestBody;
import com.linkedin.coral.coralservice.entity.TestDataResponseBody;
import com.linkedin.coral.coralservice.utils.TestDataGenerationUtils;

import static com.linkedin.coral.coralservice.utils.CoralProvider.*;
import static com.linkedin.coral.coralservice.utils.TestDataGenerationUtils.*;


/**
 * REST controller for generating test data from SQL queries.
 * 
 * <p>This controller provides an endpoint to generate test data based on SQL queries
 * by analyzing predicates and constraints in the query to produce realistic test data.
 */
@RestController
@Service
@Profile({ "remoteMetastore", "default", "localMetastore" })
@CrossOrigin(origins = CORAL_SERVICE_FRONTEND_URL)
public class TestDataGenerationController implements ApplicationListener<ContextRefreshedEvent> {
  @Value("${hivePropsLocation:}")
  private String hivePropsLocation;

  @Override
  public void onApplicationEvent(ContextRefreshedEvent event) {
    try {
      initHiveMetastoreClient(hivePropsLocation);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  /**
   * Generates test data for a given SQL query.
   * 
   * @param requestBody contains the SQL query, source language (hive/trino/spark), and number of rows to generate
   * @return ResponseEntity containing the generated test data or error message
   */
  @PostMapping("/api/testdata/generate")
  public ResponseEntity generate(@RequestBody TestDataRequestBody requestBody) {
    final String sourceLanguage = requestBody.getSourceLanguage();
    final String query = requestBody.getQuery();
    final int numRows = requestBody.getNumRows();

    if (query == null || query.trim().isEmpty()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Query cannot be empty.\n");
    }

    if (numRows <= 0) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Number of rows must be greater than 0.\n");
    }

    if (numRows > 10000) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Number of rows cannot exceed 10000.\n");
    }

    if (!isValidSourceLanguage(sourceLanguage)) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body("Currently, only Hive, Trino and Spark are supported as source languages.\n");
    }

    try {
      List<Map<String, Object>> testData = TestDataGenerationUtils.generateTestData(query, sourceLanguage, numRows);
      TestDataResponseBody responseBody = new TestDataResponseBody(testData, testData.size(), query);
      return ResponseEntity.status(HttpStatus.OK).body(responseBody);
    } catch (Throwable t) {
      t.printStackTrace();
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(t.getMessage());
    }
  }
}
