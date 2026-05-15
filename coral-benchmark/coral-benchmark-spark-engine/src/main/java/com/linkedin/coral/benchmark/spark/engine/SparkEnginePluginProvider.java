/**
 * Copyright 2017-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.benchmark.spark.engine;

import com.linkedin.coral.benchmark.spi.Dialect;
import com.linkedin.coral.benchmark.spi.EnginePlugin;
import com.linkedin.coral.benchmark.spi.EnginePluginProvider;


/**
 * {@link EnginePluginProvider} for Spark. Discovered via {@link java.util.ServiceLoader}
 * against this module's isolated classloader.
 */
public final class SparkEnginePluginProvider implements EnginePluginProvider {

  @Override
  public Dialect dialect() {
    return Dialect.SPARK_SQL;
  }

  @Override
  public EnginePlugin create() {
    return new SparkEnginePlugin();
  }
}
