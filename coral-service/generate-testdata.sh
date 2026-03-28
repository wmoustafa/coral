#!/bin/bash

# Coral Test Data Generation CLI
# This script calls the REST endpoint to generate test data from SQL queries

set -e

# Default values
HOST="http://localhost:8080"
SOURCE_LANGUAGE="hive"
NUM_ROWS=10
OUTPUT_FORMAT="table"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to display usage
usage() {
    cat << EOF
Usage: $0 [OPTIONS]

Generate test data from SQL queries using Coral's test data generation endpoint.

OPTIONS:
    -q, --query QUERY          SQL query to analyze (required)
    -l, --language LANGUAGE    Source SQL dialect (hive, trino, spark) [default: hive]
    -n, --num-rows NUM         Number of rows to generate (1-10000) [default: 10]
    -h, --host HOST            Service host URL [default: http://localhost:8080]
    -f, --format FORMAT        Output format (json, pretty, table) [default: table]
    --help                     Display this help message

EXAMPLES:
    # Generate 5 rows with a simple predicate
    $0 -q "SELECT * FROM users WHERE name = 'John'" -n 5

    # Generate 10 rows with multiple predicates
    $0 -q "SELECT * FROM users WHERE age = 30 AND city = 'NYC'" -n 10 -f pretty

    # Use Trino dialect
    $0 -q "SELECT * FROM catalog.schema.table" -l trino -n 3

    # Generate random data (no predicates)
    $0 -q "SELECT * FROM users" -n 20 -f table

EOF
    exit 1
}

# Function to format JSON output as a table
format_table() {
    local json_data="$1"
    
    # Check if jq is available
    if ! command -v jq &> /dev/null; then
        echo -e "${YELLOW}Warning: jq not found. Install jq for table formatting. Falling back to JSON.${NC}" >&2
        echo "$json_data"
        return
    fi
    
    # Check if data exists
    local has_data=$(echo "$json_data" | jq -r '.testData | length' 2>/dev/null)
    if [ -z "$has_data" ] || [ "$has_data" -eq 0 ]; then
        echo -e "${RED}Error: No data returned${NC}" >&2
        return 1
    fi
    
    # Create temporary file for table data
    local temp_file=$(mktemp)
    
    # Get column names in sorted order for consistency
    local columns=$(echo "$json_data" | jq -r '.testData[0] | keys | sort | @tsv' 2>/dev/null)
    
    # Print header with pipe separators
    echo "$columns" | awk '{for(i=1;i<=NF;i++) {printf "%s", $i; if(i<NF) printf "|"} printf "\n"}' > "$temp_file"
    
    # Print rows with values in same order as headers with pipe separators
    echo "$json_data" | jq -r '.testData[] | [to_entries | sort_by(.key) | .[].value] | join("|")' >> "$temp_file"
    
    # Calculate max width for each column and format manually
    awk -F'|' '
    {
        # Store all rows
        for(i=1; i<=NF; i++) {
            rows[NR,i] = $i
            len = length($i)
            if(len > max_width[i]) max_width[i] = len
        }
        num_cols = NF
        num_rows = NR
    }
    END {
        # Print header
        for(i=1; i<=num_cols; i++) {
            printf "%-*s", max_width[i], rows[1,i]
            if(i < num_cols) printf " | "
        }
        printf "\n"
        
        # Print separator
        for(i=1; i<=num_cols; i++) {
            for(j=0; j<max_width[i]; j++) printf "-"
            if(i < num_cols) printf " | "
        }
        printf "\n"
        
        # Print data rows
        for(r=2; r<=num_rows; r++) {
            for(i=1; i<=num_cols; i++) {
                printf "%-*s", max_width[i], rows[r,i]
                if(i < num_cols) printf " | "
            }
            printf "\n"
        }
    }
    ' "$temp_file"
    
    # Clean up
    rm -f "$temp_file"
}

# Parse command line arguments
QUERY=""
while [[ $# -gt 0 ]]; do
    case $1 in
        -q|--query)
            QUERY="$2"
            shift 2
            ;;
        -l|--language)
            SOURCE_LANGUAGE="$2"
            shift 2
            ;;
        -n|--num-rows)
            NUM_ROWS="$2"
            shift 2
            ;;
        -h|--host)
            HOST="$2"
            shift 2
            ;;
        -f|--format)
            OUTPUT_FORMAT="$2"
            shift 2
            ;;
        --help)
            usage
            ;;
        *)
            echo -e "${RED}Error: Unknown option $1${NC}" >&2
            usage
            ;;
    esac
done

# Validate required parameters
if [ -z "$QUERY" ]; then
    echo -e "${RED}Error: Query is required${NC}" >&2
    usage
fi

# Validate num rows
if ! [[ "$NUM_ROWS" =~ ^[0-9]+$ ]] || [ "$NUM_ROWS" -lt 1 ] || [ "$NUM_ROWS" -gt 10000 ]; then
    echo -e "${RED}Error: num-rows must be between 1 and 10000${NC}" >&2
    exit 1
fi

# Validate source language
if [[ ! "$SOURCE_LANGUAGE" =~ ^(hive|trino|spark)$ ]]; then
    echo -e "${RED}Error: language must be one of: hive, trino, spark${NC}" >&2
    exit 1
fi

# Build JSON request
REQUEST_JSON=$(cat <<EOF
{
  "sourceLanguage": "$SOURCE_LANGUAGE",
  "query": "$QUERY",
  "numRows": $NUM_ROWS
}
EOF
)

# Make the API call
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "${HOST}/api/testdata/generate" \
    -H "Content-Type: application/json" \
    -d "$REQUEST_JSON")

# Extract HTTP status code and response body
HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
RESPONSE_BODY=$(echo "$RESPONSE" | sed '$d')

# Check HTTP status
if [ "$HTTP_CODE" != "200" ]; then
    echo -e "${RED}Error: Request failed with status $HTTP_CODE${NC}" >&2
    echo "$RESPONSE_BODY" >&2
    exit 1
fi

# Format output based on requested format
case $OUTPUT_FORMAT in
    json)
        echo "$RESPONSE_BODY"
        ;;
    pretty)
        if command -v jq &> /dev/null; then
            echo "$RESPONSE_BODY" | jq '.'
        else
            echo -e "${YELLOW}Warning: jq not found. Install jq for pretty formatting.${NC}" >&2
            echo "$RESPONSE_BODY"
        fi
        ;;
    table)
        echo -e "${GREEN}Query:${NC} $(echo "$RESPONSE_BODY" | jq -r '.query')"
        echo -e "${GREEN}Rows:${NC} $(echo "$RESPONSE_BODY" | jq -r '.numRows')"
        echo ""
        format_table "$RESPONSE_BODY"
        ;;
    *)
        echo -e "${RED}Error: Invalid format. Use json, pretty, or table${NC}" >&2
        exit 1
        ;;
esac
