/**
 * Copyright 2017-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.benchmark.suite;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.calcite.rel.RelNode;

import com.linkedin.coral.benchmark.comparison.ComparisonConfig;
import com.linkedin.coral.benchmark.comparison.ComparisonResult;
import com.linkedin.coral.benchmark.comparison.ResultSetComparator;
import com.linkedin.coral.benchmark.data.ExplainResult;
import com.linkedin.coral.benchmark.data.ResultSet;
import com.linkedin.coral.benchmark.data.RowSet;
import com.linkedin.coral.benchmark.plugin.PluginCatalog;
import com.linkedin.coral.benchmark.spi.Dialect;
import com.linkedin.coral.benchmark.spi.DialectPlugin;
import com.linkedin.coral.benchmark.spi.Engine;
import com.linkedin.coral.benchmark.spi.EnginePlugin;
import com.linkedin.coral.benchmark.spi.VerificationLevel;
import com.linkedin.coral.common.catalog.CoralCatalog;
import com.linkedin.coral.common.catalog.CoralTable;
import com.linkedin.coral.common.types.StructType;


/**
 * Main orchestrator for cross-dialect translation benchmark tests.
 *
 * <p>A test suite is parameterized by source {@code (engine, dialect)}, target
 * {@code (engine, dialect)}, a verification level, a Coral catalog, and a query
 * directory. It reads all {@code .sql} files from the configured directory, translates
 * each through the Coral IR pipeline, and verifies the result at the configured level.
 *
 * <p>Plugin discovery is decoupled from scenario specification: a {@link PluginCatalog}
 * owns which plugins are deployed (where their jars live, how they self-identify), while
 * the suite only names what it wants in terms of {@link Engine} and {@link Dialect}
 * values. Callers can supply a custom catalog or let the builder default to
 * {@link PluginCatalog#discoverFromSystemProperties()} — in either case the suite never
 * sees jar URLs.
 *
 * <p>Usage:
 * <pre>{@code
 * TranslationTestSuite suite = TranslationTestSuite.builder()
 *     .source(Engine.SPARK, Dialect.SPARK_SQL)
 *     .target(Engine.TRINO, Dialect.TRINO_SQL)
 *     .catalog(catalog)
 *     .queryDir("queries/spark_sql")
 *     .verificationLevel(VerificationLevel.RESULT_SET)
 *     .testData("default.users", userData)
 *     .build();
 *
 * TestReport report = suite.run();
 * }</pre>
 */
public final class TranslationTestSuite {

  private final Dialect source;
  private final Dialect target;
  private final CoralCatalog catalog;
  private final String queryDir;
  private final VerificationLevel verificationLevel;
  private final DialectPlugin sourcePlugin;
  private final DialectPlugin targetPlugin;
  private final EnginePlugin sourceEngine;
  private final EnginePlugin targetEngine;
  private final Map<String, RowSet> testData;
  private final ComparisonConfig comparisonConfig;
  private final PluginCatalog pluginCatalog;
  private final boolean ownsCatalog;

  private TranslationTestSuite(Builder builder, DialectPlugin sourcePlugin, DialectPlugin targetPlugin,
      EnginePlugin sourceEngine, EnginePlugin targetEngine, PluginCatalog pluginCatalog, boolean ownsCatalog) {
    this.source = builder.sourceDialect;
    this.target = builder.targetDialect;
    this.catalog = builder.catalog;
    this.queryDir = builder.queryDir;
    this.verificationLevel = builder.verificationLevel;
    this.sourcePlugin = sourcePlugin;
    this.targetPlugin = targetPlugin;
    this.sourceEngine = sourceEngine;
    this.targetEngine = targetEngine;
    this.testData = Collections.unmodifiableMap(new HashMap<>(builder.testData));
    this.comparisonConfig = builder.comparisonConfig;
    this.pluginCatalog = pluginCatalog;
    this.ownsCatalog = ownsCatalog;
  }

  /**
   * Runs the test suite: reads all .sql files from the query directory, translates each,
   * and verifies at the configured level.
   *
   * <p>For each query file, the pipeline is:
   * <ol>
   *   <li><b>TRANSLATION:</b> {@code sourcePlugin.toRelNode(sql)} then
   *       {@code targetPlugin.toDialectSql(relNode)}</li>
   *   <li><b>EXPLAIN:</b> (1) + {@code targetEngine.explain(translatedSql)}</li>
   *   <li><b>RESULT_SET:</b> (1) + (2) + {@code sourceEngine.execute(sourceSql)} vs
   *       {@code targetEngine.execute(translatedSql)}, compared via
   *       {@code ResultSetComparator}</li>
   * </ol>
   *
   * <p>Engine lifecycle is managed automatically: start() is called before the first query,
   * and stop() is called after the last query, even if exceptions occur.
   *
   * @return the test report with per-query results and aggregate statistics
   */
  public TestReport run() {
    List<QueryFile> queryFiles = discoverQueries(queryDir);
    boolean needSourceEngine = verificationLevel == VerificationLevel.RESULT_SET;
    boolean needTargetEngine = verificationLevel.ordinal() >= VerificationLevel.EXPLAIN.ordinal();

    List<QueryTestResult> results = new ArrayList<>();
    try {
      if (needSourceEngine) {
        sourceEngine.start();
      }
      if (needTargetEngine) {
        targetEngine.start();
      }

      if (verificationLevel == VerificationLevel.RESULT_SET) {
        seedEngines();
      }

      ResultSetComparator comparator = new ResultSetComparator(comparisonConfig);

      for (QueryFile q : queryFiles) {
        results.add(runOne(q, comparator));
      }
    } finally {
      // Use Throwable, not RuntimeException, so a JVM-level Error thrown from one
      // engine's teardown doesn't prevent the other engine from getting its stop().
      // build() has already validated that engines are non-null whenever their need-flag
      // is set, so no null checks needed here.
      if (needSourceEngine) {
        try {
          sourceEngine.stop();
        } catch (Throwable ignored) {
        }
      }
      if (needTargetEngine) {
        try {
          targetEngine.stop();
        } catch (Throwable ignored) {
        }
      }
      // The plugin classloaders are owned by the PluginCatalog. If this suite owns the
      // catalog (because the builder created one for us), close it — that releases every
      // loader. If the caller supplied a catalog, leave its lifecycle alone.
      if (ownsCatalog) {
        try {
          pluginCatalog.close();
        } catch (Throwable ignored) {
        }
      }
    }

    return new TestReport(source, target, verificationLevel, results);
  }

  private QueryTestResult runOne(QueryFile q, ResultSetComparator comparator) {
    QueryTestResult.Builder b = QueryTestResult.builder(q.name, q.sql, verificationLevel);
    String translatedSql;
    try {
      RelNode rel = sourcePlugin.toRelNode(q.sql);
      translatedSql = targetPlugin.toDialectSql(rel);
      b.translatedSql(translatedSql);
    } catch (Exception e) {
      return b.status(QueryTestResult.Status.FAIL).failureCategory(QueryTestResult.FailureCategory.TRANSLATION_ERROR)
          .errorMessage(e.getMessage()).exception(e).build();
    }

    if (verificationLevel.ordinal() >= VerificationLevel.EXPLAIN.ordinal()) {
      ExplainResult explain;
      try {
        explain = targetEngine.explain(translatedSql);
      } catch (Exception e) {
        explain = ExplainResult.failure(e.getMessage(), e);
      }
      b.explainResult(explain);
      if (!explain.isSuccess()) {
        return b.status(QueryTestResult.Status.FAIL).failureCategory(QueryTestResult.FailureCategory.EXPLAIN_FAILURE)
            .errorMessage(explain.getErrorMessage().orElse("EXPLAIN failed"))
            .exception(explain.getException().orElse(null)).build();
      }
    }

    if (verificationLevel == VerificationLevel.RESULT_SET) {
      ResultSet sourceRs;
      ResultSet targetRs;
      try {
        sourceRs = sourceEngine.execute(q.sql);
        targetRs = targetEngine.execute(translatedSql);
      } catch (Exception e) {
        return b.status(QueryTestResult.Status.FAIL).failureCategory(QueryTestResult.FailureCategory.RESULT_MISMATCH)
            .errorMessage("Execution failed: " + e.getMessage()).exception(e).build();
      }
      ComparisonResult cmp = comparator.compare(sourceRs, targetRs);
      b.comparisonResult(cmp);
      if (!cmp.isEquivalent()) {
        return b.status(QueryTestResult.Status.FAIL).failureCategory(QueryTestResult.FailureCategory.RESULT_MISMATCH)
            .errorMessage(cmp.getSummary()).build();
      }
    }

    return b.status(QueryTestResult.Status.PASS).build();
  }

  private void seedEngines() {
    for (Map.Entry<String, RowSet> e : testData.entrySet()) {
      String qname = e.getKey();
      int dot = qname.indexOf('.');
      if (dot <= 0 || dot == qname.length() - 1) {
        throw new IllegalStateException("Test data key must be 'namespace.table', got: " + qname);
      }
      String ns = qname.substring(0, dot);
      String table = qname.substring(dot + 1);
      CoralTable ct = catalog.getTable(ns, table);
      if (ct == null) {
        throw new IllegalStateException("Catalog does not contain table " + qname);
      }
      StructType schema = (StructType) ct.getSchema();
      sourceEngine.createTable(ns, table, schema);
      targetEngine.createTable(ns, table, schema);
      sourceEngine.loadData(ns, table, e.getValue());
      targetEngine.loadData(ns, table, e.getValue());
    }
  }

  private static List<QueryFile> discoverQueries(String queryDir) {
    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    if (cl == null) {
      cl = TranslationTestSuite.class.getClassLoader();
    }
    URL url = cl.getResource(queryDir);
    if (url == null) {
      throw new IllegalStateException("Query directory not found on classpath: " + queryDir);
    }
    try {
      URI uri = url.toURI();
      FileSystem jarFs = null;
      Path dir;
      try {
        if ("jar".equals(uri.getScheme())) {
          try {
            dir = Paths.get(uri);
          } catch (java.nio.file.FileSystemNotFoundException fsnfe) {
            jarFs = FileSystems.newFileSystem(uri, Collections.emptyMap());
            dir = Paths.get(uri);
          }
        } else {
          dir = Paths.get(uri);
        }
        List<QueryFile> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
          List<Path> files =
              s.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().collect(Collectors.toList());
          for (Path p : files) {
            String name = p.getFileName().toString().replaceFirst("\\.sql$", "");
            String sql = new String(Files.readAllBytes(p), StandardCharsets.UTF_8).trim();
            if (sql.endsWith(";")) {
              sql = sql.substring(0, sql.length() - 1).trim();
            }
            out.add(new QueryFile(name, sql));
          }
        }
        return out;
      } finally {
        if (jarFs != null) {
          jarFs.close();
        }
      }
    } catch (URISyntaxException | IOException ex) {
      throw new UncheckedIOException(new IOException("Failed to enumerate " + queryDir, ex));
    }
  }

  private static final class QueryFile {
    final String name;
    final String sql;

    QueryFile(String name, String sql) {
      this.name = name;
      this.sql = sql;
    }
  }

  /**
   * Creates a new builder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Builder for {@link TranslationTestSuite}.
   *
   * <p>Required for all levels: source, target, catalog, queryDir, verificationLevel.
   * <p>Required for RESULT_SET: at least one {@link #testData} entry.
   *
   * <p>Plugins come from a {@link PluginCatalog}, which the builder either inherits
   * via {@link #pluginCatalog} or constructs by default via
   * {@link PluginCatalog#discoverFromSystemProperties()}. The builder names what it
   * needs by {@link Engine} and {@link Dialect}; deployment specifics (jar URLs,
   * plugin module names) stay in the catalog.
   */
  public static final class Builder {
    private Engine sourceEngineId;
    private Dialect sourceDialect;
    private Engine targetEngineId;
    private Dialect targetDialect;
    private CoralCatalog catalog;
    private String queryDir;
    private VerificationLevel verificationLevel;
    private PluginCatalog pluginCatalog;
    private final Map<String, RowSet> testData = new HashMap<>();
    private ComparisonConfig comparisonConfig = ComparisonConfig.defaults();

    private Builder() {
    }

    /**
     * Sets the source side of the scenario: the engine that executes the source SQL,
     * and the SQL dialect to parse it as.
     *
     * @param engine  the engine that executes the source SQL
     * @param dialect the source SQL dialect
     * @return this builder
     */
    public Builder source(Engine engine, Dialect dialect) {
      this.sourceEngineId = Objects.requireNonNull(engine, "engine");
      this.sourceDialect = Objects.requireNonNull(dialect, "dialect");
      return this;
    }

    /**
     * Sets the target side of the scenario: the engine that executes the translated
     * SQL, and the SQL dialect to translate into.
     *
     * @param engine  the engine that executes the translated SQL
     * @param dialect the target SQL dialect
     * @return this builder
     */
    public Builder target(Engine engine, Dialect dialect) {
      this.targetEngineId = Objects.requireNonNull(engine, "engine");
      this.targetDialect = Objects.requireNonNull(dialect, "dialect");
      return this;
    }

    /**
     * Sets the catalog providing table metadata for query resolution.
     */
    public Builder catalog(CoralCatalog catalog) {
      this.catalog = Objects.requireNonNull(catalog);
      return this;
    }

    /**
     * Sets the directory containing .sql query files (classpath-relative).
     */
    public Builder queryDir(String queryDir) {
      this.queryDir = Objects.requireNonNull(queryDir);
      return this;
    }

    /**
     * Sets the verification level.
     */
    public Builder verificationLevel(VerificationLevel level) {
      this.verificationLevel = Objects.requireNonNull(level);
      return this;
    }

    /**
     * Overrides the {@link PluginCatalog}. By default the builder uses
     * {@link PluginCatalog#discoverFromSystemProperties()} and owns its lifecycle
     * (closes it when the suite finishes). Supplying a catalog here transfers
     * lifecycle ownership to the caller.
     */
    public Builder pluginCatalog(PluginCatalog catalog) {
      this.pluginCatalog = Objects.requireNonNull(catalog);
      return this;
    }

    /**
     * Adds test data for a table (key: {@code "namespace.table"}). Required for
     * {@link VerificationLevel#RESULT_SET}.
     */
    public Builder testData(String qualifiedTableName, RowSet data) {
      Objects.requireNonNull(qualifiedTableName);
      Objects.requireNonNull(data);
      this.testData.put(qualifiedTableName, data);
      return this;
    }

    /**
     * Adds test data for multiple tables at once.
     */
    public Builder testData(Map<String, RowSet> testData) {
      Objects.requireNonNull(testData);
      this.testData.putAll(testData);
      return this;
    }

    /**
     * Sets the comparison config for result-set comparison. Defaults to
     * {@link ComparisonConfig#defaults()} if not set.
     */
    public Builder comparisonConfig(ComparisonConfig config) {
      this.comparisonConfig = Objects.requireNonNull(config);
      return this;
    }

    /**
     * Builds the test suite, validating that all required configuration is present
     * and that every needed plugin is available in the catalog.
     */
    public TranslationTestSuite build() {
      Objects.requireNonNull(sourceEngineId, "Source engine is required — call .source(engine, dialect)");
      Objects.requireNonNull(sourceDialect, "Source dialect is required — call .source(engine, dialect)");
      Objects.requireNonNull(targetEngineId, "Target engine is required — call .target(engine, dialect)");
      Objects.requireNonNull(targetDialect, "Target dialect is required — call .target(engine, dialect)");
      Objects.requireNonNull(catalog, "Catalog is required");
      Objects.requireNonNull(queryDir, "Query directory is required");
      Objects.requireNonNull(verificationLevel, "Verification level is required");

      boolean needTargetEngine = verificationLevel.ordinal() >= VerificationLevel.EXPLAIN.ordinal();
      boolean needSourceEngine = verificationLevel == VerificationLevel.RESULT_SET;
      if (verificationLevel == VerificationLevel.RESULT_SET && testData.isEmpty()) {
        throw new IllegalStateException("Test data is required for RESULT_SET verification");
      }

      PluginCatalog resolvedCatalog;
      boolean ownsCatalog;
      if (pluginCatalog != null) {
        resolvedCatalog = pluginCatalog;
        ownsCatalog = false;
      } else {
        resolvedCatalog = PluginCatalog.discoverFromSystemProperties(TranslationTestSuite.class.getClassLoader());
        ownsCatalog = true;
      }

      try {
        DialectPlugin resolvedSourcePlugin = resolvedCatalog.createDialectPlugin(sourceDialect, catalog);
        DialectPlugin resolvedTargetPlugin = resolvedCatalog.createDialectPlugin(targetDialect, catalog);
        EnginePlugin resolvedSourceEngine =
            needSourceEngine ? resolvedCatalog.createEnginePlugin(sourceEngineId) : null;
        EnginePlugin resolvedTargetEngine =
            needTargetEngine ? resolvedCatalog.createEnginePlugin(targetEngineId) : null;

        return new TranslationTestSuite(this, resolvedSourcePlugin, resolvedTargetPlugin, resolvedSourceEngine,
            resolvedTargetEngine, resolvedCatalog, ownsCatalog);
      } catch (RuntimeException e) {
        // Resolution failed after we opened our own catalog — close it before propagating.
        if (ownsCatalog) {
          try {
            resolvedCatalog.close();
          } catch (RuntimeException ignored) {
          }
        }
        throw e;
      }
    }
  }
}
