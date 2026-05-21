/**
 * Copyright 2017-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.benchmark.spi;

/**
 * A query execution runtime. Distinct from {@link Dialect}, which identifies a SQL
 * language variant.
 *
 * <p>An engine and a dialect are different concepts with a many-to-many relationship —
 * one engine may natively execute multiple dialects (Spark accepts both Spark SQL and
 * HiveQL via its Hive-compat layer), and one dialect may be executed by multiple engines
 * (Trino SQL runs on Trino, Presto, Athena). An {@link EnginePlugin} self-identifies via
 * {@link EnginePlugin#engine()}, but does not claim a dialect; the caller wires
 * {@code (Dialect → EnginePlugin classpath)} explicitly when configuring a benchmark
 * suite.
 *
 * <p>Each value carries a stable external {@link #id() identifier} string for use in
 * places outside the Java symbol table.
 */
public enum Engine {
  HIVE("hive"),
  SPARK("spark"),
  TRINO("trino");

  private final String id;

  Engine(String id) {
    this.id = id;
  }

  /**
   * Returns the canonical external identifier for this engine — a stable lowercase
   * string suitable for embedding in property keys, log tokens, and similar external
   * surfaces.
   *
   * @return the external identifier
   */
  public String id() {
    return id;
  }
}
