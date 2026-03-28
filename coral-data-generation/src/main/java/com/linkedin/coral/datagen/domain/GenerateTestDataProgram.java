/**
 * Copyright 2025-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.datagen.domain;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.type.SqlTypeName;

import com.linkedin.coral.datagen.rel.CanonicalPredicateExtractor;
import com.linkedin.coral.datagen.rel.DnfRewriter;


/**
 * Program for generating test data for all columns in a table schema.
 * 
 * <p>This class generates test data by:
 * <ul>
 *   <li>For columns with predicates: Uses domain inference to generate constrained data</li>
 *   <li>For other columns: Generates random data based on their SQL types</li>
 * </ul>
 * 
 * <p>Example usage:
 * <pre>
 *   GenerateTestDataProgram program = new GenerateTestDataProgram(domainInferenceProgram);
 *   RelNode relNode = converter.convertSql("SELECT * FROM test.T WHERE SUBSTRING(name, 1, 4) = '2000'");
 *   List&lt;Map&lt;String, Object&gt;&gt; testData = program.generateTestData(relNode, 10);
 * </pre>
 */
public class GenerateTestDataProgram {
  private final DomainInferenceProgram domainInferenceProgram;

  /**
   * Creates a new test data generator with the given domain inference program.
   * 
   * @param domainInferenceProgram the program used to derive input constraints from predicates
   */
  public GenerateTestDataProgram(DomainInferenceProgram domainInferenceProgram) {
    this.domainInferenceProgram = domainInferenceProgram;
  }

  /**
   * Generates test data for all columns in the table schema.
   * 
   * @param relNode the relational algebra tree (from SQL query)
   * @param numRows the number of rows to generate
   * @return list of rows, where each row is a map from column name to value
   */
  public List<Map<String, Object>> generateTestData(RelNode relNode, int numRows) {
    Map<Integer, Domain<?, ?>> columnDomains = extractColumnDomains(relNode);
    List<RelDataTypeField> fields = relNode.getRowType().getFieldList();

    List<Map<String, Object>> testData = new ArrayList<>();
    for (int i = 0; i < numRows; i++) {
      Map<String, Object> row = new LinkedHashMap<>();
      for (int fieldIndex = 0; fieldIndex < fields.size(); fieldIndex++) {
        RelDataTypeField field = fields.get(fieldIndex);
        Object value = columnDomains.containsKey(fieldIndex) ? sampleFromDomain(columnDomains.get(fieldIndex))
            : generateRandomValue(field.getType().getSqlTypeName());
        row.put(field.getName(), value);
      }
      testData.add(row);
    }
    return testData;
  }

  /**
   * Extracts column domains from predicates in the relational tree.
   */
  private Map<Integer, Domain<?, ?>> extractColumnDomains(RelNode relNode) {
    Map<Integer, Domain<?, ?>> columnDomains = new HashMap<>();
    try {
      DnfRewriter.Output dnfOut =
          DnfRewriter.convert(CanonicalPredicateExtractor.extract(relNode), relNode.getCluster().getRexBuilder());

      dnfOut.disjuncts.forEach(disjunct -> processDisjunct(disjunct, columnDomains));
    } catch (Exception e) {
      System.err.println("Warning: Failed to extract predicates: " + e.getMessage());
    }
    return columnDomains;
  }

  /**
   * Processes a single disjunct to extract column domains.
   * Handles both individual predicates and conjunctions (AND).
   */
  private void processDisjunct(RexNode disjunct, Map<Integer, Domain<?, ?>> columnDomains) {
    if (!(disjunct instanceof org.apache.calcite.rex.RexCall))
      return;

    org.apache.calcite.rex.RexCall call = (org.apache.calcite.rex.RexCall) disjunct;

    // Handle AND conjunctions by recursively processing each operand
    if (call.getOperator() == org.apache.calcite.sql.fun.SqlStdOperatorTable.AND) {
      for (RexNode operand : call.getOperands()) {
        processDisjunct(operand, columnDomains);
      }
      return;
    }

    // Handle EQUALS predicates
    if (call.getOperator() != org.apache.calcite.sql.fun.SqlStdOperatorTable.EQUALS)
      return;

    RexNode lhs = call.getOperands().get(0);
    RexNode rhs = call.getOperands().get(1);

    if (!(rhs instanceof org.apache.calcite.rex.RexLiteral))
      return;

    try {
      Domain<?, ?> outputDomain = createDomainFromLiteral((org.apache.calcite.rex.RexLiteral) rhs);
      Domain<?, ?> inputDomain = domainInferenceProgram.deriveInputDomain(lhs, outputDomain);
      Integer columnIndex = findReferencedColumn(lhs);

      if (columnIndex != null && !inputDomain.isEmpty()) {
        columnDomains.merge(columnIndex, inputDomain, this::mergeDomains);
      }
    } catch (Exception e) {
      System.err.println("Warning: Failed to derive domain for: " + lhs + " - " + e.getMessage());
    }
  }

  /**
   * Creates a domain from a literal value.
   */
  private Domain<?, ?> createDomainFromLiteral(org.apache.calcite.rex.RexLiteral literal) {
    SqlTypeName typeName = literal.getType().getSqlTypeName();

    // For integer types, use IntegerDomain
    if (isIntegerType(typeName)) {
      String literalValue = literal.getValue2().toString();
      return IntegerDomain.of(Long.parseLong(literalValue));
    }

    // For floating-point types (DOUBLE, FLOAT, DECIMAL), we need to preserve the decimal representation
    // getValue3() returns the original string representation which preserves decimals
    // If getValue3() is not available, we fall back to getValue2() with proper handling
    String literalValue;

    try {
      // Try getValue3() first - this returns the original string representation
      Object value3 = literal.getValue3();
      if (value3 != null) {
        literalValue = value3.toString();
      } else {
        // Fallback to getValue2() with proper BigDecimal handling
        Object value = literal.getValue2();
        if (value instanceof java.math.BigDecimal) {
          literalValue = ((java.math.BigDecimal) value).toPlainString();
        } else {
          literalValue = value.toString();
        }
      }
    } catch (Exception e) {
      // If getValue3() doesn't exist or fails, use getValue2()
      Object value = literal.getValue2();
      if (value instanceof java.math.BigDecimal) {
        literalValue = ((java.math.BigDecimal) value).toPlainString();
      } else {
        literalValue = value.toString();
      }
    }

    // Use RegexDomain for exact match since we don't have a DoubleDomain
    return RegexDomain.literal(literalValue);
  }

  /**
   * Checks if a SQL type is an integer type (not floating-point).
   */
  private boolean isIntegerType(SqlTypeName typeName) {
    switch (typeName) {
      case TINYINT:
      case SMALLINT:
      case INTEGER:
      case BIGINT:
        return true;
      default:
        return false;
    }
  }

  /**
   * Finds the column index referenced by an expression (recursively searches for RexInputRef).
   */
  private Integer findReferencedColumn(RexNode expr) {
    if (expr instanceof RexInputRef) {
      return ((RexInputRef) expr).getIndex();
    }
    if (expr instanceof org.apache.calcite.rex.RexCall) {
      return ((org.apache.calcite.rex.RexCall) expr).getOperands().stream().map(this::findReferencedColumn)
          .filter(Objects::nonNull).findFirst().orElse(null);
    }
    return null;
  }

  /**
   * Merges two domains by intersecting them.
   */
  @SuppressWarnings("unchecked")
  private Domain<?, ?> mergeDomains(Domain<?, ?> domain1, Domain<?, ?> domain2) {
    if (domain1.getClass() == domain2.getClass()) {
      if (domain1 instanceof RegexDomain) {
        return ((RegexDomain) domain1).intersect((RegexDomain) domain2);
      }
      if (domain1 instanceof IntegerDomain) {
        return ((IntegerDomain) domain1).intersect((IntegerDomain) domain2);
      }
    }
    return domain1;
  }

  /**
   * Samples a value from a domain.
   */
  private Object sampleFromDomain(Domain<?, ?> domain) {
    List<?> samples = domain.sample(1);
    return samples.isEmpty() ? null : samples.get(0);
  }

  /**
   * Generates a random value based on SQL type using ThreadLocalRandom.
   */
  private Object generateRandomValue(SqlTypeName sqlType) {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    switch (sqlType) {
      case TINYINT:
      case SMALLINT:
      case INTEGER:
        return random.nextInt(1000);
      case BIGINT:
        return random.nextLong(10000);
      case FLOAT:
      case REAL:
        return random.nextFloat() * 1000;
      case DOUBLE:
      case DECIMAL:
        return random.nextDouble() * 1000;
      case BOOLEAN:
        return random.nextBoolean();
      case DATE:
        return String.format("%04d-%02d-%02d", 2000 + random.nextInt(25), 1 + random.nextInt(12),
            1 + random.nextInt(28));
      case TIME:
        return String.format("%02d:%02d:%02d", random.nextInt(24), random.nextInt(60), random.nextInt(60));
      case TIMESTAMP:
        return generateRandomValue(SqlTypeName.DATE) + " " + generateRandomValue(SqlTypeName.TIME);
      case CHAR:
      case VARCHAR:
      default:
        return generateRandomString(10);
    }
  }

  /**
   * Generates a random alphanumeric string using ThreadLocalRandom.
   */
  private String generateRandomString(int length) {
    return ThreadLocalRandom.current().ints(length, 0, 62).mapToObj(i -> {
      if (i < 10)
        return String.valueOf((char) ('0' + i));
      if (i < 36)
        return String.valueOf((char) ('A' + i - 10));
      return String.valueOf((char) ('a' + i - 36));
    }).reduce("", String::concat);
  }

  /**
   * Checks if a SQL type is numeric.
   */
  private boolean isNumericType(SqlTypeName typeName) {
    return typeName.getFamily() == org.apache.calcite.sql.type.SqlTypeFamily.NUMERIC;
  }
}
