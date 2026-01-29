/**
 * Copyright 2017-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.coralservice.entity;

/**
 * Response body for Gremlin to RelNode and Spark SQL conversion endpoint.
 */
public class GremlinToRelResponseBody {
  private String gremlinQuery;
  private String relNode;
  private String sparkSql;
  private boolean success;

  public GremlinToRelResponseBody() {
  }

  public GremlinToRelResponseBody(String gremlinQuery, String relNode, String sparkSql, boolean success) {
    this.gremlinQuery = gremlinQuery;
    this.relNode = relNode;
    this.sparkSql = sparkSql;
    this.success = success;
  }

  public String getGremlinQuery() {
    return gremlinQuery;
  }

  public void setGremlinQuery(String gremlinQuery) {
    this.gremlinQuery = gremlinQuery;
  }

  public String getRelNode() {
    return relNode;
  }

  public void setRelNode(String relNode) {
    this.relNode = relNode;
  }

  public String getSparkSql() {
    return sparkSql;
  }

  public void setSparkSql(String sparkSql) {
    this.sparkSql = sparkSql;
  }

  public boolean isSuccess() {
    return success;
  }

  public void setSuccess(boolean success) {
    this.success = success;
  }
}
