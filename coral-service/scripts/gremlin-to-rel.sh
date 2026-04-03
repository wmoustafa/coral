#!/bin/bash

# Gremlin to Coral IR (RelNode) Converter Script
# Calls the coral-service REST API endpoint
# Usage: ./gremlin-to-rel.sh [OPTIONS]

set -e

# Default coral-service URL
CORAL_SERVICE_URL="${CORAL_SERVICE_URL:-http://localhost:8080}"

# Default values
GREMLIN_QUERY=""
VERTEX_TABLE=""
EDGE_TABLE=""
VERTEX_ID_COLUMN=""
EDGE_SRC_COLUMN=""
EDGE_DST_COLUMN=""

# Function to print usage
print_usage() {
    cat << EOF
Gremlin to Coral IR (RelNode) Converter

Usage:
  ./gremlin-to-rel.sh [OPTIONS]

Required Options:
  -g, --gremlin <query>          Gremlin query string
  -v, --vertex-table <table>     Vertex table name (e.g., 'default.members')
  -e, --edge-table <table>       Edge table name (e.g., 'default.connections')
  --vertex-id-column <column>    Vertex ID column name
  --edge-src-column <column>     Edge source column name
  --edge-dst-column <column>     Edge destination column name

Other Options:
  -h, --help                     Show this help message

Example:
  ./gremlin-to-rel.sh \\
    --gremlin "g.V().has('name', 'Alice').out()" \\
    --vertex-table "default.members" \\
    --edge-table "default.connections" \\
    --vertex-id-column "member" \\
    --edge-src-column "src_member" \\
    --edge-dst-column "dst_member"

EOF
}

# Parse command-line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -g|--gremlin)
            GREMLIN_QUERY="$2"
            shift 2
            ;;
        -v|--vertex-table)
            VERTEX_TABLE="$2"
            shift 2
            ;;
        -e|--edge-table)
            EDGE_TABLE="$2"
            shift 2
            ;;
        --vertex-id-column)
            VERTEX_ID_COLUMN="$2"
            shift 2
            ;;
        --edge-src-column)
            EDGE_SRC_COLUMN="$2"
            shift 2
            ;;
        --edge-dst-column)
            EDGE_DST_COLUMN="$2"
            shift 2
            ;;
        -h|--help)
            print_usage
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            print_usage
            exit 1
            ;;
    esac
done

# Validate required arguments
if [[ -z "$GREMLIN_QUERY" || -z "$VERTEX_TABLE" || -z "$EDGE_TABLE" || \
      -z "$VERTEX_ID_COLUMN" || -z "$EDGE_SRC_COLUMN" || -z "$EDGE_DST_COLUMN" ]]; then
    echo "Error: Missing required arguments"
    echo ""
    print_usage
    exit 1
fi

# Create JSON request body
REQUEST_JSON=$(cat <<EOF
{
  "gremlinQuery": "$GREMLIN_QUERY",
  "vertexTable": "$VERTEX_TABLE",
  "edgeTable": "$EDGE_TABLE",
  "vertexIdColumn": "$VERTEX_ID_COLUMN",
  "edgeSrcColumn": "$EDGE_SRC_COLUMN",
  "edgeDstColumn": "$EDGE_DST_COLUMN"
}
EOF
)

echo "Converting Gremlin query to RelNode..."
echo "Calling coral-service at: $CORAL_SERVICE_URL/api/gremlin/convert"
echo ""

# Call the REST API
RESPONSE=$(curl -s -X POST \
    -H "Content-Type: application/json" \
    -d "$REQUEST_JSON" \
    "$CORAL_SERVICE_URL/api/gremlin/convert")

# Check if curl succeeded
if [[ $? -ne 0 ]]; then
    echo "Error: Failed to connect to coral-service at $CORAL_SERVICE_URL"
    echo "Make sure coral-service is running:"
    echo "  cd coral-service"
    echo "  ./gradlew bootRun"
    exit 1
fi

# Parse and display response
echo "$RESPONSE" | python3 -c "
import sys
import json

try:
    data = json.load(sys.stdin)
    if isinstance(data, dict) and 'gremlinQuery' in data:
        print('Gremlin Query:', data['gremlinQuery'])
        print()
        print('Coral IR (RelNode):')
        print(data['relNode'])
        print()
        print('Spark SQL:')
        print(data.get('sparkSql', 'N/A'))
    else:
        # Error response
        print('Error:', data if isinstance(data, str) else json.dumps(data, indent=2))
except json.JSONDecodeError:
    print('Error: Invalid JSON response')
    print(sys.stdin.read())
except Exception as e:
    print('Error:', str(e))
"
