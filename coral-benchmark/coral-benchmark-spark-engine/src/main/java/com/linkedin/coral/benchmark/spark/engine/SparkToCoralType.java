/**
 * Copyright 2017-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.benchmark.spark.engine;

import java.util.ArrayList;
import java.util.List;

import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;

import com.linkedin.coral.common.types.CoralDataType;
import com.linkedin.coral.common.types.CoralTypeKind;
import com.linkedin.coral.common.types.PrimitiveType;
import com.linkedin.coral.common.types.StructType;


/**
 * Maps a Spark result-set schema back into Coral's type system so result sets can be
 * surfaced through the framework's typed {@code ResultSet} container.
 */
final class SparkToCoralType {

  private SparkToCoralType() {
  }

  static StructType toCoralSchema(org.apache.spark.sql.types.StructType sparkStruct) {
    List<com.linkedin.coral.common.types.StructField> fields = new ArrayList<>();
    for (StructField f : sparkStruct.fields()) {
      fields.add(com.linkedin.coral.common.types.StructField.of(f.name(), toCoralType(f.dataType(), f.nullable())));
    }
    return StructType.of(fields, true);
  }

  private static CoralDataType toCoralType(DataType sparkType, boolean nullable) {
    if (sparkType.equals(DataTypes.BooleanType)) {
      return PrimitiveType.of(CoralTypeKind.BOOLEAN, nullable);
    }
    if (sparkType.equals(DataTypes.ByteType)) {
      return PrimitiveType.of(CoralTypeKind.TINYINT, nullable);
    }
    if (sparkType.equals(DataTypes.ShortType)) {
      return PrimitiveType.of(CoralTypeKind.SMALLINT, nullable);
    }
    if (sparkType.equals(DataTypes.IntegerType)) {
      return PrimitiveType.of(CoralTypeKind.INT, nullable);
    }
    if (sparkType.equals(DataTypes.LongType)) {
      return PrimitiveType.of(CoralTypeKind.BIGINT, nullable);
    }
    if (sparkType.equals(DataTypes.FloatType)) {
      return PrimitiveType.of(CoralTypeKind.FLOAT, nullable);
    }
    if (sparkType.equals(DataTypes.DoubleType)) {
      return PrimitiveType.of(CoralTypeKind.DOUBLE, nullable);
    }
    if (sparkType.equals(DataTypes.StringType)) {
      return PrimitiveType.of(CoralTypeKind.STRING, nullable);
    }
    if (sparkType.equals(DataTypes.DateType)) {
      return PrimitiveType.of(CoralTypeKind.DATE, nullable);
    }
    if (sparkType.equals(DataTypes.TimestampType)) {
      return PrimitiveType.of(CoralTypeKind.TIMESTAMP, nullable);
    }
    if (sparkType.equals(DataTypes.BinaryType)) {
      return PrimitiveType.of(CoralTypeKind.BINARY, nullable);
    }
    throw new UnsupportedOperationException("Spark type " + sparkType + " is not yet mapped to Coral");
  }
}
