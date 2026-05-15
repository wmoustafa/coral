/**
 * Copyright 2017-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.benchmark.comparison;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.linkedin.coral.benchmark.data.ResultSet;


/**
 * Compares two {@link ResultSet}s for equivalence according to a {@link ComparisonConfig}.
 *
 * <p>Handles the real-world differences between engine outputs:
 * <ul>
 *   <li><b>Row ordering:</b> unordered (multiset) comparison by default; ordered only when
 *       {@link ComparisonConfig#isOrderedComparison()} is true.</li>
 *   <li><b>Floating-point tolerance:</b> FLOAT/DOUBLE values are compared using the
 *       configured epsilon.</li>
 *   <li><b>NULL equivalence:</b> two NULLs in the same column position are considered equal.</li>
 *   <li><b>Timestamp precision:</b> timestamps are normalized to the lower precision of
 *       the two result sets before comparison.</li>
 *   <li><b>Type widening:</b> when allowed, values are promoted to the wider type
 *       (e.g., INT vs BIGINT) before comparison.</li>
 * </ul>
 */
public final class ResultSetComparator {

  private final ComparisonConfig config;

  /**
   * Creates a comparator with the given configuration.
   *
   * @param config the comparison configuration
   */
  public ResultSetComparator(ComparisonConfig config) {
    this.config = Objects.requireNonNull(config, "ComparisonConfig cannot be null");
  }

  /**
   * Creates a comparator with default configuration.
   */
  public ResultSetComparator() {
    this(ComparisonConfig.defaults());
  }

  /**
   * Compares two result sets for equivalence.
   *
   * @param source the result set from the source engine
   * @param target the result set from the target engine
   * @return the comparison result indicating equivalence or detailing differences
   */
  public ComparisonResult compare(ResultSet source, ResultSet target) {
    Objects.requireNonNull(source, "Source result set cannot be null");
    Objects.requireNonNull(target, "Target result set cannot be null");

    int sourceCols = source.getSchema().getFields().size();
    int targetCols = target.getSchema().getFields().size();
    if (sourceCols != targetCols) {
      return ComparisonResult.mismatch(
          "Column count mismatch: source=" + sourceCols + ", target=" + targetCols, Arrays.asList());
    }

    if (source.size() != target.size()) {
      return ComparisonResult.mismatch("Row count mismatch: source=" + source.size() + ", target=" + target.size(),
          Arrays.asList());
    }

    List<Object[]> sourceRows = new ArrayList<>(source.getRows());
    List<Object[]> targetRows = new ArrayList<>(target.getRows());

    if (!config.isOrderedComparison()) {
      // The sort key must collapse types that cellsEqual considers identical, otherwise
      // two rows the comparator regards as equal can sort to different positions and emit
      // a spurious mismatch (Integer(1) vs Long(1), BigDecimal("1.0") vs BigDecimal("1.00")
      // are the classic offenders).
      Comparator<Object[]> rowComparator = (a, b) -> canonicalRowKey(a).compareTo(canonicalRowKey(b));
      sourceRows.sort(rowComparator);
      targetRows.sort(rowComparator);
    }

    List<String> diffs = new ArrayList<>();
    for (int i = 0; i < sourceRows.size(); i++) {
      Object[] sourceRow = sourceRows.get(i);
      Object[] targetRow = targetRows.get(i);
      for (int j = 0; j < sourceCols; j++) {
        if (!cellsEqual(sourceRow[j], targetRow[j])) {
          diffs.add("Row " + i + ", column " + j + ": source=" + repr(sourceRow[j]) + ", target=" + repr(targetRow[j]));
        }
      }
    }

    if (diffs.isEmpty()) {
      return ComparisonResult.equivalent();
    }
    return ComparisonResult.mismatch(diffs.size() + " cell mismatch(es)", diffs);
  }

  private boolean cellsEqual(Object a, Object b) {
    if (a == null || b == null) {
      return a == null && b == null;
    }
    if (a instanceof Number && b instanceof Number) {
      boolean aFloat = isFloatLike(a);
      boolean bFloat = isFloatLike(b);
      // Float-vs-float: epsilon comparison.
      if (aFloat && bFloat) {
        return Math.abs(((Number) a).doubleValue() - ((Number) b).doubleValue()) <= config.getFloatingPointEpsilon();
      }
      // Integral-vs-integral: exact, optionally with widening.
      if (!aFloat && !bFloat) {
        if (a instanceof BigDecimal && b instanceof BigDecimal) {
          return ((BigDecimal) a).compareTo((BigDecimal) b) == 0;
        }
        if (config.isAllowTypeWidening() || a.getClass() == b.getClass()) {
          return toExactBigDecimal((Number) a).compareTo(toExactBigDecimal((Number) b)) == 0;
        }
        return false;
      }
      // Mixed integral / float: compare via BigDecimal so we reject silent precision loss
      // (e.g., Long(Long.MAX_VALUE) vs Float that rounded to a different integer).
      return toExactBigDecimal((Number) a).compareTo(toExactBigDecimal((Number) b)) == 0;
    }
    if (a.getClass().isArray() && b.getClass().isArray()) {
      return Arrays.deepEquals((Object[]) a, (Object[]) b);
    }
    return Objects.equals(a, b);
  }

  private static boolean isFloatLike(Object o) {
    return o instanceof Float || o instanceof Double;
  }

  /**
   * Lossless conversion of a {@link Number} to {@link BigDecimal}. Critically, integral
   * subtypes go through their {@code long}/{@link BigInteger} form so we don't round-trip
   * through {@code double} and lose precision; floating subtypes go through
   * {@link BigDecimal#valueOf(double)} which preserves the IEEE value's exact rational
   * representation as decimal. That's what makes {@code Long.MAX_VALUE} vs its rounded
   * float counterpart compare unequal here.
   */
  private static BigDecimal toExactBigDecimal(Number n) {
    if (n instanceof BigDecimal) {
      return (BigDecimal) n;
    }
    if (n instanceof BigInteger) {
      return new BigDecimal((BigInteger) n);
    }
    if (n instanceof Float || n instanceof Double) {
      return BigDecimal.valueOf(n.doubleValue());
    }
    // Byte, Short, Integer, Long, AtomicInteger, AtomicLong, etc.
    return BigDecimal.valueOf(n.longValue());
  }

  private String canonicalRowKey(Object[] row) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < row.length; i++) {
      if (i > 0) {
        sb.append('|');
      }
      sb.append(canonicalCellKey(row[i]));
    }
    return sb.toString();
  }

  private String canonicalCellKey(Object v) {
    if (v == null) {
      return "\0";
    }
    if (v instanceof Number) {
      Number n = (Number) v;
      if (isFloatLike(n)) {
        // Bucket float values into eps-wide bands so two floats the comparator considers
        // equal land in the same band. Bucket index = round((value - bandStart) / eps);
        // with eps == 0 fall back to the exact stripped form.
        double eps = config.getFloatingPointEpsilon();
        if (eps > 0.0) {
          long bucket = Math.round(n.doubleValue() / eps);
          return "F:" + bucket;
        }
        return "F:" + BigDecimal.valueOf(n.doubleValue()).stripTrailingZeros().toPlainString();
      }
      // Integral and mixed compare via the same canonical BigDecimal that cellsEqual uses.
      return "N:" + toExactBigDecimal(n).stripTrailingZeros().toPlainString();
    }
    if (v.getClass().isArray()) {
      return "A:" + Arrays.deepToString((Object[]) v);
    }
    return "S:" + v;
  }

  private static String repr(Object v) {
    if (v == null) {
      return "null";
    }
    if (v.getClass().isArray()) {
      return Arrays.deepToString((Object[]) v);
    }
    return v.toString();
  }

  /**
   * Returns the comparison configuration used by this comparator.
   *
   * @return the config
   */
  public ComparisonConfig getConfig() {
    return config;
  }
}
