/**
 * Copyright 2017-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.benchmark.spi;

/**
 * SPI for discovering and constructing {@link EnginePlugin} instances.
 *
 * <p>Provider implementations are the registration entry point picked up by
 * {@link java.util.ServiceLoader}, so they must declare a public no-arg constructor and a
 * {@code META-INF/services/com.linkedin.coral.benchmark.spi.EnginePluginProvider}
 * file listing the implementing class. Providers are loaded through a per-plugin
 * isolated {@link ClassLoader}, which lets each engine plugin carry its own runtime
 * dependencies (Spark, Trino, etc.) without colliding with other plugins' classpaths.
 *
 * <p>The provider identifies itself by {@link Engine}, not by {@link Dialect}: an
 * engine is a runtime, distinct from the SQL dialect it executes. A given engine may
 * natively execute multiple dialects; the framework user wires
 * {@code (Dialect → engine plugin classpath)} explicitly on the benchmark suite builder
 * rather than asking the engine plugin to claim a dialect.
 *
 * <p>Typical implementation:
 * <pre>{@code
 * public final class SparkEnginePluginProvider implements EnginePluginProvider {
 *     public Engine engine() { return Engine.SPARK; }
 *     public EnginePlugin create() { return new SparkEnginePlugin(); }
 * }
 * }</pre>
 */
public interface EnginePluginProvider {

  /**
   * Returns the engine this provider produces plugins for.
   *
   * @return the engine identifier
   */
  Engine engine();

  /**
   * Constructs a new {@link EnginePlugin} instance. The plugin is not yet started — the
   * benchmark framework calls {@link EnginePlugin#start()} when it actually needs to
   * execute queries.
   *
   * @return a new, fully-constructed engine plugin instance
   */
  EnginePlugin create();
}
