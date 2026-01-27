/**
 * Copyright 2025-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.coralservice.entity;

import java.util.List;
import java.util.Map;


public class TestDataResponseBody {
  private List<Map<String, Object>> testData;
  private int numRows;
  private String query;

  public TestDataResponseBody(List<Map<String, Object>> testData, int numRows, String query) {
    this.testData = testData;
    this.numRows = numRows;
    this.query = query;
  }

  public List<Map<String, Object>> getTestData() {
    return testData;
  }

  public int getNumRows() {
    return numRows;
  }

  public String getQuery() {
    return query;
  }
}
