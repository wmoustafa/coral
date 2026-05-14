/**
 * Copyright 2017-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.benchmark.tests;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.linkedin.coral.benchmark.catalog.InMemoryCatalog;
import com.linkedin.coral.benchmark.data.ResultSet;
import com.linkedin.coral.benchmark.data.RowSet;
import com.linkedin.coral.benchmark.plugin.PluginRegistry;
import com.linkedin.coral.benchmark.spi.Dialect;
import com.linkedin.coral.benchmark.spi.EnginePlugin;
import com.linkedin.coral.benchmark.spi.VerificationLevel;
import com.linkedin.coral.benchmark.suite.QueryTestResult;
import com.linkedin.coral.benchmark.suite.TestReport;
import com.linkedin.coral.benchmark.suite.TranslationTestSuite;
import com.linkedin.coral.common.catalog.CoralTable;
import com.linkedin.coral.common.types.CoralTypeKind;
import com.linkedin.coral.common.types.PrimitiveType;
import com.linkedin.coral.common.types.StructField;
import com.linkedin.coral.common.types.StructType;


/**
 * End-to-end RESULT_SET smoke test for the cross-dialect translation pipeline. Translates
 * {@code SELECT * FROM default.users} between Spark and Trino in both directions, executes
 * on both engines, and asserts the result sets match.
 *
 * <p>This test JVM's parent classpath contains only the benchmark SPI and Coral common —
 * neither Spark 3.5 nor Trino 411 runtime classes are visible. Each plugin's runtime
 * classpath is handed in via system properties (populated by Gradle's per-plugin
 * configurations) and loaded inside an isolated {@code PluginClassLoader}. That isolation
 * is what makes Spark and Trino coexist in one JVM despite their conflicting transitive
 * dependencies.
 */
public class TestCrossDialectSelectStar {

  @DataProvider(name = "directions")
  public Object[][] directions() {
    return new Object[][] { { Dialect.SPARK, Dialect.TRINO }, { Dialect.TRINO, Dialect.SPARK } };
  }

  @Test(dataProvider = "directions")
  public void runsSelectStarEndToEnd(Dialect source, Dialect target) {
    StructType usersSchema = StructType.of(
        Arrays.asList(StructField.of("id", PrimitiveType.of(CoralTypeKind.INT, true)),
            StructField.of("name", PrimitiveType.of(CoralTypeKind.STRING, true))),
        true);

    InMemoryCatalog catalog =
        InMemoryCatalog.builder().createNamespace("default").addTable("default", "users", usersSchema).build();

    RowSet users = RowSet.builder(usersSchema).addRow(1, "alice").addRow(2, "bob").build();

    TranslationTestSuite suite = TranslationTestSuite.builder().source(source).target(target).catalog(catalog)
        .queryDir("queries/" + source.name().toLowerCase()).verificationLevel(VerificationLevel.RESULT_SET)
        .dialectPluginJars(Dialect.SPARK, classpathOf("coral.benchmark.plugin.spark.dialect"))
        .dialectPluginJars(Dialect.TRINO, classpathOf("coral.benchmark.plugin.trino.dialect"))
        .enginePluginJars(Dialect.SPARK, classpathOf("coral.benchmark.plugin.spark.engine"))
        .enginePluginJars(Dialect.TRINO, classpathOf("coral.benchmark.plugin.trino.engine"))
        .testData("default.users", users).build();

    TestReport report = suite.run();

    Assert.assertEquals(report.totalCount(), 1, "expected one query");
    if (report.passCount() != 1) {
      QueryTestResult q = report.getQueryResults().get(0);
      Assert.fail("Suite did not pass. status=" + q.getStatus() + ", category=" + q.getFailureCategory().orElse(null)
          + ", msg=" + q.getErrorMessage().orElse("(none)") + ", translated="
          + q.getTranslatedSql().orElse("(not produced)"));
    }
  }

  /**
   * Skeptic mode: bypasses the orchestrator and directly drives each engine plugin to
   * prove the engine actually starts, accepts the CoralCatalog-derived schema, and returns
   * rows. Prints the materialized output for visual inspection.
   */
  @Test
  public void provesEnginesActuallyRunWithCoralCatalogSchema() {
    StructType usersSchema = StructType.of(
        Arrays.asList(StructField.of("id", PrimitiveType.of(CoralTypeKind.INT, true)),
            StructField.of("name", PrimitiveType.of(CoralTypeKind.STRING, true))),
        true);

    InMemoryCatalog catalog =
        InMemoryCatalog.builder().createNamespace("default").addTable("default", "users", usersSchema).build();

    // 1) Verify CoralCatalog actually carries the schema we registered.
    CoralTable usersTable = catalog.getTable("default", "users");
    Assert.assertNotNull(usersTable, "CoralCatalog must return the registered users table");
    StructType retrieved = (StructType) usersTable.getSchema();
    Assert.assertEquals(retrieved.getFields().size(), 2);
    Assert.assertEquals(retrieved.getFields().get(0).getName(), "id");
    Assert.assertEquals(retrieved.getFields().get(0).getType().getKind(), CoralTypeKind.INT);
    Assert.assertEquals(retrieved.getFields().get(1).getName(), "name");
    Assert.assertEquals(retrieved.getFields().get(1).getType().getKind(), CoralTypeKind.STRING);
    System.out.println("[skeptic] CoralCatalog.getTable('default','users') -> " + retrieved);

    RowSet users = RowSet.builder(usersSchema).addRow(1, "alice").addRow(2, "bob").build();

    PluginRegistry registry = new PluginRegistry(getClass().getClassLoader());

    // 2) Drive the Trino engine end-to-end and print what comes back.
    EnginePlugin trino = registry.loadEnginePlugin(Dialect.TRINO, classpathOf("coral.benchmark.plugin.trino.engine"));
    System.out.println("[skeptic] Trino engine plugin instance: " + trino);
    trino.start();
    try {
      // CoralCatalog-derived schema -> engine table.
      trino.createTable("default", "users", retrieved);
      trino.loadData("default", "users", users);

      ResultSet rs = trino.execute("SELECT * FROM memory.default.users ORDER BY id");
      printResultSet("Trino", rs);
      Assert.assertEquals(rs.size(), 2, "Trino should return 2 rows");
      Assert.assertEquals(rs.getRows().get(0)[0], 1, "Trino row 0 id");
      Assert.assertEquals(rs.getRows().get(0)[1].toString(), "alice", "Trino row 0 name");
      Assert.assertEquals(rs.getRows().get(1)[0], 2, "Trino row 1 id");
      Assert.assertEquals(rs.getRows().get(1)[1].toString(), "bob", "Trino row 1 name");
    } finally {
      trino.stop();
    }

    // 3) Same drill for Spark.
    EnginePlugin spark = registry.loadEnginePlugin(Dialect.SPARK, classpathOf("coral.benchmark.plugin.spark.engine"));
    System.out.println("[skeptic] Spark engine plugin instance: " + spark);
    spark.start();
    try {
      spark.createTable("default", "users", retrieved);
      spark.loadData("default", "users", users);

      ResultSet rs = spark.execute("SELECT * FROM default.users ORDER BY id");
      printResultSet("Spark", rs);
      Assert.assertEquals(rs.size(), 2, "Spark should return 2 rows");
      Assert.assertEquals(rs.getRows().get(0)[0], 1, "Spark row 0 id");
      Assert.assertEquals(rs.getRows().get(0)[1].toString(), "alice", "Spark row 0 name");
      Assert.assertEquals(rs.getRows().get(1)[0], 2, "Spark row 1 id");
      Assert.assertEquals(rs.getRows().get(1)[1].toString(), "bob", "Spark row 1 name");
    } finally {
      spark.stop();
    }
  }

  private static void printResultSet(String label, ResultSet rs) {
    System.out.println("[skeptic] " + label + " result schema: " + rs.getSchema());
    System.out.println("[skeptic] " + label + " row count: " + rs.size());
    int i = 0;
    for (Object[] row : rs.getRows()) {
      StringBuilder sb = new StringBuilder("[skeptic] ").append(label).append(" row[").append(i++).append("] = [");
      for (int j = 0; j < row.length; j++) {
        if (j > 0) {
          sb.append(", ");
        }
        Object v = row[j];
        sb.append(v == null ? "null" : (v.getClass().getSimpleName() + "(" + v + ")"));
      }
      sb.append("]");
      System.out.println(sb);
    }
  }

  private static List<URL> classpathOf(String systemProperty) {
    String value = System.getProperty(systemProperty);
    if (value == null || value.isEmpty()) {
      throw new IllegalStateException("Missing system property " + systemProperty
          + " — Gradle test task should populate it from the matching per-plugin configuration.");
    }
    List<URL> urls = new ArrayList<>();
    for (String entry : value.split(File.pathSeparator)) {
      if (entry.isEmpty()) {
        continue;
      }
      try {
        urls.add(new File(entry).toURI().toURL());
      } catch (MalformedURLException e) {
        throw new IllegalStateException("Bad classpath entry: " + entry, e);
      }
    }
    return urls;
  }
}
