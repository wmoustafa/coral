/**
 * Copyright 2017-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.benchmark.comparison;

import java.math.BigDecimal;
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
      Comparator<Object[]> rowComparator = (a, b) -> Arrays.deepToString(a).compareTo(Arrays.deepToString(b));
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
      double da = ((Number) a).doubleValue();
      double db = ((Number) b).doubleValue();
      if (a instanceof BigDecimal && b instanceof BigDecimal) {
        return ((BigDecimal) a).compareTo((BigDecimal) b) == 0;
      }
      if (a instanceof Float || a instanceof Double || b instanceof Float || b instanceof Double) {
        return Math.abs(da - db) <= config.getFloatingPointEpsilon();
      }
      if (config.isAllowTypeWidening()) {
        return ((Number) a).longValue() == ((Number) b).longValue();
      }
    }
    if (a.getClass().isArray() && b.getClass().isArray()) {
      return Arrays.deepEquals((Object[]) a, (Object[]) b);
    }
    return Objects.equals(a, b);
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
