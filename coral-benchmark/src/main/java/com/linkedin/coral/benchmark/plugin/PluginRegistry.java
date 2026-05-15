/**
 * Copyright 2017-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.benchmark.plugin;

import java.io.Closeable;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;

import com.linkedin.coral.benchmark.spi.Dialect;
import com.linkedin.coral.benchmark.spi.DialectPlugin;
import com.linkedin.coral.benchmark.spi.DialectPluginProvider;
import com.linkedin.coral.benchmark.spi.EnginePlugin;
import com.linkedin.coral.benchmark.spi.EnginePluginProvider;
import com.linkedin.coral.common.catalog.CoralCatalog;


/**
 * Materializes {@link DialectPlugin} and {@link EnginePlugin} instances inside per-plugin
 * isolated {@link PluginClassLoader} instances.
 *
 * <p>Each call to {@link #loadDialectPlugin} / {@link #loadEnginePlugin} builds a fresh
 * classloader rooted at the supplied jar URLs, parented to the shared SPI loader. The
 * registry uses {@link ServiceLoader} against that isolated loader to find the provider
 * matching the requested dialect, then invokes the provider's factory method inside the
 * isolated loader.
 *
 * <p>The returned plugin is the parent-loader-visible {@link DialectPlugin} or
 * {@link EnginePlugin} interface; the concrete implementation classes never leak out.
 */
public final class PluginRegistry implements Closeable {

  private final ClassLoader sharedParent;
  private final List<PluginClassLoader> openLoaders = new ArrayList<>();

  /**
   * Creates a registry rooted at the given shared parent classloader.
   *
   * @param sharedParent the loader that holds the SPI, Coral common types, and Apache
   *                     Calcite — typically {@code getClass().getClassLoader()} from the
   *                     calling site
   */
  public PluginRegistry(ClassLoader sharedParent) {
    this.sharedParent = Objects.requireNonNull(sharedParent, "sharedParent");
  }

  /**
   * Loads a {@link DialectPlugin} for the given dialect from the supplied jar set.
   *
   * @param dialect the dialect the caller wants
   * @param jars    the plugin's runtime classpath
   * @param catalog the catalog the plugin will bind to
   * @return a plugin instance, instantiated inside an isolated classloader
   * @throws IllegalStateException if no matching provider is found on the supplied jars
   */
  public DialectPlugin loadDialectPlugin(Dialect dialect, List<URL> jars, CoralCatalog catalog) {
    PluginClassLoader loader = new PluginClassLoader(jars.toArray(new URL[0]), sharedParent);
    ClassLoader previous = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(loader);
      for (DialectPluginProvider provider : ServiceLoader.load(DialectPluginProvider.class, loader)) {
        if (provider.dialect() == dialect) {
          openLoaders.add(loader);
          return new ContextClassLoaderDialectPlugin(provider.create(catalog), loader);
        }
      }
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
    // Provider wasn't found; release the loader we just opened.
    try {
      loader.close();
    } catch (IOException ignored) {
    }
    throw new IllegalStateException("No DialectPluginProvider for " + dialect + " on the supplied classpath ("
        + jars.size() + " jar(s)). Check META-INF/services registration in the plugin module.");
  }

  /**
   * Loads an {@link EnginePlugin} for the given dialect from the supplied jar set.
   *
   * @param dialect the dialect whose engine the caller wants
   * @param jars    the plugin's runtime classpath
   * @return an engine plugin instance, instantiated inside an isolated classloader
   * @throws IllegalStateException if no matching provider is found on the supplied jars
   */
  public EnginePlugin loadEnginePlugin(Dialect dialect, List<URL> jars) {
    PluginClassLoader loader = new PluginClassLoader(jars.toArray(new URL[0]), sharedParent);
    ClassLoader previous = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(loader);
      for (EnginePluginProvider provider : ServiceLoader.load(EnginePluginProvider.class, loader)) {
        if (provider.dialect() == dialect) {
          openLoaders.add(loader);
          return new ContextClassLoaderEnginePlugin(provider.create(), loader);
        }
      }
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
    // Provider wasn't found; release the loader we just opened.
    try {
      loader.close();
    } catch (IOException ignored) {
    }
    throw new IllegalStateException("No EnginePluginProvider for " + dialect + " on the supplied classpath ("
        + jars.size() + " jar(s)). Check META-INF/services registration in the plugin module.");
  }

  /**
   * Closes every {@link PluginClassLoader} this registry has opened. Spark, Trino, Hadoop,
   * and similar engine runtimes install MBeans, thread pools, FileSystem cache entries,
   * and shutdown hooks that hold their classloader strongly reachable — without this,
   * each load-then-discard cycle leaks an entire engine runtime. Callers that already
   * closed individual plugin proxies will see the underlying loader's {@code close()}
   * called a second time here, which is a no-op.
   */
  @Override
  public void close() {
    for (PluginClassLoader loader : openLoaders) {
      try {
        loader.close();
      } catch (IOException ignored) {
      }
    }
    openLoaders.clear();
  }
}
