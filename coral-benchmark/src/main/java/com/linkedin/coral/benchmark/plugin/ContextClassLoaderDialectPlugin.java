/**
 * Copyright 2017-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.benchmark.plugin;

import java.io.Closeable;
import java.io.IOException;

import org.apache.calcite.rel.RelNode;

import com.linkedin.coral.benchmark.spi.Dialect;
import com.linkedin.coral.benchmark.spi.DialectPlugin;


/**
 * Wraps a {@link DialectPlugin} so every method invocation runs with the plugin's own
 * {@link PluginClassLoader} as the thread context classloader. Calcite, ANTLR, and the
 * Coral converter pipeline all use {@link java.util.ServiceLoader} indirectly; the
 * context-loader swap keeps those lookups landing inside the plugin's isolated jars.
 *
 * <p>Implements {@link Closeable} so callers can release the underlying
 * {@link PluginClassLoader} once they are done with the plugin — important because
 * the loader transitively holds onto every class the plugin's runtime instantiated.
 */
final class ContextClassLoaderDialectPlugin implements DialectPlugin, Closeable {

  private final DialectPlugin delegate;
  private final PluginClassLoader pluginLoader;

  ContextClassLoaderDialectPlugin(DialectPlugin delegate, PluginClassLoader pluginLoader) {
    this.delegate = delegate;
    this.pluginLoader = pluginLoader;
  }

  @Override
  public Dialect dialect() {
    return withContext(delegate::dialect);
  }

  @Override
  public RelNode toRelNode(String sql) {
    return withContext(() -> delegate.toRelNode(sql));
  }

  @Override
  public String toDialectSql(RelNode relNode) {
    return withContext(() -> delegate.toDialectSql(relNode));
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
