/**
 * Copyright 2017-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.benchmark.plugin;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;

import com.linkedin.coral.benchmark.spi.Dialect;
import com.linkedin.coral.benchmark.spi.DialectPlugin;
import com.linkedin.coral.benchmark.spi.DialectPluginProvider;
import com.linkedin.coral.benchmark.spi.Engine;
import com.linkedin.coral.benchmark.spi.EnginePlugin;
import com.linkedin.coral.benchmark.spi.EnginePluginProvider;
import com.linkedin.coral.common.catalog.CoralCatalog;


/**
 * Discovers and indexes benchmark plugins by their self-reported identity.
 *
 * <p>The catalog separates the <em>deployment</em> concern (which plugins are available
 * and where their jars live) from the <em>scenario</em> concern (which dialects and
 * engines a particular test exercises). A consumer registers plugin classpaths with the
 * catalog once — typically via {@link #discoverFromSystemProperties()} — and then asks
 * for plugins by {@link Dialect} / {@link Engine} value. Plugins are not named by jar
 * path or build-system identifier at the consumer's level.
 *
 * <p>Each classpath registered via {@link #addClasspath(List)} produces a per-plugin
 * {@link PluginClassLoader}, which the catalog scans with {@link ServiceLoader} for any
 * {@link DialectPluginProvider} or {@link EnginePluginProvider} it contains. The
 * providers' own {@link DialectPluginProvider#dialect()} / {@link EnginePluginProvider#engine()}
 * methods determine the index keys — the catalog never asks the caller to declare what
 * a classpath contains.
 *
 * <p>The catalog owns the classloaders it creates and closes them in {@link #close()}.
 * Plugins handed out by {@link #createDialectPlugin} / {@link #createEnginePlugin} share
 * those loaders and remain usable as long as the catalog is open.
 */
public final class PluginCatalog implements Closeable {

  private static final String SYSTEM_PROPERTY_PREFIX = "coral.benchmark.plugin.";

  private final ClassLoader sharedParent;
  private final Map<Dialect, ProviderEntry<DialectPluginProvider>> dialects = new EnumMap<>(Dialect.class);
  private final Map<Engine, ProviderEntry<EnginePluginProvider>> engines = new EnumMap<>(Engine.class);
  private final List<PluginClassLoader> loaders = new ArrayList<>();

  /**
   * Creates an empty catalog rooted at the given shared parent classloader. The parent
   * holds the SPI types, Coral common, and Apache Calcite — typically the calling
   * class's own classloader.
   *
   * @param sharedParent parent classloader for every plugin classpath added later
   */
  public PluginCatalog(ClassLoader sharedParent) {
    this.sharedParent = Objects.requireNonNull(sharedParent, "sharedParent");
  }

  /**
   * Scans system properties for keys starting with {@code coral.benchmark.plugin.} and
   * registers each value as a plugin classpath ({@link File#pathSeparator}-separated
   * jar paths). The catalog is rooted at this class's own classloader.
   *
   * @return a catalog populated from the current process's system properties
   */
  public static PluginCatalog discoverFromSystemProperties() {
    return discoverFromSystemProperties(PluginCatalog.class.getClassLoader());
  }

  /**
   * Same as {@link #discoverFromSystemProperties()} but with an explicit parent
   * classloader.
   */
  public static PluginCatalog discoverFromSystemProperties(ClassLoader sharedParent) {
    PluginCatalog catalog = new PluginCatalog(sharedParent);
    Map<String, String> props = new java.util.TreeMap<>();
    for (String name : System.getProperties().stringPropertyNames()) {
      if (name.startsWith(SYSTEM_PROPERTY_PREFIX)) {
        String value = System.getProperty(name);
        if (value != null && !value.isEmpty()) {
          props.put(name, value);
        }
      }
    }
    for (Map.Entry<String, String> e : props.entrySet()) {
      try {
        catalog.addClasspath(parseClasspath(e.getValue()));
      } catch (RuntimeException ex) {
        catalog.close();
        throw new IllegalStateException("Failed to register plugin from system property " + e.getKey(), ex);
      }
    }
    return catalog;
  }

  private static List<URL> parseClasspath(String value) {
    List<URL> urls = new ArrayList<>();
    for (String entry : value.split(File.pathSeparator)) {
      if (entry.isEmpty()) {
        continue;
      }
      try {
        urls.add(new File(entry).toURI().toURL());
      } catch (MalformedURLException e) {
        throw new IllegalStateException("Bad classpath entry: " + entry, e);
      }
    }
    return urls;
  }

  /**
   * Registers a single plugin classpath. Loads any {@link DialectPluginProvider} and
   * {@link EnginePluginProvider} discovered in {@code META-INF/services} on the
   * classpath and indexes them by their self-reported identity. A classpath that
   * yields no providers is rejected.
   *
   * @param jars the plugin module's full runtime classpath
   * @throws IllegalStateException if the classpath has no provider, or if its
   *                               identity conflicts with one already registered
   */
  public void addClasspath(List<URL> jars) {
    Objects.requireNonNull(jars, "jars");
    PluginClassLoader loader = new PluginClassLoader(jars.toArray(new URL[0]), sharedParent);
    ClassLoader previous = Thread.currentThread().getContextClassLoader();
    boolean indexed = false;
    try {
      Thread.currentThread().setContextClassLoader(loader);
      for (DialectPluginProvider p : ServiceLoader.load(DialectPluginProvider.class, loader)) {
        ProviderEntry<DialectPluginProvider> existing = dialects.get(p.dialect());
        if (existing != null) {
          throw new IllegalStateException(
              "Duplicate DialectPluginProvider for " + p.dialect() + ": already registered by "
                  + existing.provider.getClass().getName() + ", now also " + p.getClass().getName());
        }
        dialects.put(p.dialect(), new ProviderEntry<>(p, loader));
        indexed = true;
      }
      for (EnginePluginProvider p : ServiceLoader.load(EnginePluginProvider.class, loader)) {
        ProviderEntry<EnginePluginProvider> existing = engines.get(p.engine());
        if (existing != null) {
          throw new IllegalStateException("Duplicate EnginePluginProvider for " + p.engine() + ": already registered by "
              + existing.provider.getClass().getName() + ", now also " + p.getClass().getName());
        }
        engines.put(p.engine(), new ProviderEntry<>(p, loader));
        indexed = true;
      }
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
    if (!indexed) {
      closeQuietly(loader);
      throw new IllegalStateException(
          "No DialectPluginProvider or EnginePluginProvider found on the supplied classpath (" + jars.size()
              + " jar(s)). Check META-INF/services in the plugin module.");
    }
    loaders.add(loader);
  }

  /**
   * Creates a {@link DialectPlugin} instance for the given dialect, bound to the
   * supplied catalog. The plugin is wrapped to install its own classloader as the
   * thread context loader on every method call.
   *
   * @throws IllegalStateException if no provider for the dialect is registered
   */
  public DialectPlugin createDialectPlugin(Dialect dialect, CoralCatalog coralCatalog) {
    ProviderEntry<DialectPluginProvider> entry = dialects.get(dialect);
    if (entry == null) {
      throw new IllegalStateException("No DialectPluginProvider registered for " + dialect
          + ". Available dialects: " + dialects.keySet());
    }
    return new ContextClassLoaderDialectPlugin(callInLoader(entry, () -> entry.provider.create(coralCatalog)),
        entry.loader);
  }

  /**
   * Creates an {@link EnginePlugin} instance for the given engine. The plugin is
   * wrapped to install its own classloader as the thread context loader on every
   * method call.
   *
   * @throws IllegalStateException if no provider for the engine is registered
   */
  public EnginePlugin createEnginePlugin(Engine engine) {
    ProviderEntry<EnginePluginProvider> entry = engines.get(engine);
    if (entry == null) {
      throw new IllegalStateException(
          "No EnginePluginProvider registered for " + engine + ". Available engines: " + engines.keySet());
    }
    return new ContextClassLoaderEnginePlugin(callInLoader(entry, entry.provider::create), entry.loader);
  }

  /** @return the dialects for which a provider has been registered. */
  public Set<Dialect> availableDialects() {
    return Collections.unmodifiableSet(dialects.keySet());
  }

  /** @return the engines for which a provider has been registered. */
  public Set<Engine> availableEngines() {
    return Collections.unmodifiableSet(engines.keySet());
  }

  /**
   * Closes every {@link PluginClassLoader} owned by this catalog, releasing Spark /
   * Trino / Hadoop runtime state that would otherwise keep the loader alive via MBeans,
   * Netty thread pools, and shutdown hooks. Plugins handed out by the catalog must not
   * be used after this call.
   */
  @Override
  public void close() {
    for (PluginClassLoader loader : loaders) {
      closeQuietly(loader);
    }
    loaders.clear();
    dialects.clear();
    engines.clear();
  }

  private static <P, R> R callInLoader(ProviderEntry<P> entry, java.util.function.Supplier<R> body) {
    ClassLoader previous = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(entry.loader);
      return body.get();
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }

  private static void closeQuietly(PluginClassLoader loader) {
    try {
      loader.close();
    } catch (IOException ignored) {
    }
  }

  /** Provider + the loader it was discovered in, so plugin creation can run in the
   *  loader's thread context. */
  private static final class ProviderEntry<P> {
    final P provider;
    final PluginClassLoader loader;

    ProviderEntry(P provider, PluginClassLoader loader) {
      this.provider = provider;
      this.loader = loader;
    }
  }
}
