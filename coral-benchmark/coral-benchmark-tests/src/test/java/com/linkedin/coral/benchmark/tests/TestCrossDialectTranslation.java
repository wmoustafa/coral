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
import com.linkedin.coral.benchmark.data.RowSet;
import com.linkedin.coral.benchmark.spi.Dialect;
import com.linkedin.coral.benchmark.spi.VerificationLevel;
import com.linkedin.coral.benchmark.suite.QueryTestResult;
import com.linkedin.coral.benchmark.suite.TestReport;
import com.linkedin.coral.benchmark.suite.TranslationTestSuite;
import com.linkedin.coral.common.types.CoralTypeKind;
import com.linkedin.coral.common.types.PrimitiveType;
import com.linkedin.coral.common.types.StructField;
import com.linkedin.coral.common.types.StructType;


/**
 * End-to-end RESULT_SET tests for the cross-dialect translation pipeline. For each
 * (source, target) pair the test JVM owns no Spark or Trino runtime classes directly —
 * each plugin's classpath is handed in via system properties (populated by Gradle's
 * per-plugin configurations) and loaded inside an isolated {@code PluginClassLoader}.
 */
public class TestCrossDialectTranslation {

  @DataProvider(name = "directions")
  public Object[][] directions() {
    return new Object[][] { { Dialect.SPARK_SQL, Dialect.TRINO_SQL }, { Dialect.TRINO_SQL, Dialect.SPARK_SQL } };
  }

  /**
   * Happy path: every {@code .sql} file in the source dialect's directory translates to
   * the target dialect, executes on both engines, and yields equivalent result sets.
   */
  @Test(dataProvider = "directions")
  public void translatesHappyPathQueriesEndToEnd(Dialect source, Dialect target) {
    InMemoryCatalog catalog = usersCatalog();
    RowSet users = usersData();

    TranslationTestSuite suite =
        baseBuilder(source, target, catalog, users).queryDir("queries/" + source.id()).build();

    TestReport report = suite.run();

    Assert.assertTrue(report.totalCount() > 0, "expected at least one query in the corpus");
    if (report.failCount() != 0) {
      Assert.fail("Suite had failures: " + describeFailures(report));
    }
  }

  /**
   * Negative path: a query against an unknown table must surface as a clean
   * {@link QueryTestResult.FailureCategory#TRANSLATION_ERROR}, not a thrown exception or
   * a silent pass.
   */
  @Test(dataProvider = "directions")
  public void surfacesTranslationErrorForUnknownTable(Dialect source, Dialect target) {
    InMemoryCatalog catalog = usersCatalog();
    RowSet users = usersData();

    TranslationTestSuite suite =
        baseBuilder(source, target, catalog, users).queryDir("queries/negative/" + source.id()).build();

    TestReport report = suite.run();

    Assert.assertEquals(report.totalCount(), 1, "expected one negative query");
    Assert.assertEquals(report.passCount(), 0, "negative query must not pass");
    QueryTestResult q = report.getQueryResults().get(0);
    Assert.assertEquals(q.getFailureCategory().orElse(null), QueryTestResult.FailureCategory.TRANSLATION_ERROR,
        "expected TRANSLATION_ERROR, got " + q.getFailureCategory().orElse(null) + " (" + q.getErrorMessage().orElse("")
            + ")");
  }

  private static InMemoryCatalog usersCatalog() {
    StructType usersSchema = StructType.of(
        Arrays.asList(StructField.of("id", PrimitiveType.of(CoralTypeKind.INT, true)),
            StructField.of("name", PrimitiveType.of(CoralTypeKind.STRING, true))),
        true);
    return InMemoryCatalog.builder().createNamespace("default").addTable("default", "users", usersSchema).build();
  }

  private static RowSet usersData() {
    StructType usersSchema = StructType.of(
        Arrays.asList(StructField.of("id", PrimitiveType.of(CoralTypeKind.INT, true)),
            StructField.of("name", PrimitiveType.of(CoralTypeKind.STRING, true))),
        true);
    return RowSet.builder(usersSchema).addRow(1, "alice").addRow(2, "bob").build();
  }

  /** Dialects the build wires up with plugin jars; iterate these (not Dialect.values()) so
   *  the test doesn't try to load a HIVE_SQL plugin that doesn't ship yet. */
  private static final List<Dialect> WIRED_DIALECTS = Arrays.asList(Dialect.SPARK_SQL, Dialect.TRINO_SQL);

  private static final String KIND_DIALECT = "dialect";
  private static final String KIND_ENGINE = "engine";

  private static TranslationTestSuite.Builder baseBuilder(Dialect source, Dialect target, InMemoryCatalog catalog,
      RowSet users) {
    TranslationTestSuite.Builder b = TranslationTestSuite.builder().source(source).target(target).catalog(catalog)
        .verificationLevel(VerificationLevel.RESULT_SET).testData("default.users", users);
    for (Dialect d : WIRED_DIALECTS) {
      b.dialectPluginJars(d, classpathOf(d, KIND_DIALECT));
      b.enginePluginJars(d, classpathOf(d, KIND_ENGINE));
    }
    return b;
  }

  private static String describeFailures(TestReport report) {
    StringBuilder sb = new StringBuilder();
    for (QueryTestResult q : report.getFailures()) {
      sb.append("\n  ").append(q.getQueryName()).append(": status=").append(q.getStatus()).append(", category=")
          .append(q.getFailureCategory().orElse(null)).append(", msg=").append(q.getErrorMessage().orElse("(none)"))
          .append(", translated=").append(q.getTranslatedSql().orElse("(not produced)"));
    }
    return sb.toString();
  }

  /**
   * Builds the system-property key for a given (dialect, kind) and reads the classpath
   * the Gradle test task populated. The key format — {@code coral.benchmark.plugin.<dialect.id()>.<kind>}
   * — must agree with the {@code systemProperty} declarations in
   * {@code coral-benchmark-tests/build.gradle}.
   */
  private static List<URL> classpathOf(Dialect dialect, String kind) {
    String propertyName = "coral.benchmark.plugin." + dialect.id() + "." + kind;
    String value = System.getProperty(propertyName);
    if (value == null || value.isEmpty()) {
      throw new IllegalStateException("Missing system property " + propertyName
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
