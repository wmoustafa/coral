/**
 * Copyright 2017-2024 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.gremlin.gremlin2rel;

import java.io.IOException;

import org.apache.calcite.rel.RelNode;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static com.linkedin.coral.gremlin.gremlin2rel.GremlinTestUtils.*;
import static org.testng.Assert.*;


/**
 * Test class for Gremlin to Coral IR conversion.
 * 
 * This test suite demonstrates converting Gremlin graph traversal queries
 * to Coral IR (RelNode). The tests use two tables in HMS:
 * - members: represents vertices V() with columns (member, name, age, location)
 * - connections: represents edges E() with columns (src_member, dst_member)
 * 
 * Each Gremlin traversal is converted to SQL operations on these tables,
 * then to Coral IR, which can then be converted to Spark SQL or other dialects.
 */
public class GremlinToRelConverterTest {

  @BeforeClass
  public static void beforeClass() throws IOException, HiveException, MetaException {
    // Setup HMS with graph tables using TestUtils pattern
    setup();
  }

  @AfterTest
  public void afterTest() {
    cleanup();
  }

  /**
   * Test basic vertex query: g.V()
   * Should translate to: SELECT * FROM members
   */
  @Test
  public void testBasicVertexQuery() {
    String gremlinQuery = "g.V()";
    RelNode rel = gremlinToRel(gremlinQuery);
    
    assertNotNull(rel);
    String relString = relToStr(rel);
    
    // Verify the plan includes a scan of the members table
    assertTrue(relString.contains("members"), 
        "Expected members table scan in plan: " + relString);
  }

  /**
   * Test basic edge query: g.E()
   * Should translate to: SELECT * FROM connections
   */
  @Test
  public void testBasicEdgeQuery() {
    String gremlinQuery = "g.E()";
    RelNode rel = gremlinToRel(gremlinQuery);
    
    assertNotNull(rel);
    String relString = relToStr(rel);
    
    // Verify the plan includes a scan of the connections table
    assertTrue(relString.contains("connections"), 
        "Expected connections table scan in plan: " + relString);
  }

  /**
   * Test vertex query with filter: g.V().has('name', 'John')
   * Should translate to: SELECT * FROM members WHERE name = 'John'
   */
  @Test
  public void testVertexQueryWithFilter() {
    String gremlinQuery = "g.V().has('name', 'John')";
    RelNode rel = gremlinToRel(gremlinQuery);
    
    assertNotNull(rel);
    String relString = relToStr(rel);
    
    // Verify the plan includes a filter
    assertTrue(relString.contains("members"), 
        "Expected members table scan in plan: " + relString);
    assertTrue(relString.contains("Filter") || relString.contains("WHERE"), 
        "Expected filter in plan: " + relString);
  }

  /**
   * Test vertex query with projection: g.V().values('name', 'age')
   * Should translate to: SELECT name, age FROM members
   */
  @Test
  public void testVertexQueryWithProjection() {
    String gremlinQuery = "g.V().values('name', 'age')";
    RelNode rel = gremlinToRel(gremlinQuery);
    
    assertNotNull(rel);
    String relString = relToStr(rel);
    
    // Verify the plan includes a projection
    assertTrue(relString.contains("members"), 
        "Expected members table scan in plan: " + relString);
    assertTrue(relString.contains("Project") || relString.contains("name"), 
        "Expected projection in plan: " + relString);
  }

  /**
   * Test outgoing edge traversal: g.V().has('name', 'John').out()
   * Should translate to a join between members and connections tables:
   * SELECT m2.* FROM members m1
   * JOIN connections e ON m1.member = e.src_member
   * JOIN members m2 ON e.dst_member = m2.member
   * WHERE m1.name = 'John'
   */
  @Test
  public void testOutgoingTraversal() {
    String gremlinQuery = "g.V().has('name', 'John').out()";
    RelNode rel = gremlinToRel(gremlinQuery);
    
    assertNotNull(rel);
    String relString = relToStr(rel);
    
    // Verify the plan includes joins
    assertTrue(relString.contains("members"), 
        "Expected members table in plan: " + relString);
    assertTrue(relString.contains("connections"), 
        "Expected connections table in plan: " + relString);
    assertTrue(relString.contains("Join"), 
        "Expected join in plan: " + relString);
  }

  /**
   * Test incoming edge traversal: g.V().has('name', 'John').in()
   * Should translate to a join with reversed edge direction:
   * SELECT m2.* FROM members m1
   * JOIN connections e ON m1.member = e.dst_member
   * JOIN members m2 ON e.src_member = m2.member
   * WHERE m1.name = 'John'
   */
  @Test
  public void testIncomingTraversal() {
    String gremlinQuery = "g.V().has('name', 'John').in()";
    RelNode rel = gremlinToRel(gremlinQuery);
    
    assertNotNull(rel);
    String relString = relToStr(rel);
    
    // Verify the plan includes joins
    assertTrue(relString.contains("members"), 
        "Expected members table in plan: " + relString);
    assertTrue(relString.contains("connections"), 
        "Expected connections table in plan: " + relString);
    assertTrue(relString.contains("Join"), 
        "Expected join in plan: " + relString);
  }

  /**
   * Test bidirectional edge traversal: g.V().has('name', 'John').both()
   * Should translate to a UNION of out() and in() queries
   */
  @Test
  public void testBidirectionalTraversal() {
    String gremlinQuery = "g.V().has('name', 'John').both()";
    RelNode rel = gremlinToRel(gremlinQuery);
    
    assertNotNull(rel);
    String relString = relToStr(rel);
    
    // Verify the plan includes union or multiple joins
    assertTrue(relString.contains("members"), 
        "Expected members table in plan: " + relString);
    assertTrue(relString.contains("connections"), 
        "Expected connections table in plan: " + relString);
    assertTrue(relString.contains("Union") || relString.contains("Join"), 
        "Expected union or joins in plan: " + relString);
  }

  /**
   * Test multi-hop traversal: g.V().out().out()
   * Each out() step should add another self-join on the edges table
   */
  @Test(enabled = false) // Disabled for initial prototype, will implement in next iteration
  public void testMultiHopTraversal() {
    String gremlinQuery = "g.V().out().out()";
    RelNode rel = gremlinToRel(gremlinQuery);
    
    assertNotNull(rel);
    String relString = relToStr(rel);
    
    // Verify the plan includes multiple joins (one per hop)
    assertTrue(relString.contains("members"), 
        "Expected members table in plan: " + relString);
    assertTrue(relString.contains("connections"), 
        "Expected connections table in plan: " + relString);
    
    // Count the number of joins (should be at least 2 for two hops)
    int joinCount = relString.split("Join").length - 1;
    assertTrue(joinCount >= 2, 
        "Expected at least 2 joins for two-hop traversal, found: " + joinCount);
  }

  /**
   * Test that we can convert the Coral IR to Spark SQL
   * This demonstrates the full pipeline: Gremlin -> Coral IR -> Spark SQL
   */
  @Test(enabled = false) // Disabled for initial prototype, requires Spark integration
  public void testGremlinToSparkConversion() {
    String gremlinQuery = "g.V().has('name', 'John').out()";
    RelNode rel = gremlinToRel(gremlinQuery);
    
    // TODO: Add Spark conversion once coral-spark integration is added
    // String sparkSql = convertToSpark(rel);
    // assertNotNull(sparkSql);
  }
}
