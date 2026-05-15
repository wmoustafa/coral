/**
 * Copyright 2017-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.benchmark.spark.sql.dialect;

import java.util.Objects;

import org.apache.calcite.rel.RelNode;

import com.linkedin.coral.benchmark.spi.Dialect;
import com.linkedin.coral.benchmark.spi.DialectPlugin;
import com.linkedin.coral.common.catalog.CoralCatalog;
import com.linkedin.coral.hive.hive2rel.HiveToRelConverter;
import com.linkedin.coral.spark.CoralSpark;


/**
 * {@link DialectPlugin} for Spark SQL.
 *
 * <p>Spark SQL parses as Hive SQL, so {@link HiveToRelConverter} is reused for the
 * {@link #toRelNode(String)} direction. {@link CoralSpark} produces Spark SQL from a Coral
 * IR {@link RelNode}.
 */
public final class SparkSqlDialectPlugin implements DialectPlugin {

  private final CoralCatalog catalog;
  private final HiveToRelConverter toRel;

  public SparkSqlDialectPlugin(CoralCatalog catalog) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.toRel = new HiveToRelConverter(catalog);
  }

  @Override
  public Dialect dialect() {
    return Dialect.SPARK;
  }

  @Override
  public RelNode toRelNode(String sql) {
    return toRel.convertSql(sql);
  }

  @Override
  public String toDialectSql(RelNode relNode) {
    return CoralSpark.create(relNode, catalog).getSparkSql();
  }
}
