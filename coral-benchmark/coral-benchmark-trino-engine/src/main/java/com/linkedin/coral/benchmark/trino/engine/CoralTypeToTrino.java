/**
 * Copyright 2017-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.benchmark.trino.engine;

import com.linkedin.coral.common.types.CoralDataType;
import com.linkedin.coral.common.types.CoralTypeKind;
import com.linkedin.coral.common.types.PrimitiveType;
import com.linkedin.coral.common.types.StructType;


/**
 * Maps Coral types to Trino SQL type names for DDL generation.
 * Scope is the primitive set needed by the SELECT * smoke tests.
 */
final class CoralTypeToTrino {

  private CoralTypeToTrino() {
  }

  static String columnDdl(StructType struct) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < struct.getFields().size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append('"').append(struct.getFields().get(i).getName()).append('"').append(' ')
          .append(toTrinoSqlType(struct.getFields().get(i).getType()));
    }
    return sb.toString();
  }

  static String toTrinoSqlType(CoralDataType type) {
    if (type instanceof PrimitiveType) {
      CoralTypeKind kind = type.getKind();
      switch (kind) {
        case BOOLEAN:
          return "boolean";
        case TINYINT:
          return "tinyint";
        case SMALLINT:
          return "smallint";
        case INT:
          return "integer";
        case BIGINT:
          return "bigint";
        case FLOAT:
          return "real";
        case DOUBLE:
          return "double";
        case STRING:
        case VARCHAR:
        case CHAR:
          return "varchar";
        case DATE:
          return "date";
        case TIMESTAMP:
          return "timestamp(3)";
        case BINARY:
          return "varbinary";
        default:
          throw new UnsupportedOperationException("Coral type kind " + kind + " not yet mapped to Trino");
      }
    }
    throw new UnsupportedOperationException("Coral type " + type + " not yet mapped to Trino");
  }
}
