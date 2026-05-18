/**
 * Copyright 2017-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.benchmark.suite;

import java.io.Closeable;
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
import com.linkedin.coral.benchmark.plugin.PluginRegistry;
import com.linkedin.coral.benchmark.spi.Dialect;
import com.linkedin.coral.benchmark.spi.DialectPlugin;
import com.linkedin.coral.benchmark.spi.EnginePlugin;
import com.linkedin.coral.benchmark.spi.PluginKind;
import com.linkedin.coral.benchmark.spi.VerificationLevel;
import com.linkedin.coral.common.catalog.CoralCatalog;
import com.linkedin.coral.common.catalog.CoralTable;
import com.linkedin.coral.common.types.StructType;


/**
 * Main orchestrator for cross-dialect translation benchmark tests.
 *
 * <p>A test suite is parameterized by source dialect, target dialect, verification level,
 * the catalog, and the runtime classpath URLs of the dialect / engine plugins it needs. It
 * reads all {@code .sql} files from the configured query directory, translates each
 * through the Coral IR pipeline, and verifies the result at the configured level.
 *
 * <p>Every plugin is materialized inside its own {@link PluginRegistry}-managed
 * classloader — there is intentionally no path that lets a caller hand in a pre-built
 * {@link DialectPlugin} or {@link EnginePlugin} instance, because that would bypass the
 * classpath isolation the harness exists to provide.
 *
 * <p>Usage:
 * <pre>{@code
 * TranslationTestSuite suite = TranslationTestSuite.builder()
 *     .source(Dialect.SPARK_SQL)
 *     .target(Dialect.TRINO_SQL)
 *     .catalog(catalog)
 *     .queryDir("queries/spark_sql")
 *     .verificationLevel(VerificationLevel.RESULT_SET)
 *     .pluginJars(Dialect.SPARK_SQL, PluginKind.DIALECT, sparkDialectJars)
 *     .pluginJars(Dialect.TRINO_SQL, PluginKind.DIALECT, trinoDialectJars)
 *     .pluginJars(Dialect.SPARK_SQL, PluginKind.ENGINE, sparkEngineJars)
 *     .pluginJars(Dialect.TRINO_SQL, PluginKind.ENGINE, trinoEngineJars)
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

  private TranslationTestSuite(Builder builder, DialectPlugin sourcePlugin, DialectPlugin targetPlugin,
      EnginePlugin sourceEngine, EnginePlugin targetEngine) {
    this.source = builder.source;
    this.target = builder.target;
    this.catalog = builder.catalog;
    this.queryDir = builder.queryDir;
    this.verificationLevel = builder.verificationLevel;
    this.sourcePlugin = sourcePlugin;
    this.targetPlugin = targetPlugin;
    this.sourceEngine = sourceEngine;
    this.targetEngine = targetEngine;
    this.testData = Collections.unmodifiableMap(new HashMap<>(builder.testData));
    this.comparisonConfig = builder.comparisonConfig;
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
        closeQuietly(sourceEngine);
      }
      if (needTargetEngine) {
        try {
          targetEngine.stop();
        } catch (Throwable ignored) {
        }
        closeQuietly(targetEngine);
      }
      // Dialect plugins don't have a stop() method but they DO hold a PluginClassLoader
      // when loaded via PluginRegistry; closing releases the loader and unblocks GC of
      // any cached converter state.
      closeQuietly(sourcePlugin);
      closeQuietly(targetPlugin);
    }

    return new TestReport(source, target, verificationLevel, results);
  }

  private static void closeQuietly(Object o) {
    if (o instanceof Closeable) {
      try {
        ((Closeable) o).close();
      } catch (Throwable ignored) {
      }
    }
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
   * <p>Required for all levels: source, target, catalog, queryDir, verificationLevel,
   * and {@link #pluginJars} with {@link PluginKind#DIALECT} for both source and target.
   * <p>Required for EXPLAIN: {@link #pluginJars} with {@link PluginKind#ENGINE} for the
   * target dialect.
   * <p>Required for RESULT_SET: {@link #pluginJars} with {@link PluginKind#ENGINE} for
   * both source and target, plus {@link #testData}.
   *
   * <p>Plugins are always materialized inside a {@link PluginRegistry}-managed
   * classloader, rooted at the supplied jar URLs, so the harness's classpath-isolation
   * guarantees hold for every code path.
   */
  public static final class Builder {
    private Dialect source;
    private Dialect target;
    private CoralCatalog catalog;
    private String queryDir;
    private VerificationLevel verificationLevel;
    private final Map<PluginKind, Map<Dialect, List<java.net.URL>>> pluginJars = newKindMap();
    private PluginRegistry pluginRegistry;
    private final Map<String, RowSet> testData = new HashMap<>();
    private ComparisonConfig comparisonConfig = ComparisonConfig.defaults();

    private static Map<PluginKind, Map<Dialect, List<java.net.URL>>> newKindMap() {
      Map<PluginKind, Map<Dialect, List<java.net.URL>>> m = new java.util.EnumMap<>(PluginKind.class);
      for (PluginKind k : PluginKind.values()) {
        m.put(k, new HashMap<>());
      }
      return m;
    }

    private Builder() {
    }

    /**
     * Sets the source dialect.
     *
     * @param source the dialect of the input queries
     * @return this builder
     */
    public Builder source(Dialect source) {
      this.source = Objects.requireNonNull(source);
      return this;
    }

    /**
     * Sets the target dialect.
     *
     * @param target the dialect to translate queries into
     * @return this builder
     */
    public Builder target(Dialect target) {
      this.target = Objects.requireNonNull(target);
      return this;
    }

    /**
     * Sets the catalog providing table metadata for query resolution.
     *
     * @param catalog the catalog (typically an {@link com.linkedin.coral.benchmark.catalog.InMemoryCatalog})
     * @return this builder
     */
    public Builder catalog(CoralCatalog catalog) {
      this.catalog = Objects.requireNonNull(catalog);
      return this;
    }

    /**
     * Sets the directory containing .sql query files.
     * Path is relative to the classpath (test resources).
     *
     * @param queryDir the directory path (e.g., "queries/hive")
     * @return this builder
     */
    public Builder queryDir(String queryDir) {
      this.queryDir = Objects.requireNonNull(queryDir);
      return this;
    }

    /**
     * Sets the verification level.
     *
     * @param level the level of verification to perform
     * @return this builder
     */
    public Builder verificationLevel(VerificationLevel level) {
      this.verificationLevel = Objects.requireNonNull(level);
      return this;
    }

    /**
     * Registers the runtime classpath for a plugin. The plugin is materialized inside an
     * isolated {@link PluginRegistry} classloader rooted at the supplied jar URLs, and
     * every call into it swaps the thread context classloader so the plugin's internal
     * {@link java.util.ServiceLoader} / reflective lookups land inside its own jars.
     *
     * @param dialect the dialect the plugin handles
     * @param kind    whether this is a {@link PluginKind#DIALECT translator} or
     *                {@link PluginKind#ENGINE execution} plugin
     * @param jars    the plugin module's full runtime classpath (jar URLs)
     * @return this builder
     */
    public Builder pluginJars(Dialect dialect, PluginKind kind, List<java.net.URL> jars) {
      Objects.requireNonNull(dialect);
      Objects.requireNonNull(kind);
      Objects.requireNonNull(jars);
      this.pluginJars.get(kind).put(dialect, jars);
      return this;
    }

    /**
     * Overrides the {@link PluginRegistry} used to load isolated plugins. By default a
     * registry rooted at {@code TranslationTestSuite}'s own classloader is used.
     *
     * @param registry the registry
     * @return this builder
     */
    public Builder pluginRegistry(PluginRegistry registry) {
      this.pluginRegistry = Objects.requireNonNull(registry);
      return this;
    }

    /**
     * Adds test data for a table. The key is the fully qualified table name
     * (e.g., "db.users"). Required for {@link VerificationLevel#RESULT_SET}.
     *
     * @param qualifiedTableName the fully qualified table name ("namespace.table")
     * @param data               the row data
     * @return this builder
     */
    public Builder testData(String qualifiedTableName, RowSet data) {
      Objects.requireNonNull(qualifiedTableName);
      Objects.requireNonNull(data);
      this.testData.put(qualifiedTableName, data);
      return this;
    }

    /**
     * Adds test data for multiple tables at once.
     *
     * @param testData a map from fully qualified table names to row data
     * @return this builder
     */
    public Builder testData(Map<String, RowSet> testData) {
      Objects.requireNonNull(testData);
      this.testData.putAll(testData);
      return this;
    }

    /**
     * Sets the comparison config for result-set comparison. Defaults to
     * {@link ComparisonConfig#defaults()} if not set.
     *
     * @param config the comparison config
     * @return this builder
     */
    public Builder comparisonConfig(ComparisonConfig config) {
      this.comparisonConfig = Objects.requireNonNull(config);
      return this;
    }

    /**
     * Builds the test suite, validating that all required configuration is present
     * for the requested verification level.
     *
     * @return a new TranslationTestSuite
     * @throws IllegalStateException if required configuration is missing
     */
    public TranslationTestSuite build() {
      Objects.requireNonNull(source, "Source dialect is required");
      Objects.requireNonNull(target, "Target dialect is required");
      Objects.requireNonNull(catalog, "Catalog is required");
      Objects.requireNonNull(queryDir, "Query directory is required");
      Objects.requireNonNull(verificationLevel, "Verification level is required");

      boolean needTargetEngine = verificationLevel.ordinal() >= VerificationLevel.EXPLAIN.ordinal();
      boolean needSourceEngine = verificationLevel == VerificationLevel.RESULT_SET;

      Map<Dialect, List<java.net.URL>> dialectJars = pluginJars.get(PluginKind.DIALECT);
      Map<Dialect, List<java.net.URL>> engineJars = pluginJars.get(PluginKind.ENGINE);
      requireJars(dialectJars, source, PluginKind.DIALECT, "source");
      requireJars(dialectJars, target, PluginKind.DIALECT, "target");
      if (needTargetEngine) {
        requireJars(engineJars, target, PluginKind.ENGINE, "target (required for " + verificationLevel + ")");
      }
      if (needSourceEngine) {
        requireJars(engineJars, source, PluginKind.ENGINE, "source (required for RESULT_SET)");
      }
      if (verificationLevel == VerificationLevel.RESULT_SET && testData.isEmpty()) {
        throw new IllegalStateException("Test data is required for RESULT_SET verification");
      }

      PluginRegistry registry =
          pluginRegistry != null ? pluginRegistry : new PluginRegistry(TranslationTestSuite.class.getClassLoader());

      DialectPlugin resolvedSourcePlugin = registry.loadDialectPlugin(source, dialectJars.get(source), catalog);
      DialectPlugin resolvedTargetPlugin = registry.loadDialectPlugin(target, dialectJars.get(target), catalog);
      EnginePlugin resolvedSourceEngine =
          needSourceEngine ? registry.loadEnginePlugin(source, engineJars.get(source)) : null;
      EnginePlugin resolvedTargetEngine =
          needTargetEngine ? registry.loadEnginePlugin(target, engineJars.get(target)) : null;

      return new TranslationTestSuite(this, resolvedSourcePlugin, resolvedTargetPlugin, resolvedSourceEngine,
          resolvedTargetEngine);
    }

    private static void requireJars(Map<Dialect, List<java.net.URL>> jars, Dialect dialect, PluginKind kind,
        String role) {
      if (!jars.containsKey(dialect)) {
        throw new IllegalStateException("Plugin classpath for " + dialect + " (" + kind
            + ") is required — call pluginJars(" + role + ", " + kind + ", ...) on the builder.");
      }
    }
  }
}
