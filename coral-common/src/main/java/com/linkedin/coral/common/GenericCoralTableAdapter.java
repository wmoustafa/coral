/**
 * Copyright 2017-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.common;

import org.apache.calcite.DataContext;
import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.Statistic;
import org.apache.calcite.schema.Statistics;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlNode;

import com.linkedin.coral.common.catalog.CoralTable;
import com.linkedin.coral.common.types.CoralTypeToRelDataTypeConverter;
import com.linkedin.coral.common.types.StructType;


/**
 * Generic Calcite adapter for any {@link CoralTable} implementation that doesn't have a
 * format-specific adapter (e.g., not {@link com.linkedin.coral.common.catalog.HiveTable}
 * or {@link com.linkedin.coral.common.catalog.IcebergTable}).
 *
 * <p>Builds the Calcite {@link RelDataType} directly from the Coral type system via
 * {@link CoralTypeToRelDataTypeConverter}, bypassing format-specific metadata extraction.
 * This is what enables consumers like the benchmark module's in-memory catalog to plug
 * into the converter pipeline without pretending to be a Hive table.
 */
public class GenericCoralTableAdapter implements ScannableTable {

  private final CoralTable coralTable;

  public GenericCoralTableAdapter(CoralTable coralTable) {
    this.coralTable = coralTable;
  }

  @Override
  public RelDataType getRowType(RelDataTypeFactory typeFactory) {
    return CoralTypeToRelDataTypeConverter.convert((StructType) coralTable.getSchema(), typeFactory);
  }

  @Override
  public Statistic getStatistic() {
    return Statistics.UNKNOWN;
  }

  @Override
  public Schema.TableType getJdbcTableType() {
    return Schema.TableType.TABLE;
  }

  @Override
  public boolean isRolledUp(String column) {
    return false;
  }

  @Override
  public boolean rolledUpColumnValidInsideAgg(String column, SqlCall call, SqlNode parent,
      CalciteConnectionConfig config) {
    return true;
  }

  @Override
  public Enumerable<Object[]> scan(DataContext root) {
    throw new UnsupportedOperationException("GenericCoralTableAdapter does not support Calcite-side execution");
  }
}
