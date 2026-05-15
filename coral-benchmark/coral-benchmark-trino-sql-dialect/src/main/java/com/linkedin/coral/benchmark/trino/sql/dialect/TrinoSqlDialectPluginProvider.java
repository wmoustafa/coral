/**
 * Copyright 2017-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.benchmark.trino.sql.dialect;

import com.linkedin.coral.benchmark.spi.Dialect;
import com.linkedin.coral.benchmark.spi.DialectPlugin;
import com.linkedin.coral.benchmark.spi.DialectPluginProvider;
import com.linkedin.coral.common.catalog.CoralCatalog;


/**
 * {@link DialectPluginProvider} for Trino SQL. Discovered via {@link java.util.ServiceLoader}.
 */
public final class TrinoSqlDialectPluginProvider implements DialectPluginProvider {

  @Override
  public Dialect dialect() {
    return Dialect.TRINO;
  }

  @Override
  public DialectPlugin create(CoralCatalog catalog) {
    return new TrinoSqlDialectPlugin(catalog);
  }
}
