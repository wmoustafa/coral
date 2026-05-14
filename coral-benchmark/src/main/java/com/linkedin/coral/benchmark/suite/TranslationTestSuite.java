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
import java.util.ServiceLoader;
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
import com.linkedin.coral.benchmark.spi.DialectPluginProvider;
import com.linkedin.coral.benchmark.spi.EnginePlugin;
import com.linkedin.coral.benchmark.spi.VerificationLevel;
import com.linkedin.coral.common.catalog.CoralCatalog;
import com.linkedin.coral.common.catalog.CoralTable;
import com.linkedin.coral.common.types.StructType;


/**
 * Main orchestrator for cross-dialect translation benchmark tests.
 *
 * <p>A test suite is parameterized by source dialect, target dialect, verification level,
 * and the catalog/data/engines needed for that level. It reads all {@code .sql} files from
 * a query directory, translates each through the Coral IR pipeline, and verifies the result
 * at the configured level.
 *
 * <p>Usage (Level 1 - translation only):
 * <pre>{@code
 * TranslationTestSuite suite = TranslationTestSuite.builder()
 *     .source(Dialect.HIVE)
 *     .target(Dialect.TRINO)
 *     .catalog(catalog)
 *     .queryDir("queries/hive")
 *     .verificationLevel(VerificationLevel.TRANSLATION)
 *     .build();
 *
 * TestReport report = suite.run();
 * }</pre>
 *
 * <p>Usage (Level 2 - EXPLAIN):
 * <pre>{@code
 * TranslationTestSuite suite = TranslationTestSuite.builder()
 *     .source(Dialect.HIVE)
 *     .target(Dialect.TRINO)
 *     .catalog(catalog)
 *     .queryDir("queries/hive")
 *     .verificationLevel(VerificationLevel.EXPLAIN)
 *     .targetEngine(new TrinoEnginePlugin())
 *     .build();
 *
 * TestReport report = suite.run();
 * }</pre>
 *
 * <p>Usage (Level 3 - result set comparison):
 * <pre>{@code
 * TranslationTestSuite suite = TranslationTestSuite.builder()
 *     .source(Dialect.HIVE)
 *     .target(Dialect.TRINO)
 *     .catalog(catalog)
 *     .queryDir("queries/hive")
 *     .verificationLevel(VerificationLevel.RESULT_SET)
 *     .testData("db.users", userData)
 *     .testData("db.events", eventData)
 *     .sourceEngine(new SparkEnginePlugin())
 *     .targetEngine(new TrinoEnginePlugin())
 *     .comparisonConfig(ComparisonConfig.builder()
 *         .floatingPointEpsilon(1e-6)
 *         .build())
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

  private TranslationTestSuite(Builder builder, DialectPlugin sourcePlugin, DialectPlugin targetPlugin) {
    this.source = builder.source;
    this.target = builder.target;
    this.catalog = builder.catalog;
    this.queryDir = builder.queryDir;
    this.verificationLevel = builder.verificationLevel;
    this.sourcePlugin = sourcePlugin;
    this.targetPlugin = targetPlugin;
    this.sourceEngine = builder.sourceEngine;
    this.targetEngine = builder.targetEngine;
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
      if (needSourceEngine && sourceEngine != null) {
        try {
          sourceEngine.stop();
        } catch (RuntimeException ignored) {
        }
      }
      if (needTargetEngine && targetEngine != null) {
        try {
          targetEngine.stop();
        } catch (RuntimeException ignored) {
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
   * <p>Required for EXPLAIN: targetEngine.
   * <p>Required for RESULT_SET: sourceEngine, targetEngine, testData.
   *
   * <p>Dialect plugins are resolved by matching {@link DialectPluginProvider#dialect()}
   * against the configured source and target dialects, using
   * {@link java.util.ServiceLoader} discovery by default. Callers may override either
   * provider explicitly via {@link #sourcePluginProvider} or {@link #targetPluginProvider}
   * (e.g. for tests, or to inject a non-discovered implementation). The framework calls
   * {@code provider.create(catalog)} during {@link #build()} to materialize the plugin.
   */
  public static final class Builder {
    private Dialect source;
    private Dialect target;
    private CoralCatalog catalog;
    private String queryDir;
    private VerificationLevel verificationLevel;
    private DialectPluginProvider sourcePluginProvider;
    private DialectPluginProvider targetPluginProvider;
    private EnginePlugin sourceEngine;
    private EnginePlugin targetEngine;
    private final Map<Dialect, List<java.net.URL>> dialectJars = new HashMap<>();
    private final Map<Dialect, List<java.net.URL>> engineJars = new HashMap<>();
    private PluginRegistry pluginRegistry;
    private final Map<String, RowSet> testData = new HashMap<>();
    private ComparisonConfig comparisonConfig = ComparisonConfig.defaults();

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
     * Explicitly sets the provider used to construct the source dialect plugin. If not
     * set, the provider is resolved via {@link java.util.ServiceLoader} based on the
     * source dialect.
     *
     * @param provider the source dialect plugin provider
     * @return this builder
     */
    public Builder sourcePluginProvider(DialectPluginProvider provider) {
      this.sourcePluginProvider = Objects.requireNonNull(provider);
      return this;
    }

    /**
     * Explicitly sets the provider used to construct the target dialect plugin. If not
     * set, the provider is resolved via {@link java.util.ServiceLoader} based on the
     * target dialect.
     *
     * @param provider the target dialect plugin provider
     * @return this builder
     */
    public Builder targetPluginProvider(DialectPluginProvider provider) {
      this.targetPluginProvider = Objects.requireNonNull(provider);
      return this;
    }

    /**
     * Sets the engine for executing queries in the source dialect.
     * Required for {@link VerificationLevel#RESULT_SET}.
     *
     * @param engine the source engine
     * @return this builder
     */
    public Builder sourceEngine(EnginePlugin engine) {
      this.sourceEngine = Objects.requireNonNull(engine);
      return this;
    }

    /**
     * Sets the engine for executing queries in the target dialect.
     * Required for {@link VerificationLevel#EXPLAIN} and {@link VerificationLevel#RESULT_SET}.
     *
     * @param engine the target engine
     * @return this builder
     */
    public Builder targetEngine(EnginePlugin engine) {
      this.targetEngine = Objects.requireNonNull(engine);
      return this;
    }

    /**
     * Registers the runtime classpath for a dialect plugin. When set, the framework
     * materializes that dialect's plugin inside an isolated {@link PluginRegistry}
     * classloader instead of using a directly-injected provider. This is what lets
     * Spark and Trino plugins coexist without their conflicting transitive deps
     * (Jackson, Avatica, SLF4J, runtime jars) colliding on a single classpath.
     *
     * @param dialect the dialect the jars provide
     * @param jars    the plugin module's full runtime classpath (jar URLs)
     * @return this builder
     */
    public Builder dialectPluginJars(Dialect dialect, List<java.net.URL> jars) {
      Objects.requireNonNull(dialect);
      Objects.requireNonNull(jars);
      this.dialectJars.put(dialect, jars);
      return this;
    }

    /**
     * Registers the runtime classpath for an engine plugin. The engine is instantiated
     * inside an isolated {@link PluginRegistry} classloader via its
     * {@link com.linkedin.coral.benchmark.spi.EnginePluginProvider}, and every call into
     * the engine swaps the thread context classloader so Spark/Trino's internal lookups
     * land inside their own jars.
     *
     * @param dialect the dialect the engine runs natively
     * @param jars    the engine module's full runtime classpath (jar URLs)
     * @return this builder
     */
    public Builder enginePluginJars(Dialect dialect, List<java.net.URL> jars) {
      Objects.requireNonNull(dialect);
      Objects.requireNonNull(jars);
      this.engineJars.put(dialect, jars);
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

      if (needTargetEngine && targetEngine == null && !engineJars.containsKey(target)) {
        throw new IllegalStateException(
            "Target engine is required for verification level " + verificationLevel + " — supply one via "
                + "targetEngine(EnginePlugin) or enginePluginJars(target, jars).");
      }
      if (needSourceEngine && sourceEngine == null && !engineJars.containsKey(source)) {
        throw new IllegalStateException(
            "Source engine is required for RESULT_SET verification — supply one via "
                + "sourceEngine(EnginePlugin) or enginePluginJars(source, jars).");
      }
      if (verificationLevel == VerificationLevel.RESULT_SET && testData.isEmpty()) {
        throw new IllegalStateException("Test data is required for RESULT_SET verification");
      }

      PluginRegistry registry = pluginRegistry;
      if (registry == null && (!dialectJars.isEmpty() || !engineJars.isEmpty())) {
        registry = new PluginRegistry(TranslationTestSuite.class.getClassLoader());
      }

      DialectPlugin resolvedSourcePlugin = resolveDialectPlugin(source, registry, sourcePluginProvider);
      DialectPlugin resolvedTargetPlugin = resolveDialectPlugin(target, registry, targetPluginProvider);

      EnginePlugin resolvedSourceEngine = sourceEngine;
      if (needSourceEngine && resolvedSourceEngine == null) {
        resolvedSourceEngine = registry.loadEnginePlugin(source, engineJars.get(source));
      }
      EnginePlugin resolvedTargetEngine = targetEngine;
      if (needTargetEngine && resolvedTargetEngine == null) {
        resolvedTargetEngine = registry.loadEnginePlugin(target, engineJars.get(target));
      }
      this.sourceEngine = resolvedSourceEngine;
      this.targetEngine = resolvedTargetEngine;

      return new TranslationTestSuite(this, resolvedSourcePlugin, resolvedTargetPlugin);
    }

    private DialectPlugin resolveDialectPlugin(Dialect dialect, PluginRegistry registry,
        DialectPluginProvider explicitProvider) {
      if (explicitProvider != null) {
        return explicitProvider.create(catalog);
      }
      if (dialectJars.containsKey(dialect)) {
        if (registry == null) {
          registry = new PluginRegistry(TranslationTestSuite.class.getClassLoader());
        }
        return registry.loadDialectPlugin(dialect, dialectJars.get(dialect), catalog);
      }
      return resolveProvider(dialect).create(catalog);
    }

    private static DialectPluginProvider resolveProvider(Dialect dialect) {
      for (DialectPluginProvider provider : ServiceLoader.load(DialectPluginProvider.class)) {
        if (provider.dialect() == dialect) {
          return provider;
        }
      }
      throw new IllegalStateException("No DialectPluginProvider registered for dialect " + dialect
          + ". Set one explicitly via sourcePluginProvider/targetPluginProvider, register the plugin's classpath via "
          + "dialectPluginJars(...), or add a META-INF/services/" + DialectPluginProvider.class.getName() + " entry.");
    }
  }
}
