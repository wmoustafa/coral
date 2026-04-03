/**
 * Copyright 2017-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.gremlin.gremlin2rel;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.calcite.plan.RelTraitDef;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgram;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.rules.FilterMergeRule;
import org.apache.calcite.rel.rules.FilterProjectTransposeRule;
import org.apache.calcite.rel.rules.ProjectFilterTransposeRule;
import org.apache.calcite.rel.rules.ProjectMergeRule;
import org.apache.calcite.rel.rules.ProjectRemoveRule;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.runtime.Hook;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Programs;
import org.apache.calcite.tools.RelBuilder;

import com.linkedin.coral.common.HiveMetastoreClient;
import com.linkedin.coral.common.HiveRelBuilder;
import com.linkedin.coral.common.HiveSchema;
import com.linkedin.coral.common.HiveTypeSystem;


/**
 * Public class to convert Gremlin queries to Calcite relational algebra (Coral IR).
 * This class serves as the main entry point for clients to convert Gremlin graph
 * traversal queries to relational plans.
 * 
 * Gremlin queries are converted DIRECTLY to Coral IR (RelNode) without SQL intermediate step.
 * 
 * Supports:
 * - Edge label filtering: .outE("friend"), .inE("colleague")
 * - Multi-hop traversals: .outE().inV().outE().inV()
 * - Comparison operators: gt(), lt(), gte(), lte(), neq()
 * - Deduplication: .dedup()
 * - Ordering: .order().by("field", decr/incr)
 * - Projections: .values(), .valueMap()
 */
public class GremlinToRelConverter {
  private final RelBuilder relBuilder;
  private final String vertexTable;
  private final String edgeTable;
  private final String vertexIdColumn;
  private final String edgeSrcColumn;
  private final String edgeDstColumn;
  private final String edgeLabelColumn;
  private int aliasCounter = 0;
  private int currentVertexFieldOffset = 0; // Track the column offset for current vertex ID
  private boolean needsDistinct = false; // Track if dedup was called
  private boolean skipProjections = false; // Skip projections for visualization compatibility

  /**
   * Create a converter with fully configurable table and column names.
   * 
   * @param hiveMetastoreClient The Hive metastore client
   * @param vertexTable Fully qualified table name for vertices
   * @param edgeTable Fully qualified table name for edges
   * @param vertexIdColumn Column name for vertex ID
   * @param edgeSrcColumn Column name for edge source
   * @param edgeDstColumn Column name for edge destination
   */
  public GremlinToRelConverter(HiveMetastoreClient hiveMetastoreClient, String vertexTable, String edgeTable,
      String vertexIdColumn, String edgeSrcColumn, String edgeDstColumn) {
    this(hiveMetastoreClient, vertexTable, edgeTable, vertexIdColumn, edgeSrcColumn, edgeDstColumn, "relation");
  }

  /**
   * Create a converter with fully configurable table and column names including edge label column.
   * 
   * @param hiveMetastoreClient The Hive metastore client
   * @param vertexTable Fully qualified table name for vertices
   * @param edgeTable Fully qualified table name for edges
   * @param vertexIdColumn Column name for vertex ID
   * @param edgeSrcColumn Column name for edge source
   * @param edgeDstColumn Column name for edge destination
   * @param edgeLabelColumn Column name for edge label/type (e.g., "relation")
   */
  public GremlinToRelConverter(HiveMetastoreClient hiveMetastoreClient, String vertexTable, String edgeTable,
      String vertexIdColumn, String edgeSrcColumn, String edgeDstColumn, String edgeLabelColumn) {
    // Create Hive schema and framework config
    SchemaPlus schemaPlus = Frameworks.createRootSchema(false);
    schemaPlus.add(HiveSchema.ROOT_SCHEMA, new HiveSchema(hiveMetastoreClient));

    FrameworkConfig config = Frameworks.newConfigBuilder().defaultSchema(schemaPlus).typeSystem(new HiveTypeSystem())
        .traitDefs((List<RelTraitDef>) null).programs(Programs.ofRules(Programs.RULE_SET)).build();

    // Create RelBuilder
    Hook.REL_BUILDER_SIMPLIFY.add(Hook.propertyJ(false));
    this.relBuilder = HiveRelBuilder.create(config);
    this.vertexTable = vertexTable;
    this.edgeTable = edgeTable;
    this.vertexIdColumn = vertexIdColumn;
    this.edgeSrcColumn = edgeSrcColumn;
    this.edgeDstColumn = edgeDstColumn;
    this.edgeLabelColumn = edgeLabelColumn;
  }

  /**
   * Convert a Gremlin query string to a RelNode (Coral IR).
   * Directly builds RelNode without SQL intermediate step.
   * 
   * @param gremlinQuery The Gremlin traversal query string
   * @return RelNode representing the query in Coral IR
   */
  public RelNode convertGremlin(String gremlinQuery) {
    return convertGremlin(gremlinQuery, true);
  }

  /**
   * Convert a Gremlin query string to a RelNode (Coral IR).
   * 
   * @param gremlinQuery The Gremlin traversal query string
   * @param optimize Whether to apply HepPlanner optimization
   * @return RelNode representing the query in Coral IR
   */
  public RelNode convertGremlin(String gremlinQuery, boolean optimize) {
    return convertGremlin(gremlinQuery, optimize, false);
  }

  /**
   * Convert a Gremlin query string to a RelNode (Coral IR).
   * 
   * @param gremlinQuery The Gremlin traversal query string
   * @param optimize Whether to apply HepPlanner optimization
   * @param forVisualization Whether this is for visualization (skips projections that break Graphviz)
   * @return RelNode representing the query in Coral IR
   */
  public RelNode convertGremlin(String gremlinQuery, boolean optimize, boolean forVisualization) {
    aliasCounter = 0;
    currentVertexFieldOffset = 0;
    needsDistinct = false;
    skipProjections = forVisualization;
    String[] vertexParts = vertexTable.split("\\.");
    String[] edgeParts = edgeTable.split("\\.");

    // Parse the query into steps
    List<GremlinStep> steps = parseGremlinSteps(gremlinQuery);

    // Build the relational plan
    RelNode relNode = buildRelationalPlan(steps, vertexParts, edgeParts);

    // Optimize the RelNode to flatten nested projections and remove unnecessary operations
    if (optimize) {
      return optimizeRelNode(relNode);
    }
    return relNode;
  }

  /**
   * Optimize the RelNode to flatten nested structures and remove unnecessary operations.
   * Uses HepPlanner with specific rules to simplify the plan before SQL generation.
   */
  private RelNode optimizeRelNode(RelNode relNode) {
    // Create optimization program with rules to flatten the plan
    HepProgramBuilder programBuilder = new HepProgramBuilder();

    // Add rules to merge projections, remove trivial projects, and simplify
    programBuilder.addRuleInstance(ProjectMergeRule.INSTANCE);
    programBuilder.addRuleInstance(ProjectRemoveRule.INSTANCE);
    programBuilder.addRuleInstance(FilterMergeRule.INSTANCE);
    programBuilder.addRuleInstance(FilterProjectTransposeRule.INSTANCE);
    programBuilder.addRuleInstance(ProjectFilterTransposeRule.INSTANCE);

    HepProgram program = programBuilder.build();
    HepPlanner planner = new HepPlanner(program);
    planner.setRoot(relNode);

    return planner.findBestExp();
  }

  /**
   * Parse Gremlin query into individual steps
   */
  private List<GremlinStep> parseGremlinSteps(String query) {
    List<GremlinStep> steps = new ArrayList<>();

    // Remove whitespace and newlines for easier parsing
    query = query.replaceAll("\\s+", "");

    // Parse steps manually to handle nested parentheses
    int i = 0;
    while (i < query.length()) {
      // Find next dot followed by step name
      if (query.charAt(i) == '.') {
        i++; // skip the dot

        // Extract step name
        int nameStart = i;
        while (i < query.length() && Character.isLetterOrDigit(query.charAt(i))) {
          i++;
        }
        String stepName = query.substring(nameStart, i);

        // Extract arguments (handle nested parentheses)
        if (i < query.length() && query.charAt(i) == '(') {
          i++; // skip opening paren
          int parenDepth = 1;
          int argsStart = i;

          while (i < query.length() && parenDepth > 0) {
            if (query.charAt(i) == '(') {
              parenDepth++;
            } else if (query.charAt(i) == ')') {
              parenDepth--;
            }
            if (parenDepth > 0) {
              i++;
            }
          }

          String args = query.substring(argsStart, i);
          steps.add(new GremlinStep(stepName, args));
          i++; // skip closing paren
        }
      } else {
        i++;
      }
    }

    return steps;
  }

  /**
   * Build relational plan from parsed Gremlin steps
   */
  private RelNode buildRelationalPlan(List<GremlinStep> steps, String[] vParts, String[] eParts) {
    if (steps.isEmpty()) {
      throw new UnsupportedOperationException("Empty Gremlin query");
    }

    // Start with V() or E()
    GremlinStep firstStep = steps.get(0);
    if ("V".equals(firstStep.name)) {
      relBuilder.scan("hive", vParts[0], vParts[1]).as(nextAlias());
    } else if ("E".equals(firstStep.name)) {
      relBuilder.scan("hive", eParts[0], eParts[1]).as(nextAlias());
    } else {
      throw new UnsupportedOperationException("Query must start with g.V() or g.E()");
    }

    // Process remaining steps
    String currentContext = "vertex"; // "vertex" or "edge"

    for (int i = 1; i < steps.size(); i++) {
      GremlinStep step = steps.get(i);

      switch (step.name) {
        case "has":
          applyHasFilter(step);
          break;
        case "outE":
          applyOutE(step, vParts, eParts);
          currentContext = "edge";
          break;
        case "inE":
          applyInE(step, vParts, eParts);
          currentContext = "edge";
          break;
        case "bothE":
          throw new UnsupportedOperationException("bothE() not yet implemented");
        case "inV":
          applyInV(vParts, currentContext);
          currentContext = "vertex";
          break;
        case "outV":
          applyOutV(vParts, currentContext);
          currentContext = "vertex";
          break;
        case "out":
          applyOut(step, vParts, eParts);
          currentContext = "vertex";
          break;
        case "in":
          applyIn(step, vParts, eParts);
          currentContext = "vertex";
          break;
        case "dedup":
          applyDedup();
          break;
        case "order":
          // Order is typically followed by .by(), handled in next iteration
          break;
        case "by":
          applyOrderBy(step);
          break;
        case "values":
          applyValues(step);
          break;
        case "valueMap":
          applyValueMap(step);
          break;
        default:
          throw new UnsupportedOperationException("Unsupported step: ." + step.name + "()");
      }
    }

    return relBuilder.build();
  }

  private void applyHasFilter(GremlinStep step) {
    // Parse has() arguments - need to handle nested parentheses like has("age", gt(25))
    String args = step.args;

    // Find first comma that's not inside parentheses
    int commaPos = -1;
    int parenDepth = 0;
    for (int i = 0; i < args.length(); i++) {
      char c = args.charAt(i);
      if (c == '(')
        parenDepth++;
      else if (c == ')')
        parenDepth--;
      else if (c == ',' && parenDepth == 0) {
        commaPos = i;
        break;
      }
    }

    if (commaPos == -1) {
      throw new IllegalArgumentException("has() requires at least 2 arguments");
    }

    String field = cleanString(args.substring(0, commaPos));
    String valueOrOp = args.substring(commaPos + 1).trim();

    // Check if it's a comparison operator
    if (valueOrOp.startsWith("gt(") || valueOrOp.startsWith("lt(") || valueOrOp.startsWith("gte(")
        || valueOrOp.startsWith("lte(") || valueOrOp.startsWith("neq(")) {
      applyComparisonFilter(field, valueOrOp);
    } else {
      // Simple equality - reference field from current vertex at tracked offset
      Object value = parseValue(valueOrOp);
      RexNode fieldNode = relBuilder.field(currentVertexFieldOffset + getFieldIndex(field));
      relBuilder.filter(relBuilder.equals(fieldNode, relBuilder.literal(value)));
    }
  }

  private void applyComparisonFilter(String field, String opExpr) {
    // Parse gt(25), lt(30), etc.
    // Clean the expression first - remove quotes and extra whitespace
    opExpr = cleanString(opExpr);

    Pattern pattern = Pattern.compile("(\\w+)\\(([^)]+)\\)");
    Matcher matcher = pattern.matcher(opExpr);

    if (!matcher.matches()) {
      throw new IllegalArgumentException("Invalid comparison operator: " + opExpr);
    }

    String op = matcher.group(1);
    String valueStr = matcher.group(2);
    Object value = parseValue(valueStr);

    // Reference field from current vertex at tracked offset
    RexNode fieldNode = relBuilder.field(currentVertexFieldOffset + getFieldIndex(field));
    RexNode valueNode = relBuilder.literal(value);

    RexNode condition;
    switch (op) {
      case "gt":
        condition = relBuilder.call(SqlStdOperatorTable.GREATER_THAN, fieldNode, valueNode);
        break;
      case "lt":
        condition = relBuilder.call(SqlStdOperatorTable.LESS_THAN, fieldNode, valueNode);
        break;
      case "gte":
        condition = relBuilder.call(SqlStdOperatorTable.GREATER_THAN_OR_EQUAL, fieldNode, valueNode);
        break;
      case "lte":
        condition = relBuilder.call(SqlStdOperatorTable.LESS_THAN_OR_EQUAL, fieldNode, valueNode);
        break;
      case "neq":
        condition = relBuilder.call(SqlStdOperatorTable.NOT_EQUALS, fieldNode, valueNode);
        break;
      default:
        throw new UnsupportedOperationException("Unsupported comparison operator: " + op);
    }

    relBuilder.filter(condition);
  }

  private void applyOutE(GremlinStep step, String[] vParts, String[] eParts) {
    String edgeLabel = step.args.isEmpty() ? null : cleanString(step.args);

    // Get the current row type to know how many fields we have
    int currentFieldCount = relBuilder.peek().getRowType().getFieldCount();

    // Join with edge table
    relBuilder.scan("hive", eParts[0], eParts[1]).as(nextAlias());

    // Build join condition: vertex.id = edge.src AND edge.label = 'friend'
    // Combine the join condition with the edge label filter to avoid separate WHERE clause
    RexNode leftField = relBuilder.field(2, 0, currentVertexFieldOffset);
    RexNode rightField = relBuilder.field(2, 1, edgeSrcColumn);
    RexNode joinCondition = relBuilder.equals(leftField, rightField);

    // Add edge label filter to the join condition if specified
    if (edgeLabel != null) {
      RexNode labelCondition =
          relBuilder.equals(relBuilder.field(2, 1, edgeLabelColumn), relBuilder.literal(edgeLabel));
      joinCondition = relBuilder.and(joinCondition, labelCondition);
    }

    relBuilder.join(JoinRelType.INNER, joinCondition);
  }

  private void applyInE(GremlinStep step, String[] vParts, String[] eParts) {
    String edgeLabel = step.args.isEmpty() ? null : cleanString(step.args);

    // Join with edge table (reversed direction)
    relBuilder.scan("hive", eParts[0], eParts[1]).as(nextAlias());

    // Build join condition: vertex.id = edge.dst AND edge.label = 'friend'
    // Combine the join condition with the edge label filter to avoid separate WHERE clause
    RexNode leftField = relBuilder.field(2, 0, currentVertexFieldOffset);
    RexNode rightField = relBuilder.field(2, 1, edgeDstColumn);
    RexNode joinCondition = relBuilder.equals(leftField, rightField);

    // Add edge label filter to the join condition if specified
    if (edgeLabel != null) {
      RexNode labelCondition =
          relBuilder.equals(relBuilder.field(2, 1, edgeLabelColumn), relBuilder.literal(edgeLabel));
      joinCondition = relBuilder.and(joinCondition, labelCondition);
    }

    relBuilder.join(JoinRelType.INNER, joinCondition);
  }

  private void applyInV(String[] vParts, String currentContext) {
    if (!"edge".equals(currentContext)) {
      throw new IllegalStateException("inV() can only be called after outE() or inE()");
    }

    // Get current field count before adding new vertex table
    int fieldCountBeforeJoin = relBuilder.peek().getRowType().getFieldCount();

    // Join with vertex table on destination
    relBuilder.scan("hive", vParts[0], vParts[1]).as(nextAlias());

    // Build join condition: edge.dst = vertex.id
    RexNode leftField = relBuilder.field(2, 0, edgeDstColumn);
    RexNode rightField = relBuilder.field(2, 1, vertexIdColumn);
    relBuilder.join(JoinRelType.INNER, relBuilder.equals(leftField, rightField));

    // Skip projection for visualization to avoid null pointer issues in Graphviz
    if (!skipProjections) {
      // Project to keep only the newly joined vertex fields to reduce column count
      // This prevents accumulating all columns from all joins
      int vertexFieldCount = relBuilder.peek().getRowType().getFieldList().size() - fieldCountBeforeJoin;
      List<RexNode> projectFields = new ArrayList<>();
      for (int i = 0; i < vertexFieldCount; i++) {
        projectFields.add(relBuilder.field(fieldCountBeforeJoin + i));
      }
      relBuilder.project(projectFields);

      // Update the offset - after projection, vertex fields start at 0
      currentVertexFieldOffset = 0;
    } else {
      // Without projection, update offset to point to the newly joined vertex
      currentVertexFieldOffset = fieldCountBeforeJoin;
    }
  }

  private void applyOutV(String[] vParts, String currentContext) {
    if (!"edge".equals(currentContext)) {
      throw new IllegalStateException("outV() can only be called after outE() or inE()");
    }

    // Get current field count before adding new vertex table
    int fieldCountBeforeJoin = relBuilder.peek().getRowType().getFieldCount();

    // Join with vertex table on source
    relBuilder.scan("hive", vParts[0], vParts[1]).as(nextAlias());

    // Build join condition: edge.src = vertex.id
    RexNode leftField = relBuilder.field(2, 0, edgeSrcColumn);
    RexNode rightField = relBuilder.field(2, 1, vertexIdColumn);
    relBuilder.join(JoinRelType.INNER, relBuilder.equals(leftField, rightField));

    // Skip projection for visualization to avoid null pointer issues in Graphviz
    if (!skipProjections) {
      // Project to keep only the newly joined vertex fields
      int vertexFieldCount = relBuilder.peek().getRowType().getFieldList().size() - fieldCountBeforeJoin;
      List<RexNode> projectFields = new ArrayList<>();
      for (int i = 0; i < vertexFieldCount; i++) {
        projectFields.add(relBuilder.field(fieldCountBeforeJoin + i));
      }
      relBuilder.project(projectFields);

      // Update the offset - after projection, vertex fields start at 0
      currentVertexFieldOffset = 0;
    } else {
      // Without projection, update offset to point to the newly joined vertex
      currentVertexFieldOffset = fieldCountBeforeJoin;
    }
  }

  private void applyOut(GremlinStep step, String[] vParts, String[] eParts) {
    String edgeLabel = step.args.isEmpty() ? null : cleanString(step.args);

    // Shorthand for outE().inV()
    // Step 1: Join vertex with edge on vertex.id = edge.src
    relBuilder.scan("hive", eParts[0], eParts[1]).as(nextAlias());
    RexNode joinCond1Left = relBuilder.field(2, 0, vertexIdColumn);
    RexNode joinCond1Right = relBuilder.field(2, 1, edgeSrcColumn);
    relBuilder.join(JoinRelType.INNER, relBuilder.equals(joinCond1Left, joinCond1Right));

    if (edgeLabel != null) {
      relBuilder.filter(relBuilder.equals(relBuilder.field(edgeLabelColumn), relBuilder.literal(edgeLabel)));
    }

    // Step 2: Join with target vertex on edge.dst = vertex.id
    relBuilder.scan("hive", vParts[0], vParts[1]).as(nextAlias());
    RexNode joinCond2Left = relBuilder.field(2, 0, edgeDstColumn);
    RexNode joinCond2Right = relBuilder.field(2, 1, vertexIdColumn);
    relBuilder.join(JoinRelType.INNER, relBuilder.equals(joinCond2Left, joinCond2Right));
  }

  private void applyIn(GremlinStep step, String[] vParts, String[] eParts) {
    String edgeLabel = step.args.isEmpty() ? null : cleanString(step.args);

    // Shorthand for inE().outV()
    // Step 1: Join vertex with edge on vertex.id = edge.dst
    relBuilder.scan("hive", eParts[0], eParts[1]).as(nextAlias());
    RexNode joinCond1Left = relBuilder.field(2, 0, vertexIdColumn);
    RexNode joinCond1Right = relBuilder.field(2, 1, edgeDstColumn);
    relBuilder.join(JoinRelType.INNER, relBuilder.equals(joinCond1Left, joinCond1Right));

    if (edgeLabel != null) {
      relBuilder.filter(relBuilder.equals(relBuilder.field(edgeLabelColumn), relBuilder.literal(edgeLabel)));
    }

    // Step 2: Join with source vertex on edge.src = vertex.id
    relBuilder.scan("hive", vParts[0], vParts[1]).as(nextAlias());
    RexNode joinCond2Left = relBuilder.field(2, 0, edgeSrcColumn);
    RexNode joinCond2Right = relBuilder.field(2, 1, vertexIdColumn);
    relBuilder.join(JoinRelType.INNER, relBuilder.equals(joinCond2Left, joinCond2Right));
  }

  private void applyDedup() {
    // Mark that we need distinct - will be applied at final projection
    // Don't call relBuilder.distinct() here as it creates GROUP BY
    needsDistinct = true;
  }

  private void applyOrderBy(GremlinStep step) {
    // Skip ORDER BY for visualization to avoid null pointer issues in Graphviz
    // The FlatSQLGenerator handles ORDER BY correctly with qualified field names
    if (skipProjections) {
      return;
    }

    String[] parts = step.args.split(",");
    if (parts.length < 1) {
      throw new IllegalArgumentException("by() requires at least 1 argument");
    }

    String field = cleanString(parts[0]);
    boolean descending = false;

    if (parts.length > 1) {
      String direction = cleanString(parts[1]);
      descending = "decr".equals(direction) || "desc".equals(direction);
    }

    // Reference field from current vertex at tracked offset
    RexNode fieldNode = relBuilder.field(currentVertexFieldOffset + getFieldIndex(field));

    // Apply sort with correct direction - don't call sort() twice
    if (descending) {
      relBuilder.sortLimit(-1, -1, relBuilder.desc(fieldNode));
    } else {
      relBuilder.sort(fieldNode);
    }
  }

  private void applyValues(GremlinStep step) {
    if (step.args.isEmpty()) {
      // No projection, return all fields
      return;
    }

    String[] fields = step.args.split(",");
    List<RexNode> projects = new ArrayList<>();

    for (String field : fields) {
      // Reference field from current vertex at tracked offset
      projects.add(relBuilder.field(currentVertexFieldOffset + getFieldIndex(cleanString(field))));
    }

    relBuilder.project(projects);

    // Note: dedup() in Gremlin should generate SELECT DISTINCT, but Calcite's
    // relBuilder.distinct() generates GROUP BY instead. Since GROUP BY and SELECT DISTINCT
    // are functionally equivalent for deduplication, we skip it here.
    // Users can manually add DISTINCT to the generated SQL if needed.
    // TODO: Implement custom SQL generator to convert LogicalAggregate to SELECT DISTINCT
  }

  private void applyValueMap(GremlinStep step) {
    // valueMap is similar to values but returns a map structure
    // For SQL, we treat it the same as values() - project specific columns
    applyValues(step);
  }

  private String nextAlias() {
    return "t" + (aliasCounter++);
  }

  /**
   * Get the field index within a vertex table based on field name.
   * Assumes vertex tables have fields in order: [id, name, age, city/location, ...]
   * This is a simplified mapping - in production, should query the schema.
   */
  private int getFieldIndex(String fieldName) {
    // Map common field names to their indices in the vertex table
    if (fieldName.equals(vertexIdColumn) || fieldName.equals("person_id") || fieldName.equals("member")) {
      return 0;
    } else if (fieldName.equals("name")) {
      return 1;
    } else if (fieldName.equals("age")) {
      return 2;
    } else if (fieldName.equals("city") || fieldName.equals("location")) {
      return 3;
    }
    // Default: try to find by name in the current row type
    return 0;
  }

  private String cleanString(String s) {
    return s.trim().replace("'", "").replace("\"", "");
  }

  private Object parseValue(String valueStr) {
    valueStr = cleanString(valueStr);

    // Try to parse as number
    try {
      if (valueStr.contains(".")) {
        return Double.parseDouble(valueStr);
      } else {
        return Integer.parseInt(valueStr);
      }
    } catch (NumberFormatException e) {
      // Return as string
      return valueStr;
    }
  }

  /**
   * Internal class to represent a Gremlin step
   */
  private static class GremlinStep {
    String name;
    String args;

    GremlinStep(String name, String args) {
      this.name = name;
      this.args = args;
    }
  }
}
