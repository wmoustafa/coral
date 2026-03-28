/**
 * Copyright 2025-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.datagen.domain;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.calcite.rel.RelNode;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.ql.CommandNeedRetryException;
import org.apache.hadoop.hive.ql.Driver;
import org.apache.hadoop.hive.ql.metadata.Hive;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.linkedin.coral.common.HiveMscAdapter;
import com.linkedin.coral.datagen.rel.ProjectPullUpController;
import com.linkedin.coral.hive.hive2rel.HiveToRelConverter;

import static org.testng.Assert.*;


/**
 * Test for GenerateTestDataProgram.
 * Validates that the program can generate test data for all columns in a schema,
 * with constrained data for columns with predicates and random data for others.
 */
public class GenerateTestDataProgramTest {
  private static final String CORAL_DATA_GENERATION_TEST_DIR = "coral.datagen.test.dir";
  private HiveConf conf;
  private HiveToRelConverter converter;
  private GenerateTestDataProgram program;

  @BeforeClass
  public void setup() {
    conf = getHiveConf();
    String testDir = conf.get(CORAL_DATA_GENERATION_TEST_DIR);
    try {
      FileUtils.deleteDirectory(new File(testDir));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    SessionState.start(conf);
    Driver driver = new Driver(conf);
    run(driver, String.join("\n", "", "CREATE DATABASE IF NOT EXISTS test"));
    run(driver, String.join("\n", "", "CREATE TABLE IF NOT EXISTS test.T (name STRING, age INT, birthdate DATE)"));
    run(driver, String.join("\n", "", "USE test"));
    try {
      java.util.List<String> dbs = Hive.get(conf).getMSC().getAllDatabases();
      if (!dbs.contains("test")) {
        throw new IllegalStateException("Metastore does not contain database 'test'. Databases: " + dbs);
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed to validate metastore after setup", e);
    }
    converter = new HiveToRelConverter(createMscAdapter(conf));

    // Initialize domain inference program with all transformers
    DomainInferenceProgram domainInferenceProgram =
        new DomainInferenceProgram(Arrays.asList(new LowerRegexTransformer(), new SubstringRegexTransformer(),
            new PlusRegexTransformer(), new TimesRegexTransformer(), new CastRegexTransformer()));

    // Initialize test data generator
    program = new GenerateTestDataProgram(domainInferenceProgram);
  }

  @AfterClass
  public void tearDown() throws IOException {
    FileUtils.deleteDirectory(new File(conf.get(CORAL_DATA_GENERATION_TEST_DIR)));
  }

  private static HiveConf getHiveConf() {
    InputStream hiveConfStream = GenerateTestDataProgramTest.class.getClassLoader().getResourceAsStream("hive.xml");
    HiveConf hiveConf = new HiveConf();
    hiveConf.set(CORAL_DATA_GENERATION_TEST_DIR,
        System.getProperty("java.io.tmpdir") + "/coral/datagen/" + UUID.randomUUID());
    hiveConf.addResource(hiveConfStream);
    hiveConf.set("mapreduce.framework.name", "local");
    hiveConf.set("_hive.hdfs.session.path", "/tmp/coral");
    hiveConf.set("_hive.local.session.path", "/tmp/coral");
    return hiveConf;
  }

  private static HiveMscAdapter createMscAdapter(HiveConf conf) {
    try {
      return new HiveMscAdapter(Hive.get(conf).getMSC());
    } catch (MetaException | HiveException e) {
      throw new RuntimeException("Could not initialize Hive Metastore Client Adapter: " + e);
    }
  }

  private static void run(Driver driver, String sql) {
    while (true) {
      try {
        driver.run(sql);
      } catch (CommandNeedRetryException e) {
        continue;
      }
      break;
    }
  }

  private RelNode normalize(RelNode root) {
    return ProjectPullUpController.applyUntilFixedPoint(root, 100);
  }

  @Test
  public void testGenerateTestDataWithSubstringPredicate() {
    System.out.println("\n=== Test: Generate Test Data with SUBSTRING Predicate ===");

    String sql = "SELECT * FROM test.T WHERE SUBSTRING(name, 1, 4) = '2000'";
    System.out.println("SQL: " + sql);

    RelNode relNode = converter.convertSql(sql);
    RelNode normalized = normalize(relNode);

    // Generate 5 rows of test data
    List<Map<String, Object>> testData = program.generateTestData(normalized, 5);

    System.out.println("\nGenerated Test Data:");
    for (int i = 0; i < testData.size(); i++) {
      Map<String, Object> row = testData.get(i);
      System.out.println("Row " + (i + 1) + ": " + row);

      // Validate that 'name' column starts with '2000'
      Object nameValue = row.get("name");
      assertNotNull(nameValue, "Name column should not be null");
      assertTrue(nameValue.toString().startsWith("2000"), "Name should start with '2000', but got: " + nameValue);

      // Validate that 'age' and 'birthdate' have random values
      assertNotNull(row.get("age"), "Age column should not be null");
      assertNotNull(row.get("birthdate"), "Birthdate column should not be null");
    }

    System.out.println("=== Test Passed ===\n");
  }

  @Test
  public void testGenerateTestDataWithMultiplePredicates() {
    System.out.println("\n=== Test: Generate Test Data with Multiple Predicates ===");

    String sql = "SELECT * FROM test.T WHERE SUBSTRING(name, 1, 4) = '2000' AND age = 25";
    System.out.println("SQL: " + sql);

    RelNode relNode = converter.convertSql(sql);
    RelNode normalized = normalize(relNode);

    // Generate 3 rows of test data
    List<Map<String, Object>> testData = program.generateTestData(normalized, 3);

    System.out.println("\nGenerated Test Data:");
    for (int i = 0; i < testData.size(); i++) {
      Map<String, Object> row = testData.get(i);
      System.out.println("Row " + (i + 1) + ": " + row);

      // Validate that 'name' column starts with '2000'
      Object nameValue = row.get("name");
      assertNotNull(nameValue, "Name column should not be null");
      assertTrue(nameValue.toString().startsWith("2000"), "Name should start with '2000', but got: " + nameValue);

      // Validate that 'age' is 25
      Object ageValue = row.get("age");
      assertNotNull(ageValue, "Age column should not be null");
      assertEquals(25L, ageValue, "Age should be 25");

      // Validate that 'birthdate' has a random value
      assertNotNull(row.get("birthdate"), "Birthdate column should not be null");
    }

    System.out.println("=== Test Passed ===\n");
  }

  @Test
  public void testGenerateTestDataWithNoPredicate() {
    System.out.println("\n=== Test: Generate Test Data with No Predicate ===");

    String sql = "SELECT * FROM test.T";
    System.out.println("SQL: " + sql);

    RelNode relNode = converter.convertSql(sql);
    RelNode normalized = normalize(relNode);

    // Generate 5 rows of test data
    List<Map<String, Object>> testData = program.generateTestData(normalized, 5);

    System.out.println("\nGenerated Test Data:");
    for (int i = 0; i < testData.size(); i++) {
      Map<String, Object> row = testData.get(i);
      System.out.println("Row " + (i + 1) + ": " + row);

      // All columns should have random values
      assertNotNull(row.get("name"), "Name column should not be null");
      assertNotNull(row.get("age"), "Age column should not be null");
      assertNotNull(row.get("birthdate"), "Birthdate column should not be null");
    }

    System.out.println("=== Test Passed ===\n");
  }

  @Test
  public void testGenerateTestDataWithLowerPredicate() {
    System.out.println("\n=== Test: Generate Test Data with LOWER Predicate ===");

    String sql = "SELECT * FROM test.T WHERE LOWER(name) = 'john'";
    System.out.println("SQL: " + sql);

    RelNode relNode = converter.convertSql(sql);
    RelNode normalized = normalize(relNode);

    // Generate 3 rows of test data
    List<Map<String, Object>> testData = program.generateTestData(normalized, 3);

    System.out.println("\nGenerated Test Data:");
    for (int i = 0; i < testData.size(); i++) {
      Map<String, Object> row = testData.get(i);
      System.out.println("Row " + (i + 1) + ": " + row);

      // Validate that 'name' column is case-insensitive 'john'
      Object nameValue = row.get("name");
      assertNotNull(nameValue, "Name column should not be null");
      assertEquals("john", nameValue.toString().toLowerCase(), "Name should be case-insensitive 'john'");

      // Validate that other columns have random values
      assertNotNull(row.get("age"), "Age column should not be null");
      assertNotNull(row.get("birthdate"), "Birthdate column should not be null");
    }

    System.out.println("=== Test Passed ===\n");
  }

  @Test
  public void testGenerateTestDataWithDecimalAndDoubleLiterals() {
    System.out.println("\n=== Test: Generate Test Data with DECIMAL and DOUBLE Literals ===");

    // First, create a table with DECIMAL and DOUBLE columns
    Driver driver = new Driver(conf);
    run(driver, "CREATE TABLE IF NOT EXISTS test.employees (" + "name STRING, " + "salary DECIMAL(10,2), "
        + "performance_score DOUBLE, " + "age INT)");

    // Test with DECIMAL literal
    String sql1 = "SELECT * FROM test.employees WHERE salary = 100000.50";
    System.out.println("SQL 1: " + sql1);

    RelNode relNode1 = converter.convertSql(sql1);
    RelNode normalized1 = normalize(relNode1);

    List<Map<String, Object>> testData1 = program.generateTestData(normalized1, 3);

    System.out.println("\nGenerated Test Data for DECIMAL predicate:");
    for (int i = 0; i < testData1.size(); i++) {
      Map<String, Object> row = testData1.get(i);
      System.out.println("Row " + (i + 1) + ": " + row);

      // Validate that 'salary' is exactly 100000.50
      Object salaryValue = row.get("salary");
      assertNotNull(salaryValue, "Salary column should not be null");
      assertEquals("100000.50", salaryValue.toString(), "Salary should be 100000.50, but got: " + salaryValue);

      // Other columns should have random values
      assertNotNull(row.get("name"), "Name column should not be null");
      assertNotNull(row.get("age"), "Age column should not be null");
    }

    // Test with DOUBLE literal
    String sql2 = "SELECT * FROM test.employees WHERE performance_score = 4.5";
    System.out.println("\nSQL 2: " + sql2);

    RelNode relNode2 = converter.convertSql(sql2);
    RelNode normalized2 = normalize(relNode2);

    List<Map<String, Object>> testData2 = program.generateTestData(normalized2, 3);

    System.out.println("\nGenerated Test Data for DOUBLE predicate:");
    for (int i = 0; i < testData2.size(); i++) {
      Map<String, Object> row = testData2.get(i);
      System.out.println("Row " + (i + 1) + ": " + row);

      // Validate that 'performance_score' is exactly 4.5
      Object scoreValue = row.get("performance_score");
      assertNotNull(scoreValue, "Performance score column should not be null");
      assertEquals("4.5", scoreValue.toString(), "Performance score should be 4.5, but got: " + scoreValue);

      // Other columns should have random values
      assertNotNull(row.get("name"), "Name column should not be null");
      assertNotNull(row.get("age"), "Age column should not be null");
    }

    // Test with multiple predicates including DECIMAL and DOUBLE
    String sql3 = "SELECT * FROM test.employees WHERE salary = 100000.50 AND performance_score = 4.5 AND age = 30";
    System.out.println("\nSQL 3: " + sql3);

    RelNode relNode3 = converter.convertSql(sql3);
    RelNode normalized3 = normalize(relNode3);

    List<Map<String, Object>> testData3 = program.generateTestData(normalized3, 3);

    System.out.println("\nGenerated Test Data for multiple predicates:");
    for (int i = 0; i < testData3.size(); i++) {
      Map<String, Object> row = testData3.get(i);
      System.out.println("Row " + (i + 1) + ": " + row);

      // Validate all predicate columns
      assertEquals("100000.50", row.get("salary").toString(), "Salary should be 100000.50");
      assertEquals("4.5", row.get("performance_score").toString(), "Performance score should be 4.5");
      assertEquals(30L, row.get("age"), "Age should be 30");

      // Name should have random value
      assertNotNull(row.get("name"), "Name column should not be null");
    }

    System.out.println("=== Test Passed ===\n");
  }
}
