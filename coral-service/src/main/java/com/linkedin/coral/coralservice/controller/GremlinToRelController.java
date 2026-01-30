/**
 * Copyright 2017-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.coralservice.controller;

import java.io.File;
import java.util.UUID;

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
import com.linkedin.coral.coralservice.entity.VisualizationResponseBody;
import com.linkedin.coral.gremlin.gremlin2rel.GremlinToRelConverter;
import com.linkedin.coral.vis.VisualizationUtil;

import static com.linkedin.coral.coralservice.utils.CoralProvider.*;
import static com.linkedin.coral.coralservice.utils.VisualizationUtils.*;


/**
 * REST controller for converting Gremlin queries to Coral IR (RelNode) and Spark SQL.
 * 
 * <p>This controller provides an endpoint to convert Apache TinkerPop Gremlin graph
 * traversal queries into:
 * 1. Calcite RelNode representation (Coral IR)
 * 2. Spark SQL (via coral-spark)
 * 
 * <p>The conversion pipeline is: Gremlin → RelNode → Spark SQL
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
   * Converts a Gremlin query to Coral IR (RelNode) and Spark SQL.
   * 
   * <p>Conversion pipeline:
   * 1. Gremlin query → RelNode (Coral IR) using GremlinToRelConverter
   * 2. RelNode → Spark SQL using CoralSpark
   * 
   * @param requestBody contains the Gremlin query and table/column configuration
   * @return ResponseEntity containing the RelNode and Spark SQL representations or error message
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
      GremlinToRelConverter converter = new GremlinToRelConverter(hiveMetastoreClient, vertexTable, edgeTable,
          vertexIdColumn, edgeSrcColumn, edgeDstColumn);

      // Step 1: Convert Gremlin to RelNode (Coral IR)
      RelNode relNode = converter.convertGremlin(gremlinQuery);
      String relNodeString = RelOptUtil.toString(relNode);

      // Step 2: Convert RelNode to Spark SQL using custom flat SQL generator
      com.linkedin.coral.gremlin.gremlin2rel.FlatSQLGenerator flatSQLGenerator =
          new com.linkedin.coral.gremlin.gremlin2rel.FlatSQLGenerator();
      String sparkSql = flatSQLGenerator.generateSQL(relNode);

      // Create response with both RelNode and Spark SQL
      GremlinToRelResponseBody responseBody = new GremlinToRelResponseBody(gremlinQuery, relNodeString, sparkSql, true);

      return ResponseEntity.status(HttpStatus.OK).body(responseBody);

    } catch (Throwable t) {
      t.printStackTrace();
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(t.getMessage());
    }
  }

  /**
   * Generates a visualization diagram for a Gremlin query's RelNode.
   * 
   * @param requestBody contains the Gremlin query and table/column configuration
   * @return ResponseEntity containing the image ID for the RelNode diagram
   */
  /**
   * Simplify SQL by removing unnecessary nested SELECT statements.
   * This is a best-effort approach to flatten Calcite's nested SQL output.
   */
  private String simplifySQL(String sql) {
    // Remove patterns like: SELECT * FROM (SELECT ... FROM ...) t WHERE/ORDER
    // and flatten to: SELECT ... FROM ... WHERE/ORDER

    // Pattern: SELECT t.col1, t.col2 FROM (SELECT * FROM (...) t1) t
    // Replace with the inner query directly
    String simplified = sql;

    // Remove outer SELECT * wrapper if it exists
    // Pattern: SELECT * FROM (actual_query) alias
    if (simplified.matches("(?s)SELECT \\*\\s+FROM \\(SELECT.*")) {
      simplified = simplified.replaceFirst("(?s)^SELECT \\*\\s+FROM \\((.*)\\) \\w+$", "$1");
    }

    return simplified;
  }

  @PostMapping("/api/gremlin/visualize")
  public ResponseEntity visualize(@RequestBody GremlinToRelRequestBody requestBody) {
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

    try {
      // Create converter
      GremlinToRelConverter converter = new GremlinToRelConverter(hiveMetastoreClient, vertexTable, edgeTable,
          vertexIdColumn, edgeSrcColumn, edgeDstColumn);

      // Convert Gremlin to RelNode for visualization
      // Skip projections and optimization to avoid null pointer issues in Graphviz
      RelNode relNode = converter.convertGremlin(gremlinQuery, false, true);

      if (relNode == null) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("RelNode conversion returned null");
      }

      // Generate visualization
      File imageDir = getImageDir();
      if (imageDir == null || !imageDir.exists()) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body("Image directory not available: " + (imageDir != null ? imageDir.getPath() : "null"));
      }

      UUID imageId = UUID.randomUUID();
      String fileName = imageId + ".svg";

      // Configure GraphViz for headless environment
      System.setProperty("java.awt.headless", "true");

      VisualizationUtil visualizationUtil = VisualizationUtil.create(imageDir);
      if (visualizationUtil == null) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("VisualizationUtil creation failed");
      }

      visualizationUtil.visualizeRelNodeToFile(relNode, fileName);

      // Create response
      VisualizationResponseBody responseBody = new VisualizationResponseBody();
      responseBody.setRelNodeImageID(imageId);

      return ResponseEntity.status(HttpStatus.OK).body(responseBody);

    } catch (Throwable t) {
      System.err.println("=== VISUALIZATION ERROR ===");
      System.err.println("Error message: " + t.getMessage());
      System.err.println("Error class: " + t.getClass().getName());
      t.printStackTrace();
      System.err.println("=========================");

      // Return error message in response body
      String errorMsg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorMsg);
    }
  }
}
