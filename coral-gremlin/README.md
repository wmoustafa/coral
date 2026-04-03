# Coral-Gremlin: Gremlin to Coral IR Conversion

## Overview

**Coral-Gremlin** converts Apache TinkerPop Gremlin graph traversal queries to Coral IR (Intermediate Representation), enabling Gremlin queries to execute on relational engines like Spark.

**Key Features:**
- **Fully generic** - Works with any vertex and edge tables you specify
- **Edge label filtering** - Filter edges by type (e.g., "friend", "colleague")
- **Multi-hop traversals** - Chain multiple hops (e.g., friends of friends)
- **Rich predicates** - Comparison operators: gt(), lt(), gte(), lte(), neq()
- **Deduplication** - Remove duplicate results with .dedup()
- **Ordering** - Sort results with .order().by()
- **Projections** - Select specific fields with .values() or .valueMap()

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
    "dst_vertex",            // Edge destination column
    "edge_type"              // Edge label column (optional, default: "relation")
);

// Convert Gremlin to Coral IR
RelNode relNode = converter.convertGremlin("g.V().has('name', 'Alice').outE('friend').inV()");

// Convert to Spark SQL
CoralSpark coralSpark = CoralSpark.create(relNode, hiveMetastoreClient);
String sparkSql = coralSpark.getSparkSql();
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

-- Edge table (must have source, destination, and optional label columns)
CREATE TABLE your_db.edge_table (
  src_vertex STRING,  -- Source vertex ID (you specify the name)
  dst_vertex STRING,  -- Destination vertex ID (you specify the name)
  edge_type STRING,   -- Edge label/type (e.g., "friend", "colleague")
  edge_property STRING  -- Optional edge properties
);
```

**Key points:**
- Vertex table needs an ID column (any name you choose)
- Edge table needs source and destination columns (any names you choose)
- Edge table should have a label/type column for edge filtering (default: "relation")
- All other columns are optional and can be used in filters/projections

## How It Works

**Architecture: Gremlin → Coral IR → Spark SQL**

```
Gremlin Query → GremlinToRelConverter → Coral IR (RelNode) → CoralSpark → Spark SQL
```

1. **Parse Gremlin Query**: Simple string-based parsing (prototype)
2. **Build RelNode Directly**: Uses Calcite's RelBuilder to construct relational algebra
3. **Convert to Spark SQL**: Uses coral-spark to generate executable Spark SQL

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

### Basic Queries
```groovy
g.V()                           // SELECT * FROM vertex_table
g.V().has('name', 'Alice')      // SELECT * FROM vertex_table WHERE name = 'Alice'
g.V().values('name', 'age')     // SELECT name, age FROM vertex_table
g.E()                           // SELECT * FROM edge_table
```

### Comparison Operators
```groovy
g.V().has('age', gt(25))        // WHERE age > 25
g.V().has('age', lt(30))        // WHERE age < 30
g.V().has('age', gte(21))       // WHERE age >= 21
g.V().has('age', lte(65))       // WHERE age <= 65
g.V().has('name', neq('John'))  // WHERE name <> 'John'
```

### Edge Label Filtering
```groovy
// Find Alice's friends (filter by edge type)
g.V().has('name', 'Alice').outE('friend').inV()
// → Filters edges WHERE edge_type = 'friend'

// Find colleagues
g.V().has('name', 'Bob').outE('colleague').inV()
```

### Multi-Hop Traversals
```groovy
// Friends of friends (2-hop)
g.V().has('name', 'Alice').outE('friend').inV().outE('friend').inV()

// Mixed relationships (friend's colleagues)
g.V().has('name', 'Alice').outE('friend').inV().outE('colleague').inV()
```

### Advanced Operations
```groovy
// Deduplication
g.V().out().dedup()

// Ordering
g.V().order().by('age', decr)          // Descending
g.V().order().by('name', incr)         // Ascending

// Projection with valueMap
g.V().valueMap('name', 'age', 'city')
```

### Complex Query Example
```groovy
// Find friends of friends over 25, ordered by age
g.V().has('name', 'Tanvi')
  .outE('friend').inV()
  .outE('friend').inV()
  .dedup()
  .has('age', gt(25))
  .order().by('age', decr)
  .valueMap('name', 'age', 'city')
```

## Spark SQL Integration

The REST API automatically converts Gremlin queries to both Coral IR and Spark SQL in a single call.

**Complete Pipeline:**
```
Gremlin Query → GremlinToRelConverter → Coral IR → CoralSpark → Spark SQL
```

**Programmatic Example:**
```java
// 1. Convert Gremlin to Coral IR
GremlinToRelConverter converter = new GremlinToRelConverter(
    mscAdapter, "default.members", "default.connections",
    "member", "src_member", "dst_member"
);
RelNode coralIR = converter.convertGremlin("g.V().has('name', 'Alice').out()");

// 2. Convert Coral IR to Spark SQL using CoralSpark
CoralSpark coralSpark = CoralSpark.create(coralIR, hiveMetastoreClient);
String sparkSQL = coralSpark.getSparkSql();

// 3. Execute on Spark
spark.sql(sparkSQL).show();
```

**The REST API does both steps automatically** - you get both RelNode and Spark SQL in the response!

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

Converts a Gremlin query to both Coral IR (RelNode) and Spark SQL.

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
  "gremlinQuery": "g.V().out()",
  "relNode": "LogicalJoin(condition=[=($5, $6)], joinType=[inner])\n  LogicalJoin(condition=[=($0, $4)], joinType=[inner])\n    LogicalTableScan(table=[[hive, default, members]])\n    LogicalTableScan(table=[[hive, default, connections]])\n  LogicalTableScan(table=[[hive, default, members]])\n",
  "sparkSql": "SELECT *\nFROM default.members members\nINNER JOIN default.connections connections ON members.member = connections.src_member\nINNER JOIN default.members members0 ON connections.dst_member = members0.member",
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

Use the provided shell script that calls the REST API and displays both RelNode and Spark SQL:

**Basic Example:**
```bash
cd coral-service/scripts
./gremlin-to-rel.sh \
  --gremlin "g.V().has('name', 'Alice')" \
  --vertex-table "default.people" \
  --edge-table "default.relationships" \
  --vertex-id-column "person_id" \
  --edge-src-column "src_person" \
  --edge-dst-column "dst_person"
```

**Edge Label Filtering:**
```bash
./gremlin-to-rel.sh \
  --gremlin "g.V().has('name', 'Alice').outE('friend').inV()" \
  --vertex-table "default.people" \
  --edge-table "default.relationships" \
  --vertex-id-column "person_id" \
  --edge-src-column "src_person" \
  --edge-dst-column "dst_person"
```

**Complex Query with All Features:**
```bash
./gremlin-to-rel.sh \
  --gremlin "g.V().has('name', 'Tanvi').outE('friend').inV().outE('friend').inV().dedup().has('age', gt(25)).order().by('age', decr).valueMap('name', 'age', 'city')" \
  --vertex-table "default.people" \
  --edge-table "default.relationships" \
  --vertex-id-column "person_id" \
  --edge-src-column "src_person" \
  --edge-dst-column "dst_person"
```

**Output Example:**
```
Gremlin Query: g.V().has('name', 'Tanvi').outE('friend').inV()

Coral IR (RelNode):
LogicalJoin(condition=[=($5, $8)], joinType=[inner])
  LogicalFilter(condition=[=($6, 'friend')])
    LogicalJoin(condition=[=($0, $4)], joinType=[inner])
      LogicalFilter(condition=[=($1, 'Tanvi')])
        LogicalTableScan(table=[[hive, default, people]])
      LogicalTableScan(table=[[hive, default, relationships]])
  LogicalTableScan(table=[[hive, default, people]])

Spark SQL:
SELECT *
FROM (SELECT *
FROM (SELECT *
FROM default.people people
WHERE people.name = 'Tanvi') t
INNER JOIN default.relationships relationships ON t.person_id = relationships.src_person
WHERE relationships.relation = 'friend') t0
INNER JOIN default.people people0 ON t0.dst_person = people0.person_id
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

// Get RelNode string representation
String relNodeString = RelOptUtil.toString(relNode);
System.out.println("RelNode: " + relNodeString);

// Convert RelNode to Spark SQL
CoralSpark coralSpark = CoralSpark.create(relNode, metastoreClient);
String sparkSql = coralSpark.getSparkSql();
System.out.println("Spark SQL: " + sparkSql);
```

## Testing

Run tests:
```bash
./gradlew :coral-gremlin:test
```

Test coverage: **20 passing unit tests** covering all features
- ✅ Basic queries: `g.V()`, `g.E()`
- ✅ Filters with equality: `g.V().has('name', 'John')`
- ✅ Comparison operators: `gt()`, `lt()`, `gte()`, `lte()`, `neq()`
- ✅ Edge label filtering: `.outE('friend')`, `.inE('colleague')`
- ✅ Multi-hop traversals: `.outE().inV().outE().inV()`
- ✅ Deduplication: `.dedup()`
- ✅ Ordering: `.order().by('age', decr)`
- ✅ Projections: `.values()`, `.valueMap()`
- ✅ Complex queries combining all features

## Supported Operations

### Currently Implemented ✅

**Basic Operations:**
- `g.V()` - Get all vertices
- `g.E()` - Get all edges

**Filters:**
- `.has(property, value)` - Equality filter
- `.has(property, gt(value))` - Greater than
- `.has(property, lt(value))` - Less than
- `.has(property, gte(value))` - Greater than or equal
- `.has(property, lte(value))` - Less than or equal
- `.has(property, neq(value))` - Not equal

**Traversals:**
- `.out()` - Traverse outgoing edges (all types)
- `.in()` - Traverse incoming edges (all types)
- `.outE(label)` - Traverse outgoing edges of specific type
- `.inE(label)` - Traverse incoming edges of specific type
- `.outE().inV()` - Explicit edge-to-vertex traversal
- `.inE().outV()` - Explicit edge-to-vertex traversal
- Multi-hop: Chain multiple traversal steps

**Projections:**
- `.values(properties...)` - Project specific properties
- `.valueMap(properties...)` - Project as map

**Modifiers:**
- `.dedup()` - Remove duplicates
- `.order().by(property, decr)` - Sort descending
- `.order().by(property, incr)` - Sort ascending

### Future Enhancements
- ⏳ `.both()` - Bidirectional traversal
- ⏳ Aggregations: `.count()`, `.sum()`, `.mean()`
- ⏳ Path operations: `.path()`, `.simplePath()`
- ⏳ Subgraph operations
- ⏳ More predicates: `within()`, `between()`, `inside()`

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
