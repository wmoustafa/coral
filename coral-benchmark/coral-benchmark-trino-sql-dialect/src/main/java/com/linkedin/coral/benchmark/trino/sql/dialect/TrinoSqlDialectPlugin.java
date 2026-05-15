/**
 * Copyright 2017-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.benchmark.trino.sql.dialect;

import java.util.Objects;

import org.apache.calcite.rel.RelNode;

import com.linkedin.coral.benchmark.spi.Dialect;
import com.linkedin.coral.benchmark.spi.DialectPlugin;
import com.linkedin.coral.common.catalog.CoralCatalog;
import com.linkedin.coral.trino.rel2trino.RelToTrinoConverter;
import com.linkedin.coral.trino.trino2rel.TrinoToRelConverter;


/**
 * {@link DialectPlugin} for Trino SQL.
 */
public final class TrinoSqlDialectPlugin implements DialectPlugin {

  private final CoralCatalog catalog;
  private final TrinoToRelConverter toRel;

  public TrinoSqlDialectPlugin(CoralCatalog catalog) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.toRel = new TrinoToRelConverter(catalog);
  }

  @Override
  public Dialect dialect() {
    return Dialect.TRINO;
  }

  @Override
  public RelNode toRelNode(String sql) {
    return toRel.convertSql(sql);
  }

  @Override
  public String toDialectSql(RelNode relNode) {
    return new RelToTrinoConverter(catalog).convert(relNode);
  }
}
