/**
 * Copyright 2017-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.benchmark.spi;

/**
 * Supported SQL dialects in the Coral translation framework.
 *
 * <p>Each value corresponds to a SQL dialect that Coral can parse from or generate to.
 * A {@link DialectPlugin} provides the translation logic for a specific dialect,
 * while an {@link EnginePlugin} provides execution capabilities.
 *
 * <p>Each value carries a stable external {@link #id() identifier} string that callers
 * use whenever a dialect needs to surface in a place outside the Java symbol table —
 * e.g. as a directory name, a system-property key segment, or a log token. The id is
 * declared explicitly on each value (not derived via {@code name().toLowerCase()}), so
 * the external identifier remains a deliberate API choice and renaming the JVM symbol
 * does not silently change what external consumers see.
 */
public enum Dialect {
  HIVE_SQL("hive_sql"),
  SPARK_SQL("spark_sql"),
  TRINO_SQL("trino_sql");

  private final String id;

  Dialect(String id) {
    this.id = id;
  }

  /**
   * Returns the canonical external identifier for this dialect — a stable, lowercase
   * string suitable for embedding in file paths, property keys, and similar external
   * surfaces. Distinct from {@link #name()}, which is the JVM symbol and may change
   * if the enum is refactored.
   *
   * @return the external identifier
   */
  public String id() {
    return id;
  }
}
