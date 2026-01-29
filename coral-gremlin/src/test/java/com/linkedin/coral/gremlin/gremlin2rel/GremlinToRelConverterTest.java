/**
 * Copyright 2017-2026 LinkedIn Corporation. All rights reserved.
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
    assertTrue(relString.contains("members"), "Expected members table scan in plan: " + relString);
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
    assertTrue(relString.contains("connections"), "Expected connections table scan in plan: " + relString);
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
    assertTrue(relString.contains("members"), "Expected members table scan in plan: " + relString);
    assertTrue(relString.contains("Filter") || relString.contains("WHERE"), "Expected filter in plan: " + relString);
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
    assertTrue(relString.contains("members"), "Expected members table scan in plan: " + relString);
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
    assertTrue(relString.contains("members"), "Expected members table in plan: " + relString);
    assertTrue(relString.contains("connections"), "Expected connections table in plan: " + relString);
    assertTrue(relString.contains("Join"), "Expected join in plan: " + relString);
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
    assertTrue(relString.contains("members"), "Expected members table in plan: " + relString);
    assertTrue(relString.contains("connections"), "Expected connections table in plan: " + relString);
    assertTrue(relString.contains("Join"), "Expected join in plan: " + relString);
  }

  /**
   * Test bidirectional edge traversal: g.V().has('name', 'John').both()
   * Should translate to a UNION of out() and in() queries
   */
  @Test(enabled = false) // TODO: Implement .both() support
  public void testBidirectionalTraversal() {
    String gremlinQuery = "g.V().has('name', 'John').both()";
    RelNode rel = gremlinToRel(gremlinQuery);

    assertNotNull(rel);
    String relString = relToStr(rel);

    // Verify the plan includes union or multiple joins
    assertTrue(relString.contains("members"), "Expected members table in plan: " + relString);
    assertTrue(relString.contains("connections"), "Expected connections table in plan: " + relString);
    assertTrue(relString.contains("Union") || relString.contains("Join"),
        "Expected union or joins in plan: " + relString);
  }

  /**
   * Test edge label filtering: g.V().outE('friend').inV()
   * Should filter edges by the 'relation' column
   */
  @Test
  public void testEdgeLabelFiltering() {
    String gremlinQuery = "g.V().has('name', 'John').outE('friend').inV()";
    RelNode rel = gremlinToRel(gremlinQuery);

    assertNotNull(rel);
    String relString = relToStr(rel);

    // Verify the plan includes edge label filter
    assertTrue(relString.contains("members"), "Expected members table in plan: " + relString);
    assertTrue(relString.contains("connections"), "Expected connections table in plan: " + relString);
    assertTrue(relString.contains("friend"), "Expected 'friend' edge label filter in plan: " + relString);
  }

  /**
   * Test multi-hop traversal with edge labels: g.V().outE('friend').inV().outE('colleague').inV()
   */
  @Test
  public void testMultiHopTraversalWithEdgeLabels() {
    String gremlinQuery = "g.V().has('name', 'John').outE('friend').inV().outE('colleague').inV()";
    RelNode rel = gremlinToRel(gremlinQuery);

    assertNotNull(rel);
    String relString = relToStr(rel);

    // Verify the plan includes multiple joins and edge filters
    assertTrue(relString.contains("members"), "Expected members table in plan: " + relString);
    assertTrue(relString.contains("connections"), "Expected connections table in plan: " + relString);
    assertTrue(relString.contains("friend"), "Expected 'friend' edge label in plan: " + relString);
    assertTrue(relString.contains("colleague"), "Expected 'colleague' edge label in plan: " + relString);

    // Count joins - should have at least 4 (2 per hop: edge + vertex)
    int joinCount = relString.split("Join").length - 1;
    assertTrue(joinCount >= 4, "Expected at least 4 joins for two-hop traversal, found: " + joinCount);
  }

  /**
   * Test comparison operator: gt (greater than)
   */
  @Test
  public void testComparisonOperatorGreaterThan() {
    String gremlinQuery = "g.V().has('age', gt(25))";
    RelNode rel = gremlinToRel(gremlinQuery);

    assertNotNull(rel);
    String relString = relToStr(rel);

    // Verify the plan includes a comparison filter
    assertTrue(relString.contains("members"), "Expected members table in plan: " + relString);
    assertTrue(relString.contains("Filter") || relString.contains(">"), "Expected filter in plan: " + relString);
  }

  /**
   * Test comparison operator: lt (less than)
   */
  @Test
  public void testComparisonOperatorLessThan() {
    String gremlinQuery = "g.V().has('age', lt(30))";
    RelNode rel = gremlinToRel(gremlinQuery);

    assertNotNull(rel);
    String relString = relToStr(rel);

    assertTrue(relString.contains("members"), "Expected members table in plan: " + relString);
    assertTrue(relString.contains("Filter") || relString.contains("<"), "Expected filter in plan: " + relString);
  }

  /**
   * Test comparison operator: gte (greater than or equal)
   */
  @Test
  public void testComparisonOperatorGreaterThanOrEqual() {
    String gremlinQuery = "g.V().has('age', gte(21))";
    RelNode rel = gremlinToRel(gremlinQuery);

    assertNotNull(rel);
    String relString = relToStr(rel);

    assertTrue(relString.contains("members"), "Expected members table in plan: " + relString);
    assertTrue(relString.contains("Filter"), "Expected filter in plan: " + relString);
  }

  /**
   * Test comparison operator: lte (less than or equal)
   */
  @Test
  public void testComparisonOperatorLessThanOrEqual() {
    String gremlinQuery = "g.V().has('age', lte(65))";
    RelNode rel = gremlinToRel(gremlinQuery);

    assertNotNull(rel);
    String relString = relToStr(rel);

    assertTrue(relString.contains("members"), "Expected members table in plan: " + relString);
    assertTrue(relString.contains("Filter"), "Expected filter in plan: " + relString);
  }

  /**
   * Test comparison operator: neq (not equal)
   */
  @Test
  public void testComparisonOperatorNotEqual() {
    String gremlinQuery = "g.V().has('name', neq('John'))";
    RelNode rel = gremlinToRel(gremlinQuery);

    assertNotNull(rel);
    String relString = relToStr(rel);

    assertTrue(relString.contains("members"), "Expected members table in plan: " + relString);
    assertTrue(relString.contains("Filter") || relString.contains("<>"), "Expected filter in plan: " + relString);
  }

  /**
   * Test deduplication: g.V().out().dedup()
   */
  @Test
  public void testDeduplication() {
    String gremlinQuery = "g.V().out().dedup()";
    RelNode rel = gremlinToRel(gremlinQuery);

    assertNotNull(rel);
    String relString = relToStr(rel);

    // Verify the plan includes distinct/aggregate
    assertTrue(relString.contains("members"), "Expected members table in plan: " + relString);
    assertTrue(relString.contains("Aggregate") || relString.contains("distinct"),
        "Expected deduplication in plan: " + relString);
  }

  /**
   * Test ordering: g.V().order().by('age', decr)
   */
  @Test
  public void testOrderingDescending() {
    String gremlinQuery = "g.V().order().by('age', decr)";
    RelNode rel = gremlinToRel(gremlinQuery);

    assertNotNull(rel);
    String relString = relToStr(rel);

    // Verify the plan includes sort
    assertTrue(relString.contains("members"), "Expected members table in plan: " + relString);
    assertTrue(relString.contains("Sort"), "Expected sort in plan: " + relString);
  }

  /**
   * Test ordering: g.V().order().by('name', incr)
   */
  @Test
  public void testOrderingAscending() {
    String gremlinQuery = "g.V().order().by('name', incr)";
    RelNode rel = gremlinToRel(gremlinQuery);

    assertNotNull(rel);
    String relString = relToStr(rel);

    assertTrue(relString.contains("members"), "Expected members table in plan: " + relString);
    assertTrue(relString.contains("Sort"), "Expected sort in plan: " + relString);
  }

  /**
   * Test valueMap projection: g.V().valueMap('name', 'age')
   */
  @Test
  public void testValueMapProjection() {
    String gremlinQuery = "g.V().valueMap('name', 'age')";
    RelNode rel = gremlinToRel(gremlinQuery);

    assertNotNull(rel);
    String relString = relToStr(rel);

    // Verify the plan includes projection
    assertTrue(relString.contains("members"), "Expected members table in plan: " + relString);
    assertTrue(relString.contains("Project"), "Expected projection in plan: " + relString);
  }

  /**
   * Test complex query combining multiple features:
   * g.V().has('name', 'Tanvi').outE('friend').inV().outE('friend').inV().dedup().has('age', gt(25)).order().by('age', decr).valueMap('name', 'age', 'location')
   */
  @Test
  public void testComplexQueryWithAllFeatures() {
    String gremlinQuery =
        "g.V().has('name', 'Tanvi').outE('friend').inV().outE('friend').inV().dedup().has('age', gt(25)).order().by('age', decr).valueMap('name', 'age', 'location')";
    RelNode rel = gremlinToRel(gremlinQuery);

    assertNotNull(rel);
    String relString = relToStr(rel);

    // Verify all components are present
    assertTrue(relString.contains("members"), "Expected members table in plan: " + relString);
    assertTrue(relString.contains("connections"), "Expected connections table in plan: " + relString);
    assertTrue(relString.contains("friend"), "Expected 'friend' edge label in plan: " + relString);
    assertTrue(relString.contains("Filter"), "Expected filters in plan: " + relString);
    assertTrue(relString.contains("Aggregate") || relString.contains("distinct"),
        "Expected deduplication in plan: " + relString);
    assertTrue(relString.contains("Sort"), "Expected sort in plan: " + relString);
    assertTrue(relString.contains("Project"), "Expected projection in plan: " + relString);

    // Verify multi-hop (should have multiple joins)
    int joinCount = relString.split("Join").length - 1;
    assertTrue(joinCount >= 4, "Expected at least 4 joins for two-hop traversal, found: " + joinCount);
  }

  /**
   * Test 2-hop friends of friends query
   */
  @Test
  public void testTwoHopFriendsOfFriends() {
    String gremlinQuery = "g.V().has('name', 'Alice').outE('friend').inV().outE('friend').inV()";
    RelNode rel = gremlinToRel(gremlinQuery);

    assertNotNull(rel);
    String relString = relToStr(rel);

    // Should have 4 joins: v1->e1, e1->v2, v2->e2, e2->v3
    int joinCount = relString.split("Join").length - 1;
    assertTrue(joinCount >= 4, "Expected at least 4 joins for 2-hop traversal, found: " + joinCount);
    assertTrue(relString.contains("friend"), "Expected 'friend' edge label filters: " + relString);
  }

  /**
   * Test combination of filters and traversal
   */
  @Test
  public void testFilteredTraversal() {
    String gremlinQuery = "g.V().has('age', gt(30)).outE('colleague').inV().has('location', 'NYC')";
    RelNode rel = gremlinToRel(gremlinQuery);

    assertNotNull(rel);
    String relString = relToStr(rel);

    assertTrue(relString.contains("members"), "Expected members table: " + relString);
    assertTrue(relString.contains("connections"), "Expected connections table: " + relString);
    assertTrue(relString.contains("colleague"), "Expected 'colleague' edge label: " + relString);
    assertTrue(relString.contains("Filter"), "Expected filters: " + relString);
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
