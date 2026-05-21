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
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;

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
 * present on the supplied classpath and invokes its factory method inside the isolated
 * loader. The registry does <em>not</em> filter providers by dialect / engine identity —
 * the caller picks the plugin by choosing the classpath, and the provider's own
 * self-identification ({@link DialectPlugin#dialect()} / {@link EnginePlugin#engine()})
 * is metadata on the loaded plugin. The caller is responsible for binding a
 * {@link com.linkedin.coral.benchmark.spi.Dialect} to a particular engine plugin
 * classpath; the engine itself does not claim a dialect.
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
   * Loads the {@link DialectPlugin} provided by the supplied jar set. Returns the first
   * provider found on the classpath; the loaded plugin's {@link DialectPlugin#dialect()}
   * tells the caller which dialect it handles.
   *
   * @param jars    the plugin's runtime classpath
   * @param catalog the catalog the plugin will bind to
   * @return a plugin instance, instantiated inside an isolated classloader
   * @throws IllegalStateException if no provider is found on the supplied jars
   */
  public DialectPlugin loadDialectPlugin(List<URL> jars, CoralCatalog catalog) {
    PluginClassLoader loader = new PluginClassLoader(jars.toArray(new URL[0]), sharedParent);
    ClassLoader previous = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(loader);
      Iterator<DialectPluginProvider> it = ServiceLoader.load(DialectPluginProvider.class, loader).iterator();
      if (it.hasNext()) {
        openLoaders.add(loader);
        return new ContextClassLoaderDialectPlugin(it.next().create(catalog), loader);
      }
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
    closeQuietly(loader);
    throw new IllegalStateException(
        "No DialectPluginProvider found on the supplied classpath (" + jars.size() + " jar(s)). "
            + "Check META-INF/services/" + DialectPluginProvider.class.getName() + " in the plugin module.");
  }

  /**
   * Loads the {@link EnginePlugin} provided by the supplied jar set. Returns the first
   * provider found on the classpath; the loaded plugin's {@link EnginePlugin#engine()}
   * tells the caller which engine it is. The engine does not claim a SQL dialect — the
   * caller binds dialects to engine plugins explicitly when configuring the benchmark
   * suite.
   *
   * @param jars the plugin's runtime classpath
   * @return an engine plugin instance, instantiated inside an isolated classloader
   * @throws IllegalStateException if no provider is found on the supplied jars
   */
  public EnginePlugin loadEnginePlugin(List<URL> jars) {
    PluginClassLoader loader = new PluginClassLoader(jars.toArray(new URL[0]), sharedParent);
    ClassLoader previous = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(loader);
      Iterator<EnginePluginProvider> it = ServiceLoader.load(EnginePluginProvider.class, loader).iterator();
      if (it.hasNext()) {
        openLoaders.add(loader);
        return new ContextClassLoaderEnginePlugin(it.next().create(), loader);
      }
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
    closeQuietly(loader);
    throw new IllegalStateException(
        "No EnginePluginProvider found on the supplied classpath (" + jars.size() + " jar(s)). "
            + "Check META-INF/services/" + EnginePluginProvider.class.getName() + " in the plugin module.");
  }

  private static void closeQuietly(PluginClassLoader loader) {
    try {
      loader.close();
    } catch (IOException ignored) {
    }
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
      closeQuietly(loader);
    }
    openLoaders.clear();
  }
}
