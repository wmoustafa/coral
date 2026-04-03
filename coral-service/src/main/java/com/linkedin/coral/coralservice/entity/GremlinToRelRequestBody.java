/**
 * Copyright 2017-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.coralservice.entity;

/**
 * Request body for Gremlin to RelNode conversion endpoint.
 */
public class GremlinToRelRequestBody {
  private String gremlinQuery;
  private String vertexTable;
  private String edgeTable;
  private String vertexIdColumn;
  private String edgeSrcColumn;
  private String edgeDstColumn;

  public GremlinToRelRequestBody() {
  }

  public GremlinToRelRequestBody(String gremlinQuery, String vertexTable, String edgeTable, String vertexIdColumn,
      String edgeSrcColumn, String edgeDstColumn) {
    this.gremlinQuery = gremlinQuery;
    this.vertexTable = vertexTable;
    this.edgeTable = edgeTable;
    this.vertexIdColumn = vertexIdColumn;
    this.edgeSrcColumn = edgeSrcColumn;
    this.edgeDstColumn = edgeDstColumn;
  }

  public String getGremlinQuery() {
    return gremlinQuery;
  }

  public void setGremlinQuery(String gremlinQuery) {
    this.gremlinQuery = gremlinQuery;
  }

  public String getVertexTable() {
    return vertexTable;
  }

  public void setVertexTable(String vertexTable) {
    this.vertexTable = vertexTable;
  }

  public String getEdgeTable() {
    return edgeTable;
  }

  public void setEdgeTable(String edgeTable) {
    this.edgeTable = edgeTable;
  }

  public String getVertexIdColumn() {
    return vertexIdColumn;
  }

  public void setVertexIdColumn(String vertexIdColumn) {
    this.vertexIdColumn = vertexIdColumn;
  }

  public String getEdgeSrcColumn() {
    return edgeSrcColumn;
  }

  public void setEdgeSrcColumn(String edgeSrcColumn) {
    this.edgeSrcColumn = edgeSrcColumn;
  }

  public String getEdgeDstColumn() {
    return edgeDstColumn;
  }

  public void setEdgeDstColumn(String edgeDstColumn) {
    this.edgeDstColumn = edgeDstColumn;
  }
}
