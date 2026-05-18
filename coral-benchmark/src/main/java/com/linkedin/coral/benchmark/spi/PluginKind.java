/**
 * Copyright 2017-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.benchmark.spi;

/**
 * The two kinds of plugins the benchmark SPI defines: a {@link DialectPlugin} that
 * translates between a SQL dialect and Coral IR, and an {@link EnginePlugin} that
 * executes queries in a SQL dialect.
 *
 * <p>Like {@link Dialect}, each value carries a stable external {@link #id() identifier}
 * for use in places outside the Java symbol table (system-property keys, log tokens,
 * etc.). The id is declared explicitly per value so it doesn't drift if the enum is
 * refactored.
 */
public enum PluginKind {
  DIALECT("dialect"),
  ENGINE("engine");

  private final String id;

  PluginKind(String id) {
    this.id = id;
  }

  /**
   * Returns the canonical external identifier for this kind — a stable lowercase string
   * suitable for embedding in property keys, paths, and similar external surfaces.
   *
   * @return the external identifier
   */
  public String id() {
    return id;
  }
}
