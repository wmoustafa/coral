/**
 * Copyright 2017-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.benchmark.plugin;

import java.io.Closeable;
import java.io.IOException;

import com.linkedin.coral.benchmark.data.ExplainResult;
import com.linkedin.coral.benchmark.data.ResultSet;
import com.linkedin.coral.benchmark.data.RowSet;
import com.linkedin.coral.benchmark.spi.Engine;
import com.linkedin.coral.benchmark.spi.EnginePlugin;
import com.linkedin.coral.common.types.CoralDataType;


/**
 * Wraps an {@link EnginePlugin} so that every method invocation runs with the plugin's
 * own {@link PluginClassLoader} installed as the thread context classloader. Engines
 * like Spark and Trino consult the thread context classloader for {@link java.util.ServiceLoader}
 * lookups, Hadoop configuration discovery, and reflective class loading — without the
 * swap, those lookups would land in the parent loader (which only has the SPI) and fail
 * to find the engine's own bindings.
 *
 * <p>Implements {@link Closeable} so callers can release the underlying
 * {@link PluginClassLoader} after the engine has been stopped. Spark and Trino install
 * MBeans, Netty thread pools, Hadoop FileSystem cache entries, and shutdown hooks that
 * keep the loader strongly reachable; closing the loader (after {@link #stop()})
 * unblocks GC of the entire engine runtime.
 */
final class ContextClassLoaderEnginePlugin implements EnginePlugin, Closeable {

  private final EnginePlugin delegate;
  private final PluginClassLoader pluginLoader;

  ContextClassLoaderEnginePlugin(EnginePlugin delegate, PluginClassLoader pluginLoader) {
    this.delegate = delegate;
    this.pluginLoader = pluginLoader;
  }

  @Override
  public Engine engine() {
    return withContext(delegate::engine);
  }

  @Override
  public void start() {
    withContext(() -> {
      delegate.start();
      return null;
    });
  }

  @Override
  public void createTable(String namespace, String tableName, CoralDataType schema) {
    withContext(() -> {
      delegate.createTable(namespace, tableName, schema);
      return null;
    });
  }

  @Override
  public void loadData(String namespace, String tableName, RowSet data) {
    withContext(() -> {
      delegate.loadData(namespace, tableName, data);
      return null;
    });
  }

  @Override
  public ExplainResult explain(String sql) {
    return withContext(() -> delegate.explain(sql));
  }

  @Override
  public ResultSet execute(String sql) {
    return withContext(() -> delegate.execute(sql));
  }

  @Override
  public void stop() {
    withContext(() -> {
      delegate.stop();
      return null;
    });
  }

  private <T> T withContext(java.util.function.Supplier<T> body) {
    ClassLoader previous = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(pluginLoader);
      return body.get();
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }

  @Override
  public void close() throws IOException {
    pluginLoader.close();
  }
}
