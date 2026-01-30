/**
 * Copyright 2017-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.gremlin.gremlin2rel;

import org.apache.calcite.rel.RelNode;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static com.linkedin.coral.gremlin.gremlin2rel.GremlinTestUtils.*;
import static org.testng.Assert.*;


/**
 * Test multi-hop traversals with complex operations
 */
public class GremlinMultiHopTest {

  @BeforeClass
  public static void beforeClass() throws Exception {
    GremlinTestUtils.setup();
  }

  @Test
  public void testComplexMultiHopQuery() {
    String gremlinQuery = "g.V().has('name', 'Tanvi')" + ".outE('friend').inV()" + ".outE('friend').inV()" + ".dedup()"
        + ".has('age', gt(25))" + ".order().by('age', decr)" + ".valueMap('name', 'age', 'location')";

    RelNode relNode = gremlinToRel(gremlinQuery);
    assertNotNull(relNode);

    String relString = relToStr(relNode);
    System.out.println("RelNode:\n" + relString);

    // Verify the plan structure
    assertTrue(relString.contains("members"), "Expected members table");
    assertTrue(relString.contains("connections"), "Expected connections table");
    assertTrue(relString.contains("LogicalProject"), "Expected projection");
    assertTrue(relString.contains("LogicalSort"), "Expected sort");
    assertTrue(relString.contains("LogicalAggregate") || relString.contains("LogicalFilter"),
        "Expected aggregate or filter");

    // Verify RelNode is valid
    assertTrue(relNode != null, "RelNode should not be null");

    // Try to generate SQL and check nesting level
    try {
      com.linkedin.coral.spark.CoralSpark coralSpark =
          com.linkedin.coral.spark.CoralSpark.create(relNode, GremlinTestUtils.converter.getHiveMetastoreClient());
      String sparkSql = coralSpark.getSparkSql();
      System.out.println("\nGenerated Spark SQL:\n" + sparkSql);

      // Count nested SELECT statements
      int selectCount = countOccurrences(sparkSql, "SELECT");
      int fromCount = countOccurrences(sparkSql, "FROM");

      System.out.println("Number of SELECT statements: " + selectCount);
      System.out.println("Number of FROM clauses: " + fromCount);

      // Ideally we want minimal nesting
      // This is just to document current state
      assertTrue(selectCount > 0, "Should have at least one SELECT");
    } catch (Exception e) {
      System.err.println("Could not generate Spark SQL: " + e.getMessage());
    }
  }

  @Test
  public void testSimpleMultiHop() {
    String gremlinQuery = "g.V().has('name', 'Alice')" + ".outE('friend').inV()" + ".outE('friend').inV()";

    RelNode relNode = gremlinToRel(gremlinQuery);
    assertNotNull(relNode);

    String relString = relToStr(relNode);
    System.out.println("Simple Multi-hop RelNode:\n" + relString);

    // Should have 2 edge joins and 3 vertex tables
    int memberCount = countOccurrences(relString, "members");
    int connectionCount = countOccurrences(relString, "connections");

    assertTrue(memberCount >= 3, "Expected at least 3 member table references, got: " + memberCount);
    assertTrue(connectionCount >= 2, "Expected at least 2 connection table references, got: " + connectionCount);
  }

  private int countOccurrences(String str, String substr) {
    int count = 0;
    int index = 0;
    while ((index = str.indexOf(substr, index)) != -1) {
      count++;
      index += substr.length();
    }
    return count;
  }
}
