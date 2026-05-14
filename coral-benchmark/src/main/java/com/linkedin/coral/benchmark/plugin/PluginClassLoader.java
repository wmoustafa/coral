/**
 * Copyright 2017-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.benchmark.plugin;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;


/**
 * Child-first {@link ClassLoader} for benchmark plugin isolation.
 *
 * <p>Two-zone delegation:
 * <ul>
 *   <li><b>Parent-exposed namespaces</b> — JVM types, the benchmark SPI, Coral common
 *       types, and Apache Calcite (because {@code RelNode} crosses the SPI boundary).
 *       These ALWAYS load from the parent so that types passed across the boundary are
 *       the same {@code Class} in every loader.</li>
 *   <li><b>Everything else</b> — child-first: look in the plugin's own jars before
 *       falling back to the parent. This is what lets two plugins co-exist with
 *       conflicting versions of Jackson, Avatica, SLF4J bindings, Spark or Trino runtime
 *       classes, etc.</li>
 * </ul>
 *
 * <p>The list of parent-exposed prefixes is intentionally tight: only what genuinely
 * crosses the SPI boundary, plus the JVM. Anything else is free to live in two loaders
 * simultaneously without interfering.
 */
public final class PluginClassLoader extends URLClassLoader {

  // Force-parent: types that MUST resolve to the parent's Class object for SPI boundary
  // crossings to type-check. Plugin code that references these names always sees the parent
  // version, even if the plugin's own jars contain a copy.
  private static final List<String> FORCE_PARENT_PREFIXES = Arrays.asList(
      // JVM / platform (parent always wins)
      "java.", "sun.", "jdk.",
      // Benchmark SPI and shared data types
      "com.linkedin.coral.benchmark.spi.",
      "com.linkedin.coral.benchmark.data.",
      "com.linkedin.coral.benchmark.catalog.",
      "com.linkedin.coral.benchmark.comparison.",
      "com.linkedin.coral.benchmark.suite.",
      "com.linkedin.coral.benchmark.plugin.",
      // Coral common (CoralCatalog, types, schema adapters, ToRelConverter, RelNode plumbing)
      "com.linkedin.coral.common.",
      // Coral's shaded third-party namespace (LinkedIn-shaded Guava, Jackson, etc. used by
      // coral-common method signatures). These classes appear in the parent's
      // calcite-core-shaded jar; force them to load from parent so plugin code linking
      // against coral-common method signatures sees the same Class objects.
      "com.linkedin.coral.com.",
      // Apache Calcite — RelNode is the IR currency crossing the SPI
      "org.apache.calcite.");

  // Parent-first: prefer the parent's copy when available (e.g. javax.* extensions baked
  // into the JDK or the test JVM's classpath), but fall back to the plugin's own jar if
  // the parent doesn't have it. This handles modules like javax.servlet that Spark
  // bundles itself.
  private static final List<String> PARENT_FIRST_FALLBACK_PREFIXES = Arrays.asList("javax.", "org.w3c.", "org.xml.");

  private final ClassLoader sharedParent;

  /**
   * Creates a plugin classloader.
   *
   * @param urls         the plugin's own jars (the plugin's runtime classpath)
   * @param sharedParent the loader that holds the SPI, Coral common, and Calcite — typically
   *                     the test JVM's system classloader
   */
  public PluginClassLoader(URL[] urls, ClassLoader sharedParent) {
    // null parent means URLClassLoader's findClass() looks at OUR urls only — we control
    // parent delegation explicitly in loadClass() below.
    super(urls, null);
    this.sharedParent = Objects.requireNonNull(sharedParent, "sharedParent");
  }

  @Override
  protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    synchronized (getClassLoadingLock(name)) {
      Class<?> loaded = findLoadedClass(name);
      if (loaded != null) {
        if (resolve) {
          resolveClass(loaded);
        }
        return loaded;
      }

      if (isForceParent(name)) {
        // Force-delegate to parent so SPI types are identical across loaders.
        Class<?> fromParent = sharedParent.loadClass(name);
        if (resolve) {
          resolveClass(fromParent);
        }
        return fromParent;
      }

      if (isParentFirstFallback(name)) {
        // Try parent first (so SDK-provided extensions win), but fall back to the plugin
        // for modules that aren't on the parent classpath.
        try {
          Class<?> fromParent = sharedParent.loadClass(name);
          if (resolve) {
            resolveClass(fromParent);
          }
          return fromParent;
        } catch (ClassNotFoundException parentMiss) {
          Class<?> own = findClass(name);
          if (resolve) {
            resolveClass(own);
          }
          return own;
        }
      }

      // Child-first: look in this plugin's jars before parent.
      try {
        Class<?> own = findClass(name);
        if (resolve) {
          resolveClass(own);
        }
        return own;
      } catch (ClassNotFoundException local) {
        Class<?> fromParent = sharedParent.loadClass(name);
        if (resolve) {
          resolveClass(fromParent);
        }
        return fromParent;
      }
    }
  }

  @Override
  public URL getResource(String name) {
    // Resource-loading mirrors class-loading: SPI-adjacent resources from parent first,
    // everything else child-first. The most relevant case is ServiceLoader META-INF files —
    // those must come from the plugin's jars so the plugin's providers are discovered.
    if (isParentExposedResource(name)) {
      URL fromParent = sharedParent.getResource(name);
      if (fromParent != null) {
        return fromParent;
      }
    }
    URL own = findResource(name);
    if (own != null) {
      return own;
    }
    return sharedParent.getResource(name);
  }

  private boolean isForceParent(String className) {
    for (String prefix : FORCE_PARENT_PREFIXES) {
      if (className.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  private boolean isParentFirstFallback(String className) {
    for (String prefix : PARENT_FIRST_FALLBACK_PREFIXES) {
      if (className.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  private boolean isParentExposedResource(String resourceName) {
    // Conservative: only force-parent for resources that mirror force-parent classes.
    // Plugin SPI service files (META-INF/services/*) must come from the plugin jars,
    // not from parent, otherwise ServiceLoader on a per-plugin loader sees nothing.
    return resourceName.startsWith("java/");
  }
}
