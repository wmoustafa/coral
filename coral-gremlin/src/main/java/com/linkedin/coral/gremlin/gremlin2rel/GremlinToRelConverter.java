/**
 * Copyright 2017-2024 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.gremlin.gremlin2rel;

import java.util.ArrayList;
import java.util.List;

import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.plan.RelTraitDef;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.runtime.Hook;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Programs;
import org.apache.calcite.tools.RelBuilder;

import com.linkedin.coral.common.HiveMetastoreClient;
import com.linkedin.coral.common.HiveRelBuilder;
import com.linkedin.coral.common.HiveSchema;
import com.linkedin.coral.common.HiveTypeSystem;


/**
 * Public class to convert Gremlin queries to Calcite relational algebra (Coral IR).
 * This class serves as the main entry point for clients to convert Gremlin graph
 * traversal queries to relational plans.
 * 
 * Gremlin queries are converted DIRECTLY to Coral IR (RelNode) without SQL intermediate step.
 */
public class GremlinToRelConverter {
  private final RelBuilder relBuilder;
  private final String vertexTable;
  private final String edgeTable;
  private final String vertexIdColumn;
  private final String edgeSrcColumn;
  private final String edgeDstColumn;

  /**
   * Create a converter with fully configurable table and column names.
   * 
   * @param hiveMetastoreClient The Hive metastore client
   * @param vertexTable Fully qualified table name for vertices
   * @param edgeTable Fully qualified table name for edges
   * @param vertexIdColumn Column name for vertex ID
   * @param edgeSrcColumn Column name for edge source
   * @param edgeDstColumn Column name for edge destination
   */
  public GremlinToRelConverter(HiveMetastoreClient hiveMetastoreClient, String vertexTable, String edgeTable,
                               String vertexIdColumn, String edgeSrcColumn, String edgeDstColumn) {
    // Create Hive schema and framework config
    SchemaPlus schemaPlus = Frameworks.createRootSchema(false);
    schemaPlus.add(HiveSchema.ROOT_SCHEMA, new HiveSchema(hiveMetastoreClient));
    
    FrameworkConfig config = Frameworks.newConfigBuilder()
        .defaultSchema(schemaPlus)
        .typeSystem(new HiveTypeSystem())
        .traitDefs((List<RelTraitDef>) null)
        .programs(Programs.ofRules(Programs.RULE_SET))
        .build();
    
    // Create RelBuilder
    Hook.REL_BUILDER_SIMPLIFY.add(Hook.propertyJ(false));
    this.relBuilder = HiveRelBuilder.create(config);
    this.vertexTable = vertexTable;
    this.edgeTable = edgeTable;
    this.vertexIdColumn = vertexIdColumn;
    this.edgeSrcColumn = edgeSrcColumn;
    this.edgeDstColumn = edgeDstColumn;
  }

  /**
   * Convert a Gremlin query string to a RelNode (Coral IR).
   * Directly builds RelNode without SQL intermediate step.
   * 
   * @param gremlinQuery The Gremlin traversal query string
   * @return RelNode representing the query in Coral IR
   */
  public RelNode convertGremlin(String gremlinQuery) {
    String[] vertexParts = vertexTable.split("\\.");
    String[] edgeParts = edgeTable.split("\\.");
    
    // g.V() - scan vertex table
    if (gremlinQuery.equals("g.V()")) {
      return relBuilder.scan("hive", vertexParts[0], vertexParts[1]).build();
    }
    
    // g.E() - scan edge table
    if (gremlinQuery.equals("g.E()")) {
      return relBuilder.scan("hive", edgeParts[0], edgeParts[1]).build();
    }
    
    // g.V().has('prop', 'val').values() OR g.V().values() - scan + optional filter + optional projection
    if (gremlinQuery.contains("g.V()") && (gremlinQuery.contains(".has(") || gremlinQuery.contains(".values("))
        && !gremlinQuery.contains(".out(") && !gremlinQuery.contains(".in(") && !gremlinQuery.contains(".both(")) {
      relBuilder.scan("hive", vertexParts[0], vertexParts[1]);
      
      String[] filter = extractFilter(gremlinQuery);
      if (filter != null) {
        relBuilder.filter(relBuilder.equals(relBuilder.field(filter[0]), relBuilder.literal(filter[1])));
      }
      
      if (gremlinQuery.contains(".values(")) {
        List<String> cols = extractColumns(gremlinQuery);
        if (cols != null) {
          List<RexNode> projects = new ArrayList<>();
          for (String col : cols) {
            projects.add(relBuilder.field(col));
          }
          relBuilder.project(projects);
        }
      }
      
      return relBuilder.build();
    }
    
    // Traversals: .out(), .in(), .both()
    if (gremlinQuery.contains(".out(") || gremlinQuery.contains(".in(") || gremlinQuery.contains(".both(")) {
      return buildTraversal(gremlinQuery, vertexParts, edgeParts);
    }
    
    throw new UnsupportedOperationException("Unsupported Gremlin pattern: " + gremlinQuery);
  }
  
  private RelNode buildTraversal(String query, String[] vParts, String[] eParts) {
    relBuilder.scan("hive", vParts[0], vParts[1]).as("v1");
    
    String[] filter = extractFilter(query);
    if (filter != null) {
      relBuilder.filter(relBuilder.equals(relBuilder.field("v1", filter[0]), relBuilder.literal(filter[1])));
    }
    
    if (query.contains(".out(")) {
      relBuilder.scan("hive", eParts[0], eParts[1]).as("e");
      relBuilder.join(JoinRelType.INNER,
          relBuilder.equals(relBuilder.field(2, "v1", vertexIdColumn), relBuilder.field(2, "e", edgeSrcColumn)));
      relBuilder.scan("hive", vParts[0], vParts[1]).as("v2");
      relBuilder.join(JoinRelType.INNER,
          relBuilder.equals(relBuilder.field(2, "e", edgeDstColumn), relBuilder.field(2, "v2", vertexIdColumn)));
    } else if (query.contains(".in(")) {
      relBuilder.scan("hive", eParts[0], eParts[1]).as("e");
      relBuilder.join(JoinRelType.INNER,
          relBuilder.equals(relBuilder.field(2, "v1", vertexIdColumn), relBuilder.field(2, "e", edgeDstColumn)));
      relBuilder.scan("hive", vParts[0], vParts[1]).as("v2");
      relBuilder.join(JoinRelType.INNER,
          relBuilder.equals(relBuilder.field(2, "e", edgeSrcColumn), relBuilder.field(2, "v2", vertexIdColumn)));
    } else if (query.contains(".both(")) {
      RelNode out = buildOutTraversal(query, vParts, eParts);
      RelNode in = buildInTraversal(query, vParts, eParts);
      relBuilder.push(out).push(in).union(true);
    }
    
    return relBuilder.build();
  }
  
  private RelNode buildOutTraversal(String query, String[] vParts, String[] eParts) {
    relBuilder.scan("hive", vParts[0], vParts[1]).as("v1");
    String[] filter = extractFilter(query);
    if (filter != null) {
      relBuilder.filter(relBuilder.equals(relBuilder.field("v1", filter[0]), relBuilder.literal(filter[1])));
    }
    relBuilder.scan("hive", eParts[0], eParts[1]).as("e");
    relBuilder.join(JoinRelType.INNER,
        relBuilder.equals(relBuilder.field(2, "v1", vertexIdColumn), relBuilder.field(2, "e", edgeSrcColumn)));
    relBuilder.scan("hive", vParts[0], vParts[1]).as("v2");
    relBuilder.join(JoinRelType.INNER,
        relBuilder.equals(relBuilder.field(2, "e", edgeDstColumn), relBuilder.field(2, "v2", vertexIdColumn)));
    return relBuilder.build();
  }
  
  private RelNode buildInTraversal(String query, String[] vParts, String[] eParts) {
    relBuilder.scan("hive", vParts[0], vParts[1]).as("v1");
    String[] filter = extractFilter(query);
    if (filter != null) {
      relBuilder.filter(relBuilder.equals(relBuilder.field("v1", filter[0]), relBuilder.literal(filter[1])));
    }
    relBuilder.scan("hive", eParts[0], eParts[1]).as("e");
    relBuilder.join(JoinRelType.INNER,
        relBuilder.equals(relBuilder.field(2, "v1", vertexIdColumn), relBuilder.field(2, "e", edgeDstColumn)));
    relBuilder.scan("hive", vParts[0], vParts[1]).as("v2");
    relBuilder.join(JoinRelType.INNER,
        relBuilder.equals(relBuilder.field(2, "e", edgeSrcColumn), relBuilder.field(2, "v2", vertexIdColumn)));
    return relBuilder.build();
  }
  
  private String[] extractFilter(String query) {
    int start = query.indexOf(".has(");
    if (start == -1) return null;
    int end = query.indexOf(")", start);
    String content = query.substring(start + 5, end);
    String[] parts = content.split(",");
    if (parts.length == 2) {
      return new String[]{parts[0].trim().replace("'", ""), parts[1].trim().replace("'", "")};
    }
    return null;
  }
  
  private List<String> extractColumns(String query) {
    int start = query.indexOf(".values(");
    if (start == -1) return null;
    int end = query.indexOf(")", start);
    String content = query.substring(start + 8, end);
    List<String> cols = new ArrayList<>();
    for (String col : content.split(",")) {
      cols.add(col.trim().replace("'", ""));
    }
    return cols;
  }
}
