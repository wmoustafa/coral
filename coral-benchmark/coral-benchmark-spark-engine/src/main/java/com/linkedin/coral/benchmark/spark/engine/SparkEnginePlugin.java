/**
 * Copyright 2017-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.benchmark.spark.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SaveMode;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;

import com.linkedin.coral.benchmark.data.ExplainResult;
import com.linkedin.coral.benchmark.data.ResultSet;
import com.linkedin.coral.benchmark.data.RowSet;
import com.linkedin.coral.benchmark.spi.Dialect;
import com.linkedin.coral.benchmark.spi.EnginePlugin;
import com.linkedin.coral.common.types.CoralDataType;


/**
 * {@link EnginePlugin} that runs queries against an in-process Spark 3.5 session.
 * Uses Spark's default file-backed catalog (no Hive metastore required); tables are
 * materialized as Parquet under a temporary warehouse directory that is cleaned up
 * on {@link #stop()}.
 */
public final class SparkEnginePlugin implements EnginePlugin {

  private SparkSession spark;
  private Path warehouseDir;

  @Override
  public Dialect dialect() {
    return Dialect.SPARK;
  }

  @Override
  public void start() {
    try {
      warehouseDir = Files.createTempDirectory("coral-benchmark-spark-");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    spark = SparkSession.builder().appName("coral-benchmark").master("local[2]")
        .config("spark.sql.warehouse.dir", warehouseDir.toString()).config("spark.ui.enabled", "false")
        .config("spark.sql.shuffle.partitions", "1").getOrCreate();
  }

  @Override
  public void createTable(String namespace, String tableName, CoralDataType schema) {
    requireStarted();
    StructType sparkSchema =
        CoralTypeToSpark.toSparkSchema((com.linkedin.coral.common.types.StructType) schema);
    StringBuilder cols = new StringBuilder();
    for (StructField f : sparkSchema.fields()) {
      if (cols.length() > 0) {
        cols.append(", ");
      }
      cols.append('`').append(f.name()).append("` ").append(f.dataType().sql());
    }
    spark.sql("CREATE DATABASE IF NOT EXISTS " + namespace);
    spark.sql("DROP TABLE IF EXISTS " + namespace + "." + tableName);
    spark.sql("CREATE TABLE " + namespace + "." + tableName + " (" + cols + ") USING parquet");
  }

  @Override
  public void loadData(String namespace, String tableName, RowSet data) {
    requireStarted();
    StructType sparkSchema = CoralTypeToSpark.toSparkSchema(data.getSchema());
    List<Row> rows = new ArrayList<>(data.size());
    for (Object[] r : data.getRows()) {
      rows.add(RowFactory.create(r));
    }
    Dataset<Row> df = spark.createDataFrame(rows, sparkSchema);
    df.write().mode(SaveMode.Append).insertInto(namespace + "." + tableName);
  }

  @Override
  public ExplainResult explain(String sql) {
    requireStarted();
    try {
      Row first = spark.sql("EXPLAIN " + sql).first();
      return ExplainResult.success(first.getString(0));
    } catch (Exception e) {
      return ExplainResult.failure(e.getMessage(), e);
    }
  }

  @Override
  public ResultSet execute(String sql) {
    requireStarted();
    Dataset<Row> df = spark.sql(sql);
    com.linkedin.coral.common.types.StructType coralSchema = SparkToCoralType.toCoralSchema(df.schema());
    List<Row> rows = df.collectAsList();
    List<Object[]> out = new ArrayList<>(rows.size());
    for (Row r : rows) {
      Object[] cells = new Object[r.size()];
      for (int i = 0; i < r.size(); i++) {
        cells[i] = r.isNullAt(i) ? null : r.get(i);
      }
      out.add(cells);
    }
    return ResultSet.of(coralSchema, out);
  }

  @Override
  public void stop() {
    if (spark != null) {
      try {
        spark.stop();
      } catch (RuntimeException ignored) {
      }
      spark = null;
    }
    if (warehouseDir != null) {
      try (Stream<Path> walk = Files.walk(warehouseDir)) {
        walk.sorted(Comparator.reverseOrder()).forEach(p -> {
          try {
            Files.deleteIfExists(p);
          } catch (IOException ignored) {
          }
        });
      } catch (IOException ignored) {
      }
      warehouseDir = null;
    }
  }

  private void requireStarted() {
    if (spark == null) {
      throw new IllegalStateException("SparkEnginePlugin is not started");
    }
  }
}
