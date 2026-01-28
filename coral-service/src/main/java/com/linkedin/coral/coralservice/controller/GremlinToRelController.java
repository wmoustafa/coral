/**
 * Copyright 2017-2024 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.coralservice.controller;

import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
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

import com.linkedin.coral.coralservice.entity.GremlinToRelRequestBody;
import com.linkedin.coral.coralservice.entity.GremlinToRelResponseBody;
import com.linkedin.coral.gremlin.gremlin2rel.GremlinToRelConverter;

import static com.linkedin.coral.coralservice.utils.CoralProvider.*;


/**
 * REST controller for converting Gremlin queries to Coral IR (RelNode).
 * 
 * <p>This controller provides an endpoint to convert Apache TinkerPop Gremlin graph
 * traversal queries into Calcite RelNode representation (Coral IR), which can then
 * be converted to various SQL dialects.
 */
@RestController
@Service
@Profile({ "remoteMetastore", "default", "localMetastore" })
@CrossOrigin(origins = CORAL_SERVICE_FRONTEND_URL)
public class GremlinToRelController implements ApplicationListener<ContextRefreshedEvent> {
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
   * Converts a Gremlin query to Coral IR (RelNode).
   * 
   * @param requestBody contains the Gremlin query and table/column configuration
   * @return ResponseEntity containing the RelNode string representation or error message
   */
  @PostMapping("/api/gremlin/convert")
  public ResponseEntity convert(@RequestBody GremlinToRelRequestBody requestBody) {
    final String gremlinQuery = requestBody.getGremlinQuery();
    final String vertexTable = requestBody.getVertexTable();
    final String edgeTable = requestBody.getEdgeTable();
    final String vertexIdColumn = requestBody.getVertexIdColumn();
    final String edgeSrcColumn = requestBody.getEdgeSrcColumn();
    final String edgeDstColumn = requestBody.getEdgeDstColumn();

    // Validate required fields
    if (gremlinQuery == null || gremlinQuery.trim().isEmpty()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Gremlin query cannot be empty.\n");
    }

    if (vertexTable == null || vertexTable.trim().isEmpty()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Vertex table cannot be empty.\n");
    }

    if (edgeTable == null || edgeTable.trim().isEmpty()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Edge table cannot be empty.\n");
    }

    if (vertexIdColumn == null || vertexIdColumn.trim().isEmpty()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Vertex ID column cannot be empty.\n");
    }

    if (edgeSrcColumn == null || edgeSrcColumn.trim().isEmpty()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Edge source column cannot be empty.\n");
    }

    if (edgeDstColumn == null || edgeDstColumn.trim().isEmpty()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Edge destination column cannot be empty.\n");
    }

    try {
      // Create converter with hiveMetastoreClient directly
      GremlinToRelConverter converter = new GremlinToRelConverter(
          hiveMetastoreClient,
          vertexTable,
          edgeTable,
          vertexIdColumn,
          edgeSrcColumn,
          edgeDstColumn
      );

      // Convert Gremlin to RelNode
      RelNode relNode = converter.convertGremlin(gremlinQuery);
      String relNodeString = RelOptUtil.toString(relNode);

      // Create response
      GremlinToRelResponseBody responseBody = new GremlinToRelResponseBody(
          gremlinQuery,
          relNodeString,
          true
      );

      return ResponseEntity.status(HttpStatus.OK).body(responseBody);

    } catch (Throwable t) {
      t.printStackTrace();
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(t.getMessage());
    }
  }
}
