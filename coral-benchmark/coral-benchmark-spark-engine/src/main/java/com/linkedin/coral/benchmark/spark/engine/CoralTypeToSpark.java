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
import org.apache.spark.sql.types.Metadata;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;

import com.linkedin.coral.common.types.CoralDataType;
import com.linkedin.coral.common.types.CoralTypeKind;
import com.linkedin.coral.common.types.PrimitiveType;


/**
 * Maps Coral primitive types to Spark SQL types. Complex types (ARRAY, MAP, STRUCT) and
 * variable-length primitives (DECIMAL, CHAR(n), VARCHAR(n)) can be added as the benchmark
 * corpus grows.
 */
final class CoralTypeToSpark {

  private CoralTypeToSpark() {
  }

  static StructType toSparkSchema(com.linkedin.coral.common.types.StructType coralStruct) {
    List<StructField> fields = new ArrayList<>();
    for (com.linkedin.coral.common.types.StructField f : coralStruct.getFields()) {
      fields.add(new StructField(f.getName(), toSparkType(f.getType()), f.getType().isNullable(), Metadata.empty()));
    }
    return DataTypes.createStructType(fields);
  }

  private static DataType toSparkType(CoralDataType type) {
    if (type instanceof PrimitiveType) {
      CoralTypeKind kind = type.getKind();
      switch (kind) {
        case BOOLEAN:
          return DataTypes.BooleanType;
        case TINYINT:
          return DataTypes.ByteType;
        case SMALLINT:
          return DataTypes.ShortType;
        case INT:
          return DataTypes.IntegerType;
        case BIGINT:
          return DataTypes.LongType;
        case FLOAT:
          return DataTypes.FloatType;
        case DOUBLE:
          return DataTypes.DoubleType;
        case STRING:
        case CHAR:
        case VARCHAR:
          return DataTypes.StringType;
        case DATE:
          return DataTypes.DateType;
        case TIMESTAMP:
          return DataTypes.TimestampType;
        case BINARY:
          return DataTypes.BinaryType;
        default:
          throw new UnsupportedOperationException("Coral type kind " + kind + " is not yet mapped to Spark");
      }
    }
    throw new UnsupportedOperationException("Coral type " + type + " is not yet mapped to Spark");
  }
}
