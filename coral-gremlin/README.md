# Coral-Gremlin: Gremlin to Coral IR Conversion

## Overview

**Coral-Gremlin** converts Apache TinkerPop Gremlin graph traversal queries to Coral IR (Intermediate Representation), enabling Gremlin queries to execute on relational engines like Spark.

**Key Feature:** The module is **fully generic** - it works with any vertex and edge tables you specify.

## Quick Start

```java
// Create converter with YOUR table and column names
HiveMscAdapter mscAdapter = new HiveMscAdapter(metastoreClient);
GremlinToRelConverter converter = new GremlinToRelConverter(
    mscAdapter,
    "your_db.vertex_table",  // Your vertex table
    "your_db.edge_table",    // Your edge table
    "vertex_id",             // Vertex ID column
    "src_vertex",            // Edge source column
    "dst_vertex"             // Edge destination column
);

// Convert Gremlin to Coral IR
RelNode relNode = converter.convertGremlin("g.V().has('name', 'Alice').out()");
```

## Configuration Examples

### Social Network (with custom columns)
```java
GremlinToRelConverter socialGraph = new GremlinToRelConverter(
    mscAdapter,
    "social.users",        // Vertex table
    "social.friendships",  // Edge table
    "user_id",            // Vertex ID column
    "from_user",          // Edge source column
    "to_user"             // Edge destination column
);
```

### Product Catalog
```java
GremlinToRelConverter productGraph = new GremlinToRelConverter(
    mscAdapter,
    "catalog.products",     // Vertex table
    "catalog.relationships", // Edge table
    "product_id",           // Vertex ID column
    "from_product",         // Edge source column
    "to_product"            // Edge destination column
);
```

## Table Requirements

Your vertex and edge tables must exist in Hive Metastore. The schema is **fully flexible** - you specify the column names when creating the converter.

**Example schema:**

```sql
-- Vertex table (any schema with an ID column)
CREATE TABLE your_db.vertex_table (
  vertex_id STRING,   -- ID column (you specify the name)
  property1 STRING,   -- Any additional properties
  property2 INT,
  ...
);

-- Edge table (must have source and destination columns)
CREATE TABLE your_db.edge_table (
  src_vertex STRING,  -- Source vertex ID (you specify the name)
  dst_vertex STRING,  -- Destination vertex ID (you specify the name)
  edge_property STRING  -- Optional edge properties
);
```

**Key points:**
- Vertex table needs an ID column (any name you choose)
- Edge table needs source and destination columns (any names you choose)
- All other columns are optional and can be used in filters/projections

## How It Works

**Architecture: Direct Gremlin → Coral IR**

```
Gremlin Query → GremlinToRelConverter → Coral IR (RelNode) using Calcite RelBuilder
```

1. **Parse Gremlin Query**: Simple string-based parsing (prototype)
2. **Build RelNode Directly**: Uses Calcite's RelBuilder to construct relational algebra
3. **Convert to Target Dialect**: Coral IR converts to Spark SQL, Trino, etc.

**Direct Conversion Benefits:**
- ✅ No SQL intermediate step
- ✅ Direct construction of Coral IR using RelBuilder
- ✅ Cleaner architecture for graph-to-relational translation
- ✅ Full control over relational algebra construction

**Key Insight:** Graph traversals = Relational joins
- `.out()` → `JOIN edge_table ON vertex.id = edge.src`
- `.in()` → `JOIN edge_table ON vertex.id = edge.dst`
- `.both()` → `UNION` of both directions

## Query Examples

**Basic Queries:**
```groovy
g.V()                           // SELECT * FROM vertex_table
g.V().has('name', 'Alice')      // SELECT * FROM vertex_table WHERE name = 'Alice'
g.V().values('name', 'age')     // SELECT name, age FROM vertex_table
```

**Traversal Queries:**
```groovy
// Find Alice's connections (outgoing)
g.V().has('name', 'Alice').out()
// → SELECT v2.* FROM vertex_table v1 
//   JOIN edge_table e ON v1.id = e.src
//   JOIN vertex_table v2 ON e.dst = v2.id WHERE v1.name = 'Alice'

// Find who connects to Bob (incoming)
g.V().has('name', 'Bob').in()
// → Same as above but reversed join conditions

// Find all Bob's connections (bidirectional)
g.V().has('name', 'Bob').both()
// → UNION of out() and in() queries
```

## Spark Integration

```
Gremlin Query → GremlinToRelConverter → Coral IR → coral-spark → Spark SQL
```

**Example:**
```java
// 1. Convert Gremlin to Coral IR
GremlinToRelConverter converter = new GremlinToRelConverter(
    mscAdapter, "default.members", "default.connections",
    "member", "src_member", "dst_member"
);
RelNode coralIR = converter.convertGremlin("g.V().has('name', 'Alice').out()");

// 2. Convert Coral IR to Spark SQL (using existing coral-spark)
SparkSqlGenerator sparkSqlGen = new SparkSqlGenerator();
String sparkSQL = sparkSqlGen.getSparkSql(coralIR);

// 3. Execute on Spark
spark.sql(sparkSQL).show();
```

## REST API

### Starting the Service

First, start the coral-service:

```bash
cd coral-service
./gradlew bootRun
```

The service will start on `http://localhost:8080`

### Endpoint

**POST** `/api/gremlin/convert`

Converts a Gremlin query to Coral IR (RelNode) representation.

**Request Body:**
```json
{
  "gremlinQuery": "g.V().has('name', 'Alice').out()",
  "vertexTable": "default.members",
  "edgeTable": "default.connections",
  "vertexIdColumn": "member",
  "edgeSrcColumn": "src_member",
  "edgeDstColumn": "dst_member"
}
```

**Response:**
```json
{
  "gremlinQuery": "g.V().has('name', 'Alice').out()",
  "relNode": "LogicalProject(member=[$0], name=[$1], age=[$2], location=[$3])\n  LogicalJoin(condition=[=($0, $4)], joinType=[inner])\n    LogicalFilter(condition=[=($1, 'Alice')])\n      LogicalTableScan(table=[[hive, default, members]])\n    LogicalJoin(condition=[=($1, $2)], joinType=[inner])\n      LogicalTableScan(table=[[hive, default, connections]])\n      LogicalTableScan(table=[[hive, default, members]])",
  "success": true
}
```

### Using curl

```bash
curl -X POST http://localhost:8080/api/gremlin/convert \
  -H "Content-Type: application/json" \
  -d '{
    "gremlinQuery": "g.V().has(\"name\", \"Alice\").out()",
    "vertexTable": "default.members",
    "edgeTable": "default.connections",
    "vertexIdColumn": "member",
    "edgeSrcColumn": "src_member",
    "edgeDstColumn": "dst_member"
  }'
```

### Shell Script (Recommended)

Use the provided shell script that calls the REST API:

```bash
cd coral-service/scripts
./gremlin-to-rel.sh \
  --gremlin "g.V().has('name', 'Alice').out()" \
  --vertex-table "default.members" \
  --edge-table "default.connections" \
  --vertex-id-column "member" \
  --edge-src-column "src_member" \
  --edge-dst-column "dst_member"
```

**Note:** Make sure coral-service is running before using the shell script.

### Java API

Direct programmatic API for converting Gremlin queries:

```java
import com.linkedin.coral.gremlin.gremlin2rel.GremlinToRelConverter;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;

// Create converter
GremlinToRelConverter converter = new GremlinToRelConverter(
    metastoreClient,
    "default.members",
    "default.connections",
    "member",
    "src_member",
    "dst_member"
);

// Convert Gremlin to RelNode
RelNode relNode = converter.convertGremlin("g.V().has('name', 'Alice').out()");

// Get string representation
String relNodeString = RelOptUtil.toString(relNode);
System.out.println(relNodeString);
```

## Testing

Run tests:
```bash
./gradlew :coral-gremlin:test
```

Test coverage:
- ✅ Basic queries: `g.V()`, `g.E()`
- ✅ Filters: `g.V().has('name', 'John')`
- ✅ Projections: `g.V().values('name', 'age')`
- ✅ Traversals: `.out()`, `.in()`, `.both()`

## Supported Operations

### Currently Implemented
- ✅ `g.V()` - Get all vertices
- ✅ `g.E()` - Get all edges
- ✅ `.has(property, value)` - Filter by property
- ✅ `.values(properties...)` - Project specific properties
- ✅ `.out()` - Traverse outgoing edges
- ✅ `.in()` - Traverse incoming edges
- ✅ `.both()` - Traverse both directions

### Future Enhancements
- ⏳ Multi-hop traversals: `g.V().out().out()`
- ⏳ Edge labels: `g.V().out('knows')`
- ⏳ Aggregations: `.count()`, `.sum()`, `.mean()`
- ⏳ Path operations: `.path()`, `.simplePath()`
- ⏳ Subgraph operations
- ⏳ More complex filters and predicates

---

## Architecture

```
Gremlin Query
     ↓
GremlinToRelConverter (direct RelBuilder construction)
     ↓
Coral IR (RelNode)
     ↓
coral-spark / coral-trino (existing modules)
     ↓
Spark SQL / Trino SQL
```

### Benefits of Coral IR

Once in Coral IR, the query can be:
1. **Optimized** using relational algebra rules
2. **Converted** to any SQL dialect (Spark, Trino, Hive)
3. **Executed** on distributed SQL engines
4. **Analyzed** for cost and performance

### Scalability

This approach allows graph queries to leverage:
- Distributed SQL engines (Spark, Presto)
- Query optimization (predicate pushdown, join reordering)
- Existing data infrastructure
- SQL-based security and governance

---

## Performance Tips

- Partition tables by frequently queried properties
- Cache vertex/edge tables in Spark
- Collect table statistics for better query planning
- Use broadcast joins for small tables

---

## Limitations

**Prototype implementation:**
- Simple string-based parser (no complex predicates)
- No edge labels or edge properties
- No multi-hop traversals yet

## Future Enhancements

- **Proper Gremlin parser**: Replace string-based parsing with TinkerPop's Gremlin parser
- **Edge labels and properties**: Support typed relationships and edge attributes
- **Multi-hop traversals**: `g.V().out().out()`
- **Complex predicates**: `gt`, `lt`, `within`, `between`
- **Path tracking**: Preserve path information in traversals
- **Aggregations**: `count()`, `sum()`, `groupBy()`

## Dependencies

- Apache TinkerPop Gremlin 3.4.13 (Java 8)
- Apache Calcite (via coral-hive)
- Hive Metastore
