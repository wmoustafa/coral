/**
 * Copyright 2017-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.gremlin.gremlin2rel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rel.logical.LogicalJoin;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.logical.LogicalSort;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;


/**
 * Custom SQL generator that produces flat SQL from a RelNode tree,
 * avoiding the nested subquery structure that Calcite's default generator creates.
 * 
 * This is a simplified generator specifically for Gremlin graph traversal queries.
 */
public class FlatSQLGenerator {

  private List<String> selectFields = new ArrayList<>();
  private List<TableInfo> tables = new ArrayList<>();
  private List<String> whereConditions = new ArrayList<>();
  private List<String> orderByFields = new ArrayList<>();
  private Map<Integer, FieldInfo> fieldMap = new HashMap<>();
  private int tableCounter = 0;

  private static class TableInfo {
    String fullName;
    String alias;
    String joinCondition;

    TableInfo(String fullName, String alias, String joinCondition) {
      this.fullName = fullName;
      this.alias = alias;
      this.joinCondition = joinCondition;
    }
  }

  private static class FieldInfo {
    String tableName;
    String fieldName;
    int index;

    FieldInfo(String tableName, String fieldName, int index) {
      this.tableName = tableName;
      this.fieldName = fieldName;
      this.index = index;
    }
  }

  /**
   * Generate flat SQL from a RelNode tree.
   */
  public String generateSQL(RelNode relNode) {
    // Walk the RelNode tree bottom-up to collect all components
    collectComponents(relNode, 0);

    // Build the final SQL
    StringBuilder sql = new StringBuilder();

    // SELECT clause
    if (selectFields.isEmpty()) {
      sql.append("SELECT *");
    } else {
      sql.append("SELECT ").append(String.join(", ", selectFields));
    }

    // FROM clause with JOINs
    sql.append("\nFROM ").append(tables.get(0).fullName).append(" AS ").append(tables.get(0).alias);
    for (int i = 1; i < tables.size(); i++) {
      TableInfo table = tables.get(i);
      sql.append("\nINNER JOIN ").append(table.fullName).append(" AS ").append(table.alias);
      if (table.joinCondition != null && !table.joinCondition.isEmpty()) {
        sql.append("\n  ON ").append(table.joinCondition);
      }
    }

    // WHERE clause
    if (!whereConditions.isEmpty()) {
      sql.append("\nWHERE ");
      sql.append(String.join("\n  AND ", whereConditions));
    }

    // ORDER BY clause
    if (!orderByFields.isEmpty()) {
      sql.append("\nORDER BY ").append(String.join(", ", orderByFields));
    }

    return sql.toString();
  }

  private int collectComponents(RelNode node, int fieldOffset) {
    if (node == null) {
      return fieldOffset;
    }

    // Process based on node type
    if (node instanceof LogicalProject) {
      return handleProject((LogicalProject) node, fieldOffset);
    } else if (node instanceof LogicalSort) {
      return handleSort((LogicalSort) node, fieldOffset);
    } else if (node instanceof LogicalFilter) {
      return handleFilter((LogicalFilter) node, fieldOffset);
    } else if (node instanceof LogicalJoin) {
      return handleJoin((LogicalJoin) node, fieldOffset);
    } else if (node instanceof TableScan) {
      return handleTableScan((TableScan) node, fieldOffset);
    } else {
      // For other nodes, process inputs
      for (RelNode input : node.getInputs()) {
        fieldOffset = collectComponents(input, fieldOffset);
      }
      return fieldOffset;
    }
  }

  private int handleProject(LogicalProject project, int fieldOffset) {
    // Process input first
    fieldOffset = collectComponents(project.getInput(), fieldOffset);

    // Extract projection fields
    selectFields.clear();
    for (int i = 0; i < project.getProjects().size(); i++) {
      RexNode rexNode = project.getProjects().get(i);
      String fieldName = project.getRowType().getFieldNames().get(i);

      if (rexNode instanceof RexInputRef) {
        RexInputRef ref = (RexInputRef) rexNode;
        FieldInfo fieldInfo = fieldMap.get(ref.getIndex());
        if (fieldInfo != null) {
          selectFields.add(fieldInfo.tableName + "." + fieldInfo.fieldName + " AS " + fieldName);
        } else {
          selectFields.add(fieldName);
        }
      } else {
        selectFields.add(fieldName);
      }
    }

    return fieldOffset;
  }

  private int handleSort(LogicalSort sort, int fieldOffset) {
    // Process input first
    fieldOffset = collectComponents(sort.getInput(), fieldOffset);

    // Extract ORDER BY with proper table qualification
    if (sort.getCollation() != null) {
      for (org.apache.calcite.rel.RelFieldCollation fieldCollation : sort.getCollation().getFieldCollations()) {
        int idx = fieldCollation.getFieldIndex();
        String fieldName = sort.getRowType().getFieldNames().get(idx);
        String direction = fieldCollation.getDirection().isDescending() ? " DESC" : "";

        // Look up the field in the field map to get the qualified table.field reference
        FieldInfo fieldInfo = fieldMap.get(idx);
        if (fieldInfo != null) {
          orderByFields.add(fieldInfo.tableName + "." + fieldInfo.fieldName + direction);
        } else {
          // Fallback to just field name if not found in map
          orderByFields.add(fieldName + direction);
        }
      }
    }

    return fieldOffset;
  }

  private int handleFilter(LogicalFilter filter, int fieldOffset) {
    // Process input first
    fieldOffset = collectComponents(filter.getInput(), fieldOffset);

    // Extract WHERE condition
    String condition = rexToSQL(filter.getCondition(), filter.getInput().getRowType().getFieldNames());
    whereConditions.add(condition);

    return fieldOffset;
  }

  private int handleJoin(LogicalJoin join, int fieldOffset) {
    // Save the starting offset for this join
    int leftStartOffset = fieldOffset;

    // Process left side
    fieldOffset = collectComponents(join.getLeft(), fieldOffset);
    int leftEndOffset = fieldOffset;

    // Process right side (usually a table scan)
    int rightStartOffset = fieldOffset;
    fieldOffset = collectComponents(join.getRight(), fieldOffset);

    // Extract join condition with proper field resolution
    // The join condition references fields from both left and right inputs
    if (!tables.isEmpty()) {
      // Create a temporary field list for this join's scope
      List<String> joinFieldNames = new ArrayList<>();
      joinFieldNames.addAll(join.getLeft().getRowType().getFieldNames());
      joinFieldNames.addAll(join.getRight().getRowType().getFieldNames());

      String joinCond = rexToSQL(join.getCondition(), joinFieldNames);
      tables.get(tables.size() - 1).joinCondition = joinCond;
    }

    return fieldOffset;
  }

  private int handleTableScan(TableScan scan, int fieldOffset) {
    String tableName = getFullTableName(scan);
    String alias = "t" + tableCounter++;

    tables.add(new TableInfo(tableName, alias, null));

    // Map fields
    for (int i = 0; i < scan.getRowType().getFieldCount(); i++) {
      String fieldName = scan.getRowType().getFieldNames().get(i);
      fieldMap.put(fieldOffset + i, new FieldInfo(alias, fieldName, i));
    }

    return fieldOffset + scan.getRowType().getFieldCount();
  }

  private String getFullTableName(TableScan scan) {
    List<String> qualifiedName = scan.getTable().getQualifiedName();
    // Skip "hive" schema prefix if present
    if (qualifiedName.size() >= 3 && qualifiedName.get(0).equals("hive")) {
      return qualifiedName.get(1) + "." + qualifiedName.get(2);
    }
    return String.join(".", qualifiedName);
  }

  private String rexToSQL(RexNode rex, List<String> fieldNames) {
    if (rex instanceof RexInputRef) {
      RexInputRef inputRef = (RexInputRef) rex;
      int index = inputRef.getIndex();
      FieldInfo fieldInfo = fieldMap.get(index);
      if (fieldInfo != null) {
        return fieldInfo.tableName + "." + fieldInfo.fieldName;
      }
      // Fallback to field name from list
      if (index < fieldNames.size()) {
        return fieldNames.get(index);
      }
      return "$" + index;
    } else if (rex instanceof RexLiteral) {
      RexLiteral literal = (RexLiteral) rex;

      // Use getValue2() to get the actual value without encoding prefix
      Object value = literal.getValue2();
      if (value == null) {
        return "NULL";
      }

      // Handle different value types
      if (value instanceof org.apache.calcite.util.NlsString) {
        // NlsString contains charset info - extract just the string value
        org.apache.calcite.util.NlsString nlsString = (org.apache.calcite.util.NlsString) value;
        String strValue = nlsString.getValue();
        return "'" + strValue.replace("'", "''") + "'";
      } else if (value instanceof String) {
        return "'" + value.toString().replace("'", "''") + "'";
      } else if (value instanceof Number) {
        return value.toString();
      } else if (value != null) {
        return "'" + value.toString() + "'";
      }

      return "NULL";
    } else if (rex instanceof RexCall) {
      RexCall call = (RexCall) rex;
      SqlKind kind = call.getKind();

      if (kind == SqlKind.EQUALS) {
        return rexToSQL(call.getOperands().get(0), fieldNames) + " = "
            + rexToSQL(call.getOperands().get(1), fieldNames);
      } else if (kind == SqlKind.AND) {
        return rexToSQL(call.getOperands().get(0), fieldNames) + " AND "
            + rexToSQL(call.getOperands().get(1), fieldNames);
      } else if (kind == SqlKind.OR) {
        return "(" + rexToSQL(call.getOperands().get(0), fieldNames) + " OR "
            + rexToSQL(call.getOperands().get(1), fieldNames) + ")";
      } else if (kind == SqlKind.GREATER_THAN) {
        return rexToSQL(call.getOperands().get(0), fieldNames) + " > "
            + rexToSQL(call.getOperands().get(1), fieldNames);
      } else if (kind == SqlKind.LESS_THAN) {
        return rexToSQL(call.getOperands().get(0), fieldNames) + " < "
            + rexToSQL(call.getOperands().get(1), fieldNames);
      } else if (kind == SqlKind.GREATER_THAN_OR_EQUAL) {
        return rexToSQL(call.getOperands().get(0), fieldNames) + " >= "
            + rexToSQL(call.getOperands().get(1), fieldNames);
      } else if (kind == SqlKind.LESS_THAN_OR_EQUAL) {
        return rexToSQL(call.getOperands().get(0), fieldNames) + " <= "
            + rexToSQL(call.getOperands().get(1), fieldNames);
      } else if (kind == SqlKind.NOT_EQUALS) {
        return rexToSQL(call.getOperands().get(0), fieldNames) + " <> "
            + rexToSQL(call.getOperands().get(1), fieldNames);
      }

      // Default: function call
      List<String> operands = new ArrayList<>();
      for (RexNode operand : call.getOperands()) {
        operands.add(rexToSQL(operand, fieldNames));
      }
      return call.getOperator().getName() + "(" + String.join(", ", operands) + ")";
    }

    return rex.toString();
  }
}
