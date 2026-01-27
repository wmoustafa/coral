# Test Data Generation REST Endpoint

## Overview
A new REST endpoint has been added to coral-service for generating test data from SQL queries.

## Endpoint Details

**URL:** `POST /api/testdata/generate`

**Request Body:**
```json
{
  "sourceLanguage": "hive",
  "query": "SELECT * FROM test.T WHERE name = 'John' AND age = 25",
  "numRows": 10
}
```

**Parameters:**
- `sourceLanguage`: The SQL dialect (supported: "hive", "trino", "spark")
- `query`: The SQL query to analyze
- `numRows`: Number of test data rows to generate (1-10000)

**Success Response (200 OK):**
```json
{
  "testData": [
    {
      "name": "John",
      "age": 25,
      "birthdate": "2020-05-15"
    },
    ...
  ],
  "numRows": 10,
  "query": "SELECT * FROM test.T WHERE name = 'John' AND age = 25"
}
```

**Error Responses:**
- `400 Bad Request`: Invalid input (empty query, invalid numRows, unsupported language)
- `500 Internal Server Error`: Query processing error

## Implementation Files

### Created Files:
1. **TestDataGenerationController.java** - REST controller with `/api/testdata/generate` endpoint
2. **TestDataRequestBody.java** - Request DTO
3. **TestDataResponseBody.java** - Response DTO
4. **TestDataGenerationUtils.java** - Utility class with domain transformers for test data generation

### Modified Files:
1. **build.gradle** - Added `coral-data-generation` dependency
2. **.gitignore** - Added patterns to ignore temporary metastore directories

## How It Works

1. **Query Parsing**: Converts SQL query to RelNode using appropriate converter (Hive/Trino/Spark)
2. **Domain Inference**: Analyzes predicates to extract constraints on columns
3. **Data Generation**: 
   - For columns with predicates: Generates data satisfying the constraints
   - For other columns: Generates random data based on SQL type
4. **Response**: Returns generated test data as JSON

## CLI Command

A command-line interface script is provided for easy interaction with the test data generation endpoint.

### Usage

```bash
./coral-service/generate-testdata.sh [OPTIONS]

OPTIONS:
    -q, --query QUERY          SQL query to analyze (required)
    -l, --language LANGUAGE    Source SQL dialect (hive, trino, spark) [default: hive]
    -n, --num-rows NUM         Number of rows to generate (1-10000) [default: 10]
    -h, --host HOST            Service host URL [default: http://localhost:8080]
    -f, --format FORMAT        Output format (json, pretty, table) [default: table]
    --help                     Display help message
```

### CLI Examples

```bash
# Generate 5 rows with a simple predicate (default table format)
./coral-service/generate-testdata.sh -q "SELECT * FROM users WHERE name = 'John'" -n 5

# Generate 10 rows with pretty JSON output
./coral-service/generate-testdata.sh -q "SELECT * FROM users WHERE age = 30" -n 10 -f pretty

# Generate data with JSON format
./coral-service/generate-testdata.sh -q "SELECT * FROM users" -n 5 -f json

# Use Trino dialect
./coral-service/generate-testdata.sh -q "SELECT * FROM catalog.schema.table" -l trino -n 3
```

## REST API Usage

### Example 1: Simple Equality Predicate
```bash
curl -X POST http://localhost:8080/api/testdata/generate \
  -H "Content-Type: application/json" \
  -d '{
    "sourceLanguage": "hive",
    "query": "SELECT * FROM users WHERE name = '\''Alice'\''",
    "numRows": 5
  }'
```

### Example 2: Multiple Predicates
```bash
curl -X POST http://localhost:8080/api/testdata/generate \
  -H "Content-Type: application/json" \
  -d '{
    "sourceLanguage": "hive",
    "query": "SELECT * FROM users WHERE age = 30 AND city = '\''NYC'\''",
    "numRows": 10
  }'
```

### Example 3: Complex Predicate (SUBSTRING)
```bash
curl -X POST http://localhost:8080/api/testdata/generate \
  -H "Content-Type: application/json" \
  -d '{
    "sourceLanguage": "hive",
    "query": "SELECT * FROM users WHERE SUBSTRING(name, 1, 4) = '\''2000'\''",
    "numRows": 3
  }'
```

### Example 4: No Predicates (Random Data)
```bash
curl -X POST http://localhost:8080/api/testdata/generate \
  -H "Content-Type: application/json" \
  -d '{
    "sourceLanguage": "hive",
    "query": "SELECT * FROM users",
    "numRows": 20
  }'
```

## Testing

The endpoint has been tested with curl commands and verified to work correctly:

### Test Results:
✅ **Simple equality predicates** - Generates data with `name = 'John'`, random values for other columns  
✅ **Multiple predicates (AND conditions)** - Generates data with `name = 'Alice' AND age = 30`, random city values  
✅ **No predicates (random data)** - Generates completely random data for all columns  
✅ **Input validation** - Rejects invalid source languages with error message  
✅ **Input validation** - Rejects invalid numRows (≤0 or >10000) with error message

### Running the Service:
To test the endpoint locally, start the service with the local metastore profile:
```bash
export JAVA_HOME=/path/to/jdk1.8
./gradlew :coral-service:bootRun --args='--spring.profiles.active=localMetastore'
```

Then create a test table:
```bash
curl -X POST http://localhost:8080/api/catalog-ops/execute \
  -H "Content-Type: application/json" \
  -d "CREATE DATABASE test"

curl -X POST http://localhost:8080/api/catalog-ops/execute \
  -H "Content-Type: application/json" \
  -d "CREATE TABLE test.users (name STRING, age INT, city STRING)"
```

## Notes

- The endpoint uses the existing `GenerateTestDataProgram` from coral-data-generation
- Supports the same SQL dialects as the translation endpoints (Hive, Trino, Spark)
- Maximum 10,000 rows per request to prevent resource exhaustion
- All generated data respects the constraints defined in the SQL query predicates
