/**
 * Copyright 2017-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.benchmark.tests;

import java.util.Arrays;

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.linkedin.coral.benchmark.catalog.InMemoryCatalog;
import com.linkedin.coral.benchmark.data.RowSet;
import com.linkedin.coral.benchmark.spi.Dialect;
import com.linkedin.coral.benchmark.spi.Engine;
import com.linkedin.coral.benchmark.spi.VerificationLevel;
import com.linkedin.coral.benchmark.suite.QueryTestResult;
import com.linkedin.coral.benchmark.suite.TestReport;
import com.linkedin.coral.benchmark.suite.TranslationTestSuite;
import com.linkedin.coral.common.types.CoralTypeKind;
import com.linkedin.coral.common.types.PrimitiveType;
import com.linkedin.coral.common.types.StructField;
import com.linkedin.coral.common.types.StructType;


/**
 * End-to-end RESULT_SET tests for the cross-dialect translation pipeline.
 *
 * <p>The test only names the scenario — {@code (sourceEngine, sourceDialect)} and
 * {@code (targetEngine, targetDialect)} — and a query directory. Plugin discovery,
 * jar paths, and classloader isolation are entirely the {@code PluginCatalog}'s job;
 * by default the suite auto-discovers from system properties that the Gradle test task
 * populates from each plugin module's runtime classpath.
 */
public class TestCrossDialectTranslation {

  @DataProvider(name = "directions")
  public Object[][] directions() {
    return new Object[][] {
        { Engine.SPARK, Dialect.SPARK_SQL, Engine.TRINO, Dialect.TRINO_SQL },
        { Engine.TRINO, Dialect.TRINO_SQL, Engine.SPARK, Dialect.SPARK_SQL } };
  }

  @Test(dataProvider = "directions")
  public void translatesHappyPathQueriesEndToEnd(Engine srcEngine, Dialect srcDialect, Engine tgtEngine,
      Dialect tgtDialect) {
    TestReport report = TranslationTestSuite.builder().source(srcEngine, srcDialect).target(tgtEngine, tgtDialect)
        .catalog(usersCatalog()).queryDir("queries/" + srcDialect.id())
        .verificationLevel(VerificationLevel.RESULT_SET).testData("default.users", usersData()).build().run();

    Assert.assertTrue(report.totalCount() > 0, "expected at least one query in the corpus");
    if (report.failCount() != 0) {
      Assert.fail("Suite had failures: " + describeFailures(report));
    }
  }

  @Test(dataProvider = "directions")
  public void surfacesTranslationErrorForUnknownTable(Engine srcEngine, Dialect srcDialect, Engine tgtEngine,
      Dialect tgtDialect) {
    TestReport report = TranslationTestSuite.builder().source(srcEngine, srcDialect).target(tgtEngine, tgtDialect)
        .catalog(usersCatalog()).queryDir("queries/negative/" + srcDialect.id())
        .verificationLevel(VerificationLevel.RESULT_SET).testData("default.users", usersData()).build().run();

    Assert.assertEquals(report.totalCount(), 1, "expected one negative query");
    Assert.assertEquals(report.passCount(), 0, "negative query must not pass");
    QueryTestResult q = report.getQueryResults().get(0);
    Assert.assertEquals(q.getFailureCategory().orElse(null), QueryTestResult.FailureCategory.TRANSLATION_ERROR,
        "expected TRANSLATION_ERROR, got " + q.getFailureCategory().orElse(null) + " (" + q.getErrorMessage().orElse("")
            + ")");
  }

  private static InMemoryCatalog usersCatalog() {
    return InMemoryCatalog.builder().createNamespace("default").addTable("default", "users", usersSchema()).build();
  }

  private static RowSet usersData() {
    return RowSet.builder(usersSchema()).addRow(1, "alice").addRow(2, "bob").build();
  }

  private static StructType usersSchema() {
    return StructType.of(Arrays.asList(StructField.of("id", PrimitiveType.of(CoralTypeKind.INT, true)),
        StructField.of("name", PrimitiveType.of(CoralTypeKind.STRING, true))), true);
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
}
