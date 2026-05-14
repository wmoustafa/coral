/**
 * Copyright 2017-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.benchmark.trino.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableMap;

import io.trino.Session;
import io.trino.plugin.memory.MemoryPlugin;
import io.trino.spi.type.BigintType;
import io.trino.spi.type.BooleanType;
import io.trino.spi.type.DateType;
import io.trino.spi.type.DoubleType;
import io.trino.spi.type.IntegerType;
import io.trino.spi.type.RealType;
import io.trino.spi.type.SmallintType;
import io.trino.spi.type.TimestampType;
import io.trino.spi.type.TinyintType;
import io.trino.spi.type.Type;
import io.trino.spi.type.VarbinaryType;
import io.trino.spi.type.VarcharType;
import io.trino.testing.LocalQueryRunner;
import io.trino.testing.MaterializedResult;
import io.trino.testing.MaterializedRow;

import com.linkedin.coral.benchmark.data.ExplainResult;
import com.linkedin.coral.benchmark.data.ResultSet;
import com.linkedin.coral.benchmark.data.RowSet;
import com.linkedin.coral.benchmark.spi.Dialect;
import com.linkedin.coral.benchmark.spi.EnginePlugin;
import com.linkedin.coral.common.types.CoralDataType;
import com.linkedin.coral.common.types.CoralTypeKind;
import com.linkedin.coral.common.types.PrimitiveType;
import com.linkedin.coral.common.types.StructField;
import com.linkedin.coral.common.types.StructType;

import static io.trino.testing.TestingSession.testSessionBuilder;


/**
 * {@link EnginePlugin} that runs queries against an in-process Trino
 * {@link LocalQueryRunner} backed by the in-memory connector.
 */
public final class TrinoEnginePlugin implements EnginePlugin {

  private static final String CATALOG = "memory";

  private LocalQueryRunner runner;
  // LocalQueryRunner won't plan bare CREATE TABLE or CREATE SCHEMA — it only handles
  // CREATE TABLE AS SELECT. We defer the actual table materialization until loadData()
  // can emit a CTAS that creates the table and inserts the rows together.
  private final Map<String, StructType> pendingTables = new HashMap<>();

  @Override
  public Dialect dialect() {
    return Dialect.TRINO;
  }

  @Override
  public void start() {
    Session session = testSessionBuilder().setCatalog(CATALOG).setSchema("default").build();
    runner = LocalQueryRunner.create(session);
    runner.installPlugin(new MemoryPlugin());
    runner.createCatalog(CATALOG, "memory", ImmutableMap.of());
  }

  @Override
  public void createTable(String namespace, String tableName, CoralDataType schema) {
    requireStarted();
    // Defer materialization until loadData() — see field comment.
    pendingTables.put(qualified(namespace, tableName), (StructType) schema);
  }

  @Override
  public void loadData(String namespace, String tableName, RowSet data) {
    requireStarted();
    String key = qualified(namespace, tableName);
    StructType schema = pendingTables.remove(key);
    if (schema == null) {
      throw new IllegalStateException("loadData called without prior createTable for " + key);
    }
    if (data.size() == 0) {
      // Materialize an empty table by selecting the literals once and filtering them out.
      runner.execute("CREATE TABLE " + CATALOG + "." + key + " AS " + emptyCtasSelect(schema));
      return;
    }
    runner.execute("CREATE TABLE " + CATALOG + "." + key + " AS " + valuesCtasSelect(schema, data.getRows()));
  }

  private static String qualified(String namespace, String tableName) {
    return namespace + "." + tableName;
  }

  private String valuesCtasSelect(StructType schema, List<Object[]> rows) {
    StringBuilder sb = new StringBuilder("SELECT * FROM (VALUES ");
    for (int rowIdx = 0; rowIdx < rows.size(); rowIdx++) {
      if (rowIdx > 0) {
        sb.append(", ");
      }
      Object[] row = rows.get(rowIdx);
      sb.append('(');
      for (int colIdx = 0; colIdx < row.length; colIdx++) {
        if (colIdx > 0) {
          sb.append(", ");
        }
        // Cast each literal so the table picks up the right type (VARCHAR sizing in
        // particular — bare literals would otherwise default to varchar(N) with length
        // equal to the longest literal).
        sb.append("CAST(").append(literal(row[colIdx], schema.getFields().get(colIdx).getType())).append(" AS ")
            .append(CoralTypeToTrino.toTrinoSqlType(schema.getFields().get(colIdx).getType())).append(')');
      }
      sb.append(')');
    }
    sb.append(") AS t(");
    for (int i = 0; i < schema.getFields().size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append('"').append(schema.getFields().get(i).getName()).append('"');
    }
    sb.append(')');
    return sb.toString();
  }

  private String emptyCtasSelect(StructType schema) {
    StringBuilder sb = new StringBuilder("SELECT ");
    for (int i = 0; i < schema.getFields().size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append("CAST(NULL AS ").append(CoralTypeToTrino.toTrinoSqlType(schema.getFields().get(i).getType()))
          .append(") AS \"").append(schema.getFields().get(i).getName()).append('"');
    }
    sb.append(" WHERE 1 = 0");
    return sb.toString();
  }

  @Override
  public ExplainResult explain(String sql) {
    requireStarted();
    try {
      MaterializedResult result = runner.execute("EXPLAIN " + sql);
      List<MaterializedRow> rows = result.getMaterializedRows();
      String plan = rows.isEmpty() ? "" : String.valueOf(rows.get(0).getField(0));
      return ExplainResult.success(plan);
    } catch (Exception e) {
      return ExplainResult.failure(e.getMessage(), e);
    }
  }

  @Override
  public ResultSet execute(String sql) {
    requireStarted();
    MaterializedResult result = runner.execute(sql);
    StructType coralSchema = toCoralSchema(result);
    List<Object[]> out = new ArrayList<>(result.getMaterializedRows().size());
    for (MaterializedRow row : result.getMaterializedRows()) {
      Object[] cells = new Object[row.getFieldCount()];
      for (int i = 0; i < cells.length; i++) {
        cells[i] = row.getField(i);
      }
      out.add(cells);
    }
    return ResultSet.of(coralSchema, out);
  }

  @Override
  public void stop() {
    if (runner != null) {
      try {
        runner.close();
      } catch (Exception ignored) {
      }
      runner = null;
    }
  }

  private void requireStarted() {
    if (runner == null) {
      throw new IllegalStateException("TrinoEnginePlugin is not started");
    }
  }

  private static StructType toCoralSchema(MaterializedResult result) {
    List<Type> types = result.getTypes();
    List<StructField> fields = new ArrayList<>(types.size());
    for (int i = 0; i < types.size(); i++) {
      String columnName = "_col" + i;
      fields.add(StructField.of(columnName, fromTrinoType(types.get(i))));
    }
    return StructType.of(fields, true);
  }

  private static CoralDataType fromTrinoType(Type type) {
    if (type instanceof BooleanType) {
      return PrimitiveType.of(CoralTypeKind.BOOLEAN, true);
    }
    if (type instanceof TinyintType) {
      return PrimitiveType.of(CoralTypeKind.TINYINT, true);
    }
    if (type instanceof SmallintType) {
      return PrimitiveType.of(CoralTypeKind.SMALLINT, true);
    }
    if (type instanceof IntegerType) {
      return PrimitiveType.of(CoralTypeKind.INT, true);
    }
    if (type instanceof BigintType) {
      return PrimitiveType.of(CoralTypeKind.BIGINT, true);
    }
    if (type instanceof RealType) {
      return PrimitiveType.of(CoralTypeKind.FLOAT, true);
    }
    if (type instanceof DoubleType) {
      return PrimitiveType.of(CoralTypeKind.DOUBLE, true);
    }
    if (type instanceof VarcharType) {
      return PrimitiveType.of(CoralTypeKind.STRING, true);
    }
    if (type instanceof DateType) {
      return PrimitiveType.of(CoralTypeKind.DATE, true);
    }
    if (type instanceof TimestampType) {
      return PrimitiveType.of(CoralTypeKind.TIMESTAMP, true);
    }
    if (type instanceof VarbinaryType) {
      return PrimitiveType.of(CoralTypeKind.BINARY, true);
    }
    throw new UnsupportedOperationException("Trino type " + type + " not yet mapped to Coral");
  }

  private static String literal(Object value, CoralDataType coralType) {
    if (value == null) {
      return "NULL";
    }
    CoralTypeKind kind = coralType.getKind();
    switch (kind) {
      case BOOLEAN:
        return Boolean.TRUE.equals(value) ? "TRUE" : "FALSE";
      case TINYINT:
      case SMALLINT:
      case INT:
      case BIGINT:
      case FLOAT:
      case DOUBLE:
        return value.toString();
      case STRING:
      case CHAR:
      case VARCHAR:
        return "'" + value.toString().replace("'", "''") + "'";
      case DATE:
        return "DATE '" + value + "'";
      case TIMESTAMP:
        return "TIMESTAMP '" + value + "'";
      default:
        throw new UnsupportedOperationException("Literal for Coral type kind " + kind + " not yet supported");
    }
  }
}
