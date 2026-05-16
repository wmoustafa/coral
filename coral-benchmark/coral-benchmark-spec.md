# Coral Benchmark: Cross-Dialect Integration Testing Framework

## 1. Purpose

The `coral-benchmark` module tests Coral translations end-to-end: from any supported source dialect to any supported target dialect. The framework verifies both **syntactic correctness** (the translated query is valid in the target dialect) and **semantic correctness** (the translated query produces equivalent results on real engine execution).

## 2. Design Principles

- **Grounded in existing APIs.** The framework builds on `CoralCatalog`, `CoralTable`, and the Coral type system (`CoralDataType`, `CoralTypeKind`). It does not invent parallel abstractions for catalog or types.
- **In-memory by default.** Tests run against an in-memory `CoralCatalog` implementation with no external metastore dependency.
- **Dialect-agnostic core, dialect-specific plugins.** The core framework knows nothing about Hive, Spark, or Trino. Each dialect contributes an implementation of a small SPI that the core orchestrates.
- **One plugin-resolution path: jar-URL classpath isolation.** Every dialect and engine plugin is materialized inside its own `URLClassLoader`-derived `PluginClassLoader` rooted at jar URLs the caller supplies. There is intentionally no path that accepts a pre-built plugin instance, because that would bypass the classpath isolation that the harness exists to provide.
- **Incremental verification levels.** Users choose the level of verification appropriate to their needs, from pure IR round-trip checks up to full result-set comparison on live engines.

## 3. Core Concepts

The framework is organized around a single orchestrator (`TranslationTestSuite`) that
drives a corpus of SQL queries through pluggable translation and execution stages,
comparing results via a configurable comparator. The major components:

```
                       +-----------------------------+
                       |   TranslationTestSuite      |
                       |       (orchestrator)        |
                       +--+--------+--------+--------+
                          |        |        |
                    uses  |        |        |  uses
                          v        v        v
                +-----------+ +------------+ +---------------------+
                |  Catalog  | | PluginReg- | | ResultSetComparator |
                | (schemas) | |  istry +   | |   (Level 3 only)    |
                |           | | classloader| |                     |
                +-----------+ +-----+------+ +---------------------+
                                    |
                          +---------+---------+
                          v                   v
                +-----------------+ +-----------------+
                | DialectPlugin   | | EnginePlugin    |
                | (per-plugin     | | (per-plugin     |
                |  classloader)   | |  classloader)   |
                +-----------------+ +-----------------+
```

The orchestrator iterates over `.sql` files from the configured query directory,
runs each through the appropriate plugins for the requested verification level, and
emits a `QueryTestResult` per query plus an aggregate `TestReport`.

### 3.1 Catalog Setup

Tests declare their table schemas using the Coral type system and register them in an in-memory catalog.

**In-memory catalog.** A concrete `InMemoryCatalog implements CoralCatalog` that holds tables in a `Map<namespace, Map<tableName, CoralTable>>`. Provides a builder API for test ergonomics:

```java
InMemoryCatalog catalog = InMemoryCatalog.builder()
    .createNamespace("default")
    .addTable("default", "users", StructType.of(Arrays.asList(
        StructField.of("id",   PrimitiveType.of(CoralTypeKind.INT, true)),
        StructField.of("name", PrimitiveType.of(CoralTypeKind.STRING, true))
    ), true))
    .build();
```

All column types are expressed through the existing Coral type hierarchy (`PrimitiveType`, `StructType`, `ArrayType`, `MapType`, `DecimalType`, `TimestampType`, etc.). No raw strings for types.

Plain `CoralTable` implementations like `InMemoryTable` resolve through Calcite via `com.linkedin.coral.common.GenericCoralTableAdapter` — a fallback added to `CoralDatabaseSchema` so consumers other than `HiveTable` / `IcebergTable` work end-to-end.

### 3.2 Query Corpus

Queries are plain SQL SELECT statements stored as individual `.sql` files organized by source dialect. The directory name is the lowercased `Dialect` enum value, so `Dialect.SPARK_SQL` reads from `queries/spark_sql/`:

```
coral-benchmark/coral-benchmark-tests/src/test/resources/queries/
  spark_sql/
    select_all.sql
    select_filtered.sql
  trino_sql/
    select_all.sql
    select_filtered.sql
  negative/
    spark_sql/
      unknown_table.sql
    trino_sql/
      unknown_table.sql
```

Each file contains a single SELECT statement written in the source dialect's syntax. The same logical query may appear under multiple dialect directories, written in each dialect's native syntax.

### 3.3 Dialect SPI

Each dialect plugs into the framework via a *provider/plugin* pair. The provider is the
`ServiceLoader`-discoverable entry point (no-arg constructor); the plugin is the
fully-constructed translator bound to a catalog. This split keeps the plugin immutable
(catalog as a `final` field, no two-phase `init`) while still supporting standard SPI
discovery — the discovery just happens inside the plugin's isolated classloader, not on
the test JVM's classpath.

```java
public interface DialectPluginProvider {

    /** Identifier for the dialect this provider produces plugins for. */
    Dialect dialect();

    /** Construct a plugin bound to the given catalog. */
    DialectPlugin create(CoralCatalog catalog);
}

public interface DialectPlugin {

    /** Identifier for this dialect (HIVE_SQL, SPARK_SQL, TRINO_SQL). */
    Dialect dialect();

    /** Parse a SQL string in this dialect and produce a Coral IR RelNode. */
    RelNode toRelNode(String sql);

    /** Convert a Coral IR RelNode to a SQL string in this dialect. */
    String toDialectSql(RelNode relNode);
}
```

Plugins wrap the existing Coral converters:
- **Hive** (latent — no plugin module yet): `HiveToRelConverter` / `CoralRelToSqlNodeConverter`
- **Spark SQL** (`coral-benchmark-spark-sql-dialect`): `HiveToRelConverter` (Spark SQL parses as Hive) / `CoralSpark`
- **Trino SQL** (`coral-benchmark-trino-sql-dialect`): `TrinoToRelConverter` / `RelToTrinoConverter`

Providers are registered via `META-INF/services/com.linkedin.coral.benchmark.spi.DialectPluginProvider` inside each plugin's jar. The harness's `PluginRegistry` invokes `ServiceLoader.load(DialectPluginProvider.class, pluginClassLoader)` against the plugin's isolated classloader, so the discovered provider's classloader is always the plugin's own — never the test JVM's parent.

### 3.4 Engine SPI

For verification levels that execute queries, each engine ships an analogous provider/plugin pair:

```java
public interface EnginePluginProvider {
    Dialect dialect();
    EnginePlugin create();   // no catalog — engines manage their own
}

public interface EnginePlugin {

    /** Which dialect this engine natively executes. */
    Dialect dialect();

    /** Start the engine (boot embedded session, install connectors, etc.). */
    void start();

    /** Create the given table schema in the engine's catalog. The table must be
     *  queryable after this call returns. */
    void createTable(String namespace, String tableName, CoralDataType schema);

    /** Load row data into a previously created table. */
    void loadData(String namespace, String tableName, RowSet data);

    /** Run EXPLAIN on a query and return success/failure. Validates syntax + planning. */
    ExplainResult explain(String sql);

    /** Execute a query and return its result set. */
    ResultSet execute(String sql);

    /** Tear down the engine. */
    void stop();
}
```

Engine implementations that ship today:
- **Spark** (`coral-benchmark-spark-engine`): in-process Spark 3.5 `SparkSession.builder().master("local[2]")` with a temp warehouse directory; tables materialize as Parquet.
- **Trino** (`coral-benchmark-trino-engine`): Trino 411 `LocalQueryRunner` + in-memory connector; tables materialize via `CREATE TABLE AS SELECT` (LocalQueryRunner plans DML, not bare DDL).

### 3.5 Classpath Isolation

Spark 3.5 and Trino 411 bring incompatible Jackson, Avatica, and SLF4J versions; loading both on a single flat classpath produces `VerifyError`, `LinkageError`, or `SecurityException` (signer mismatch). The harness owns the resolution in three pieces:

1. **`PluginClassLoader`** (`com.linkedin.coral.benchmark.plugin`) — child-first `URLClassLoader` with two delegation zones:
   - *Force-parent* (always from parent classloader): `java.*`, `sun.*`, `jdk.*`, `com.linkedin.coral.benchmark.*`, `com.linkedin.coral.common.*`, `com.linkedin.coral.com.*` (Coral's shaded third-party namespace), and `org.apache.calcite.*` (because `RelNode` crosses the SPI boundary).
   - *Parent-first with fallback*: `javax.*`, `org.w3c.*`, `org.xml.*` — handles modules like `javax.servlet` that Spark bundles itself.
   - Everything else: child-first.
2. **`PluginRegistry`** — creates one `PluginClassLoader` per plugin from a `List<URL>`, uses `ServiceLoader` against that loader to find the matching provider, wraps the result in a context-classloader proxy, tracks the loader so it can be closed.
3. **Context-classloader proxies** (`ContextClassLoaderDialectPlugin` / `ContextClassLoaderEnginePlugin`) — swap the thread context classloader to the plugin's loader before every method invocation, restore afterwards. Spark and Trino consult the thread context loader for `ServiceLoader` lookups, Hadoop config discovery, and reflective class loading; without the swap those lookups land in the parent loader and fail.

The plugin module classpaths are resolved independently by Gradle, via per-plugin `Configuration` declarations in `coral-benchmark-tests/build.gradle`. Each configuration produces its own dependency tree (no cross-configuration conflict resolution) and is handed to the test JVM as a system property.

### 3.6 Test Data

A `RowSet` abstraction carries typed tabular data for loading into engines:

```java
RowSet userData = RowSet.builder(usersSchema)
    .addRow(1, "alice")
    .addRow(2, "bob")
    .build();
```

Values are Java objects matching the Coral type mapping (INT → Integer, STRING → String, ARRAY → List, MAP → Map, STRUCT → Object[], etc.).

## 4. Verification Levels

The framework supports three escalating levels of verification. Each level subsumes the
ones before it.

```
  Level 1 (TRANSLATION):

      Source SQL --[toRelNode]--> IR --[toDialectSql]--> Target SQL


  Level 2 (EXPLAIN):  Level 1, plus:

      Target SQL --[targetEngine.explain]--> ExplainResult


  Level 3 (RESULT_SET):  Level 2, plus:

      sourceEngine.execute(Source SQL) ---> ResultSet A
                                                         \
                                                          ResultSetComparator
                                                         /
      targetEngine.execute(Target SQL) ---> ResultSet B
```

| Level       | Dialect plugins (jars) | Engine plugins (jars) | Test data |
| ----------- | ---------------------- | --------------------- | --------- |
| TRANSLATION | source + target        | none                  | no        |
| EXPLAIN     | source + target        | target                | no        |
| RESULT_SET  | source + target        | source + target       | yes       |

### Level 1: Translation (IR round-trip)

Translates a query from source dialect to target dialect through Coral IR. Verifies that the translation pipeline completes without error.

**What it catches:** Parser failures, unsupported SQL constructs, operator mapping gaps, type conversion errors.

### Level 2: Syntactic Validation (EXPLAIN)

Runs the translated SQL through the target engine's EXPLAIN.

**What it catches:** Dialect-specific syntax errors the Coral converter missed, schema mismatches, unresolved functions.

### Level 3: Semantic Validation (result-set comparison)

Loads test data into both source and target engines, executes the original query on the source engine and the translated query on the target engine, then compares result sets via `ResultSetComparator`.

**What it catches:** Subtle semantic differences in function behavior, NULL handling, type coercion, ordering, and precision across engines.

## 5. Test Suite Construction

A `TranslationTestSuite` is parameterized by source dialect, target dialect, verification level, catalog, and the per-plugin runtime classpaths. The classpaths are required for every dialect and engine the verification level uses — there is no shortcut for handing in a pre-built plugin instance.

```java
TranslationTestSuite suite = TranslationTestSuite.builder()
    .source(Dialect.SPARK_SQL)
    .target(Dialect.TRINO_SQL)
    .catalog(catalog)
    .queryDir("queries/spark_sql")
    .verificationLevel(VerificationLevel.RESULT_SET)
    .dialectPluginJars(Dialect.SPARK_SQL, sparkDialectJars)
    .dialectPluginJars(Dialect.TRINO_SQL, trinoDialectJars)
    .enginePluginJars(Dialect.SPARK_SQL, sparkEngineJars)
    .enginePluginJars(Dialect.TRINO_SQL, trinoEngineJars)
    .testData("default.users", userData)
    .build();

TestReport report = suite.run();
```

The suite iterates over all `.sql` files in the query directory and runs each through the configured pipeline.

In Gradle, the typical wiring is one `Configuration` per plugin so each plugin's transitive dep tree resolves independently. See `coral-benchmark-tests/build.gradle` for the canonical setup; the test JVM receives each configuration's resolved classpath via a system property.

## 6. Comparison Semantics

Result-set comparison handles real-world engine differences via `ComparisonConfig`:

- **Row ordering:** Unordered by default (multiset comparison). Ordered comparison only when `orderedComparison(true)` is set.
- **Floating-point tolerance:** Configurable epsilon for FLOAT/DOUBLE comparisons.
- **NULL equivalence:** Two NULLs in the same position are treated as equal.
- **Type widening:** Allow safe promotions (e.g., INT vs BIGINT) when `allowTypeWidening(true)` (default). The unordered-mode row sort key uses the same canonicalisation as `cellsEqual` (BigDecimal for integral/mixed numerics, eps-wide buckets for floats), so two rows the comparator considers equal cannot sort to different positions.
- **Integral-vs-float precision boundary:** When one side is integral and the other is floating, both go through `toExactBigDecimal` so `Long.MAX_VALUE` vs `(float) Long.MAX_VALUE` correctly compares unequal.

## 7. Reporting

`TestReport` provides structured output:

- Per-query: status (`PASS` or `FAIL`), source SQL, translated SQL, and on failure: `FailureCategory` (`TRANSLATION_ERROR` / `EXPLAIN_FAILURE` / `RESULT_MISMATCH`), error message, exception, and (where applicable) the explain output or result-set diff.
- Aggregate: `totalCount`, `passCount`, `failCount`, `passRate`, `failureCountsByCategory`, plus convenience filters like `getFailures()` and `getFailuresByCategory(...)`.

## 8. Module Structure

```
coral-benchmark/                              (SPI + orchestrator)
  coral-benchmark-spec.md                     this document
  build.gradle
  src/main/java/com/linkedin/coral/benchmark/
    catalog/
      InMemoryCatalog.java                    CoralCatalog impl for tests
      InMemoryTable.java                      CoralTable impl for tests
    spi/
      Dialect.java                            enum: HIVE_SQL, SPARK_SQL, TRINO_SQL
      DialectPlugin.java                      translation SPI - catalog-bound plugin
      DialectPluginProvider.java              translation SPI - ServiceLoader entry point
      EnginePlugin.java                       execution SPI - catalog-free plugin
      EnginePluginProvider.java               execution SPI - ServiceLoader entry point
      VerificationLevel.java                  enum: TRANSLATION, EXPLAIN, RESULT_SET
    plugin/
      PluginClassLoader.java                  child-first URLClassLoader
      PluginRegistry.java                     materializes plugins inside isolated loaders
      ContextClassLoaderDialectPlugin.java    thread-context-loader proxy (dialect)
      ContextClassLoaderEnginePlugin.java     thread-context-loader proxy (engine)
    data/
      ExplainResult.java                      result of running EXPLAIN
      ResultSet.java                          query result container
      RowSet.java                              typed tabular test data
    comparison/
      ComparisonConfig.java                   tolerances, ordering, widening
      ComparisonResult.java                   outcome of comparing two result sets
      ResultSetComparator.java                comparison logic with canonical-key sort
    suite/
      QueryTestResult.java                    per-query test outcome
      TestReport.java                          aggregate results
      TranslationTestSuite.java               main orchestrator
  src/test/java/.../comparison/
    TestResultSetComparator.java              unit tests for the comparator

  coral-benchmark-spark-sql-dialect/          Spark SQL dialect plugin
    src/main/java/com/linkedin/coral/benchmark/spark/sql/dialect/
      SparkSqlDialectPlugin.java
      SparkSqlDialectPluginProvider.java
    src/main/resources/META-INF/services/
      com.linkedin.coral.benchmark.spi.DialectPluginProvider

  coral-benchmark-spark-engine/               Spark engine plugin (Spark 3.5 local)
    src/main/java/com/linkedin/coral/benchmark/spark/engine/
      SparkEnginePlugin.java
      SparkEnginePluginProvider.java
      CoralTypeToSpark.java
      SparkToCoralType.java
    src/main/resources/META-INF/services/
      com.linkedin.coral.benchmark.spi.EnginePluginProvider

  coral-benchmark-trino-sql-dialect/          Trino SQL dialect plugin
    src/main/java/com/linkedin/coral/benchmark/trino/sql/dialect/
      TrinoSqlDialectPlugin.java
      TrinoSqlDialectPluginProvider.java
    src/main/resources/META-INF/services/
      com.linkedin.coral.benchmark.spi.DialectPluginProvider

  coral-benchmark-trino-engine/               Trino engine plugin (Trino 411 LocalQueryRunner)
    src/main/java/com/linkedin/coral/benchmark/trino/engine/
      TrinoEnginePlugin.java
      TrinoEnginePluginProvider.java
      CoralTypeToTrino.java
    src/main/resources/META-INF/services/
      com.linkedin.coral.benchmark.spi.EnginePluginProvider

  coral-benchmark-tests/                      integration tests
    build.gradle                              per-plugin Gradle Configurations
    src/test/java/.../tests/
      TestCrossDialectTranslation.java        cross-dialect happy-path + negative tests
    src/test/resources/queries/
      spark_sql/      *.sql happy-path corpus
      trino_sql/      *.sql happy-path corpus
      negative/
        spark_sql/    *.sql expected-failure corpus
        trino_sql/    *.sql expected-failure corpus
```

Maven coordinates (group `com.linkedin.coral`):
- `coral-benchmark`
- `coral-benchmark-spark-sql-dialect`
- `coral-benchmark-spark-engine`
- `coral-benchmark-trino-sql-dialect`
- `coral-benchmark-trino-engine`

`coral-benchmark-tests` is test-only and not published.

## 9. Dependencies

- `coral-benchmark` depends only on `coral-common` (CoralCatalog, CoralTable, CoralDataType, type hierarchy).
- Each `*-sql-dialect` module depends on `coral-benchmark` plus the corresponding Coral translator module (`coral-hive`, `coral-spark`, or `coral-trino`).
- Each `*-engine` module depends on `coral-benchmark` plus its engine runtime (`spark-sql_2.12:3.5.0` for Spark, `trino-main:411` + `trino-memory:411` + `trino-testing:411` for Trino).
- `coral-benchmark-tests` depends only on `coral-benchmark` at compile time; it pulls plugin jars at test runtime via per-plugin Gradle `Configuration`s and hands them to the test JVM as system properties, so engine and dialect runtimes never appear on the test JVM's parent classloader.

Two small upstream additions support the SPI's CoralCatalog binding:
- `TrinoToRelConverter(CoralCatalog)` constructor in `coral-trino`.
- `CoralSpark.create(RelNode, CoralCatalog)` factory + matching `DataTypeDerivedSqlCallConverter` constructor in `coral-spark`.
- `GenericCoralTableAdapter` + `CoralDatabaseSchema` fallback in `coral-common`, so plain `CoralTable` implementations like `InMemoryTable` resolve through Calcite.

## 10. Non-Goals (current scope)

- **DDL/DML translation testing.** Only SELECT queries are in scope.
- **Performance benchmarking.** This is a correctness framework, not a latency benchmark.
- **Production metastore integration.** Tests use in-memory catalogs only.
- **View resolution.** Queries reference base tables, not views-on-views.
- **Hive engine plugin.** No `coral-benchmark-hive-engine` ships yet; the `Dialect.HIVE_SQL` value is reserved for when one does.
