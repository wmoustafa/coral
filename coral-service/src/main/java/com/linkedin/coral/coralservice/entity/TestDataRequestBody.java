/**
 * Copyright 2025-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.coralservice.entity;

public class TestDataRequestBody {
  private String sourceLanguage;
  private String query;
  private int numRows;

  public String getSourceLanguage() {
    return sourceLanguage;
  }

  public String getQuery() {
    return query;
  }

  public int getNumRows() {
    return numRows;
  }
}
