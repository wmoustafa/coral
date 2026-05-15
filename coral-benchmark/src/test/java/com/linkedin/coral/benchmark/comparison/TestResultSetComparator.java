/**
 * Copyright 2017-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.benchmark.comparison;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.linkedin.coral.benchmark.data.ResultSet;
import com.linkedin.coral.common.types.CoralTypeKind;
import com.linkedin.coral.common.types.PrimitiveType;
import com.linkedin.coral.common.types.StructField;
import com.linkedin.coral.common.types.StructType;


/**
 * Unit tests for {@link ResultSetComparator}. Cover the subtle cases that the integration
 * smoke test would never reach directly: cross-side type widening, BigDecimal scale,
 * floating-point precision boundary, ordered vs unordered comparison, and NULL handling.
 */
public class TestResultSetComparator {

  // ---- Structural mismatches ------------------------------------------------------

  @Test
  public void emptyVsEmptyIsEquivalent() {
    ResultSet empty = ResultSet.of(twoCol(), Collections.<Object[]> emptyList());
    Assert.assertTrue(new ResultSetComparator().compare(empty, empty).isEquivalent());
  }

  @Test
  public void columnCountMismatchReported() {
    ResultSet a = ResultSet.of(struct("id", CoralTypeKind.INT), rows(new Object[] { 1 }));
    ResultSet b = ResultSet.of(struct("id", CoralTypeKind.INT, "v", CoralTypeKind.INT), rows(new Object[] { 1, 2 }));
    ComparisonResult result = new ResultSetComparator().compare(a, b);
    Assert.assertFalse(result.isEquivalent());
    Assert.assertTrue(result.getSummary().toLowerCase().contains("column count"), result.getSummary());
  }

  @Test
  public void rowCountMismatchReported() {
    ResultSet a = ResultSet.of(struct("id", CoralTypeKind.INT), rows(new Object[] { 1 }));
    ResultSet b = ResultSet.of(struct("id", CoralTypeKind.INT), rows(new Object[] { 1 }, new Object[] { 2 }));
    ComparisonResult result = new ResultSetComparator().compare(a, b);
    Assert.assertFalse(result.isEquivalent());
    Assert.assertTrue(result.getSummary().toLowerCase().contains("row count"), result.getSummary());
  }

  // ---- Numeric widening ----------------------------------------------------------

  @Test
  public void integerVsLongIsEquivalentUnderWidening() {
    ResultSet a = ResultSet.of(struct("id", CoralTypeKind.INT), rows(new Object[] { 1 }));
    ResultSet b = ResultSet.of(struct("id", CoralTypeKind.BIGINT), rows(new Object[] { 1L }));
    Assert.assertTrue(new ResultSetComparator().compare(a, b).isEquivalent());
  }

  @Test
  public void widerLongRejectedWhenWideningDisabled() {
    ComparisonConfig cfg = ComparisonConfig.builder().allowTypeWidening(false).build();
    ResultSet a = ResultSet.of(struct("id", CoralTypeKind.INT), rows(new Object[] { 1 }));
    ResultSet b = ResultSet.of(struct("id", CoralTypeKind.BIGINT), rows(new Object[] { 1L }));
    Assert.assertFalse(new ResultSetComparator(cfg).compare(a, b).isEquivalent());
  }

  // ---- Integer vs float precision boundary --------------------------------------

  @Test
  public void longMaxValueVsFloatLosesEquality() {
    // Float can't represent Long.MAX_VALUE; the cast rounds away the low bits. The
    // comparator must NOT silently call these equal.
    long maxLong = Long.MAX_VALUE;
    float lossy = (float) maxLong;
    ResultSet a = ResultSet.of(struct("v", CoralTypeKind.BIGINT), rows(new Object[] { maxLong }));
    ResultSet b = ResultSet.of(struct("v", CoralTypeKind.FLOAT), rows(new Object[] { lossy }));
    Assert.assertFalse(new ResultSetComparator().compare(a, b).isEquivalent(),
        "Float(Long.MAX_VALUE) is not the same number as Long.MAX_VALUE");
  }

  @Test
  public void smallLongVsFloatIsEquivalent() {
    // Floats can losslessly represent small integers; equality should hold.
    ResultSet a = ResultSet.of(struct("v", CoralTypeKind.BIGINT), rows(new Object[] { 3L }));
    ResultSet b = ResultSet.of(struct("v", CoralTypeKind.FLOAT), rows(new Object[] { 3.0f }));
    Assert.assertTrue(new ResultSetComparator().compare(a, b).isEquivalent());
  }

  // ---- Floating-point epsilon ----------------------------------------------------

  @Test
  public void doublesWithinEpsilonAreEquivalent() {
    ComparisonConfig cfg = ComparisonConfig.builder().floatingPointEpsilon(1e-6).build();
    ResultSet a = ResultSet.of(struct("v", CoralTypeKind.DOUBLE), rows(new Object[] { 1.0 }));
    ResultSet b = ResultSet.of(struct("v", CoralTypeKind.DOUBLE), rows(new Object[] { 1.0 + 5e-7 }));
    Assert.assertTrue(new ResultSetComparator(cfg).compare(a, b).isEquivalent());
  }

  @Test
  public void doublesOutsideEpsilonAreNotEquivalent() {
    ComparisonConfig cfg = ComparisonConfig.builder().floatingPointEpsilon(1e-9).build();
    ResultSet a = ResultSet.of(struct("v", CoralTypeKind.DOUBLE), rows(new Object[] { 1.0 }));
    ResultSet b = ResultSet.of(struct("v", CoralTypeKind.DOUBLE), rows(new Object[] { 1.01 }));
    Assert.assertFalse(new ResultSetComparator(cfg).compare(a, b).isEquivalent());
  }

  // ---- BigDecimal scale ----------------------------------------------------------

  @Test
  public void bigDecimalScaleDifferenceIsEquivalent() {
    ResultSet a = ResultSet.of(struct("v", CoralTypeKind.DECIMAL), rows(new Object[] { new BigDecimal("1.0") }));
    ResultSet b = ResultSet.of(struct("v", CoralTypeKind.DECIMAL), rows(new Object[] { new BigDecimal("1.00") }));
    Assert.assertTrue(new ResultSetComparator().compare(a, b).isEquivalent());
  }

  // ---- NULL handling -------------------------------------------------------------

  @Test
  public void twoNullsAreEquivalent() {
    ResultSet a = ResultSet.of(struct("id", CoralTypeKind.INT), rows(new Object[] { null }));
    ResultSet b = ResultSet.of(struct("id", CoralTypeKind.INT), rows(new Object[] { null }));
    Assert.assertTrue(new ResultSetComparator().compare(a, b).isEquivalent());
  }

  @Test
  public void nullVsValueIsNotEquivalent() {
    ResultSet a = ResultSet.of(struct("id", CoralTypeKind.INT), rows(new Object[] { null }));
    ResultSet b = ResultSet.of(struct("id", CoralTypeKind.INT), rows(new Object[] { 1 }));
    Assert.assertFalse(new ResultSetComparator().compare(a, b).isEquivalent());
  }

  // ---- Ordered vs unordered ------------------------------------------------------

  @Test
  public void unorderedComparisonIgnoresRowOrder() {
    StructType schema = struct("id", CoralTypeKind.INT);
    ResultSet a = ResultSet.of(schema, rows(new Object[] { 1 }, new Object[] { 2 }));
    ResultSet b = ResultSet.of(schema, rows(new Object[] { 2 }, new Object[] { 1 }));
    Assert.assertTrue(new ResultSetComparator().compare(a, b).isEquivalent());
  }

  @Test
  public void orderedComparisonRespectsRowOrder() {
    StructType schema = struct("id", CoralTypeKind.INT);
    ResultSet a = ResultSet.of(schema, rows(new Object[] { 1 }, new Object[] { 2 }));
    ResultSet b = ResultSet.of(schema, rows(new Object[] { 2 }, new Object[] { 1 }));
    ComparisonConfig cfg = ComparisonConfig.builder().orderedComparison(true).build();
    Assert.assertFalse(new ResultSetComparator(cfg).compare(a, b).isEquivalent());
  }

  @Test
  public void unorderedComparisonAlignsMixedNumericTypes() {
    // Regression: the canonical row-sort key used in unordered mode must collapse
    // Integer(1) and Long(1) to the same key so a multiset of Integer rows on one side
    // aligns with the same logical multiset of Long rows on the other.
    StructType schemaA = struct("id", CoralTypeKind.INT);
    StructType schemaB = struct("id", CoralTypeKind.BIGINT);
    ResultSet a = ResultSet.of(schemaA, rows(new Object[] { 1 }, new Object[] { 2 }));
    ResultSet b = ResultSet.of(schemaB, rows(new Object[] { 2L }, new Object[] { 1L }));
    Assert.assertTrue(new ResultSetComparator().compare(a, b).isEquivalent(),
        "Integer/Long multiset should align regardless of element order");
  }

  // ---- Helpers -------------------------------------------------------------------

  private static StructType twoCol() {
    return struct("id", CoralTypeKind.INT, "v", CoralTypeKind.DOUBLE);
  }

  private static StructType struct(Object... nameKindPairs) {
    List<StructField> fields = new ArrayList<>();
    for (int i = 0; i < nameKindPairs.length; i += 2) {
      String name = (String) nameKindPairs[i];
      CoralTypeKind kind = (CoralTypeKind) nameKindPairs[i + 1];
      fields.add(StructField.of(name, PrimitiveType.of(kind, true)));
    }
    return StructType.of(fields, true);
  }

  private static List<Object[]> rows(Object[]... rows) {
    return Arrays.asList(rows);
  }
}
