/**
 * Copyright (c) 2018-present, A2 Rešitve d.o.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package solutions.a2.oracle.iceberg;

import org.apache.commons.lang3.StringUtils;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OracleToIcebergTypeMapper {

    private static final Map<String, String> exactOverrides = new HashMap<>();
    private static final List<PatternOverride> patternOverrides = new ArrayList<>();
    private static String defaultNumberFallback = "decimal(38,10)";

    // Internal fields for the final result
    private final String columnName;
    private final int originalJdbcType;
    private final int originalPrecision;
    private final int originalScale;

    private int mappedType;
    private int finalPrecision;
    private int finalScale;
    private Type icebergType;

    private static class PatternOverride {
        String patternColumnName; // includes '%'
        String oracleTypeName;
        String icebergTypeSpec;

        PatternOverride(String patternColumnName, String oracleTypeName, String icebergTypeSpec) {
            this.patternColumnName = patternColumnName;
            this.oracleTypeName = oracleTypeName;
            this.icebergTypeSpec = icebergTypeSpec;
        }
    }

    /**
     * Configure overrides from a single string.
     * Example: "SUPPLIER_ID:NUMBER=long; %_ID:NUMBER=long; PRODUCT_%:NUMBER=decimal(20,6)"
     */
    public static void configureOverrides(String allOverrides) {
        exactOverrides.clear();
        patternOverrides.clear();

        if (StringUtils.isBlank(allOverrides)) {
            return;
        }

        String[] overrideArray = allOverrides.split(";");
        for (String overrideSpec : overrideArray) {
            overrideSpec = overrideSpec.trim();
            if (overrideSpec.isEmpty()) {
                continue;
            }
            configureSingleOverride(overrideSpec);
        }
    }

    /**
     * Configure a single override.
     * Format: "COLUMN_OR_PATTERN:ORACLE_TYPE=ICEBERG_TYPE_SPEC"
     */
    private static void configureSingleOverride(String overrideSpec) {
        String[] parts = overrideSpec.split("=");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid override specification: " + overrideSpec);
        }

        String leftPart = parts[0].trim();  // e.g. "SUPPLIER_ID:NUMBER" or "%_ID:NUMBER"
        String icebergTypeSpec = parts[1].trim();

        String[] leftParts = leftPart.split(":");
        if (leftParts.length != 2) {
            throw new IllegalArgumentException("Invalid override format before '='. " +
                    "Expected 'COLUMN_OR_PATTERN:ORACLE_TYPE'. Got: " + leftPart);
        }

        String columnOrPattern = leftParts[0].trim();
        String oracleType = leftParts[1].trim().toUpperCase();

        if (columnOrPattern.contains("%")) {
            patternOverrides.add(new PatternOverride(columnOrPattern, oracleType, icebergTypeSpec));
        } else {
            exactOverrides.put(columnOrPattern.toUpperCase() + ":" + oracleType, icebergTypeSpec);
        }
    }

    /**
     * Configure default fallback for NUMBER when unknown precision/scale.
     */
    public static void configureDefaultNumberFallback(String fallbackSpec) {
        defaultNumberFallback = fallbackSpec;
    }

    /**
     * Constructor that takes column metadata as input.
     */
    public OracleToIcebergTypeMapper(String columnName, int jdbcType, int precision, int scale) {
        this.columnName = columnName;
        this.originalJdbcType = jdbcType;
        this.originalPrecision = precision;
        this.originalScale = scale;
        determineFinalMapping();
    }

    private void determineFinalMapping() {
        // Determine the Oracle type name from jdbcType for override matching
        String oracleTypeName = guessOracleTypeName(originalJdbcType);

        // Try exact override first
        String exactKey = columnName.toUpperCase() + ":" + oracleTypeName;
        String overrideTypeSpec = exactOverrides.get(exactKey);

        if (overrideTypeSpec == null) {
            // Try pattern overrides
            for (PatternOverride po : patternOverrides) {
                if (po.oracleTypeName.equalsIgnoreCase(oracleTypeName)
                        && matchesPattern(columnName, po.patternColumnName)) {
                    overrideTypeSpec = po.icebergTypeSpec;
                    break;
                }
            }
        }

        if (overrideTypeSpec != null) {
            // Apply override logic to mappedType, precision, scale
            applyOverride(overrideTypeSpec);
        } else {
            // No override, use default logic
            applyDefaultLogic(oracleTypeName);
        }

        // Determine icebergType from final mappedType, finalPrecision, finalScale
        this.icebergType = toIcebergType(this.mappedType, this.finalPrecision, this.finalScale);
    }

    private void applyOverride(String overrideSpec) {
        // Override affects mappedType, precision, scale
        overrideSpec = overrideSpec.toLowerCase().trim();

        if (overrideSpec.startsWith("decimal(")) {
            // decimal(p,s)
            String inside = overrideSpec.substring("decimal(".length(), overrideSpec.length() - 1);
            String[] parts = inside.split(",");
            int p = Integer.parseInt(parts[0].trim());
            int s = Integer.parseInt(parts[1].trim());
            this.mappedType = java.sql.Types.NUMERIC;
            this.finalPrecision = p;
            this.finalScale = s;
        } else if (overrideSpec.equals("long")) {
            this.mappedType = java.sql.Types.BIGINT;
            this.finalPrecision = Integer.MIN_VALUE;
            this.finalScale = Integer.MIN_VALUE;
        } else if (overrideSpec.equals("integer")) {
            this.mappedType = java.sql.Types.INTEGER;
            this.finalPrecision = Integer.MIN_VALUE;
            this.finalScale = Integer.MIN_VALUE;
        } else if (overrideSpec.equals("float")) {
            this.mappedType = java.sql.Types.FLOAT;
            this.finalPrecision = Integer.MIN_VALUE;
            this.finalScale = Integer.MIN_VALUE;
        } else if (overrideSpec.equals("double")) {
            this.mappedType = java.sql.Types.DOUBLE;
            this.finalPrecision = Integer.MIN_VALUE;
            this.finalScale = Integer.MIN_VALUE;
        } else if (overrideSpec.equals("string")) {
            this.mappedType = java.sql.Types.VARCHAR;
            this.finalPrecision = Integer.MIN_VALUE;
            this.finalScale = Integer.MIN_VALUE;
        } else if (overrideSpec.equals("boolean")) {
            this.mappedType = java.sql.Types.BOOLEAN;
            this.finalPrecision = Integer.MIN_VALUE;
            this.finalScale = Integer.MIN_VALUE;
        } else if (overrideSpec.equals("binary")) {
            this.mappedType = java.sql.Types.BINARY;
            this.finalPrecision = Integer.MIN_VALUE;
            this.finalScale = Integer.MIN_VALUE;
        } else if (overrideSpec.equals("timestamp")) {
            this.mappedType = java.sql.Types.TIMESTAMP;
            this.finalPrecision = Integer.MIN_VALUE;
            this.finalScale = Integer.MIN_VALUE;
        } else if (overrideSpec.equals("timestamptz")) {
            this.mappedType = java.sql.Types.TIMESTAMP_WITH_TIMEZONE;
            this.finalPrecision = Integer.MIN_VALUE;
            this.finalScale = Integer.MIN_VALUE;
        } else if (overrideSpec.equals("date")) {
            this.mappedType = java.sql.Types.DATE;
            this.finalPrecision = Integer.MIN_VALUE;
            this.finalScale = Integer.MIN_VALUE;
        } else if (overrideSpec.equals("time")) {
            this.mappedType = java.sql.Types.TIME;
            this.finalPrecision = Integer.MIN_VALUE;
            this.finalScale = Integer.MIN_VALUE;
        } else {
            throw new IllegalArgumentException("Unsupported override type spec: " + overrideSpec);
        }
    }

    private void applyDefaultLogic(String oracleTypeName) {
        switch (oracleTypeName) {
            case "NUMBER":
            case "FLOAT":
            case "BINARY_FLOAT":
            case "BINARY_DOUBLE":
                if (originalPrecision > 0 && originalScale >= 0) {
                    this.mappedType = java.sql.Types.NUMERIC;
                    this.finalPrecision = originalPrecision <= 0 ? 38 : originalPrecision;
                    this.finalScale = originalScale < 0 ? 10 : originalScale;
                } else {
                    // Parse defaultNumberFallback
                    applyOverride(defaultNumberFallback);
                }
                break;

            case "VARCHAR2":
            case "CHAR":
            case "NCHAR":
            case "NVARCHAR2":
            case "CLOB":
            case "NCLOB":
                this.mappedType = java.sql.Types.VARCHAR;
                this.finalPrecision = Integer.MIN_VALUE;
                this.finalScale = Integer.MIN_VALUE;
                break;

            case "DATE":
                this.mappedType = java.sql.Types.TIMESTAMP;
                this.finalPrecision = Integer.MIN_VALUE;
                this.finalScale = Integer.MIN_VALUE;
                break;

            case "TIMESTAMP":
                this.mappedType = java.sql.Types.TIMESTAMP;
                this.finalPrecision = Integer.MIN_VALUE;
                this.finalScale = Integer.MIN_VALUE;
                break;

            case "TIMESTAMP WITH TIME ZONE":
            case "TIMESTAMP WITH LOCAL TIME ZONE":
                this.mappedType = java.sql.Types.TIMESTAMP_WITH_TIMEZONE;
                this.finalPrecision = Integer.MIN_VALUE;
                this.finalScale = Integer.MIN_VALUE;
                break;

            case "RAW":
            case "BLOB":
            case "BFILE":
                this.mappedType = java.sql.Types.BINARY;
                this.finalPrecision = Integer.MIN_VALUE;
                this.finalScale = Integer.MIN_VALUE;
                break;

            default:
                this.mappedType = java.sql.Types.VARCHAR;
                this.finalPrecision = Integer.MIN_VALUE;
                this.finalScale = Integer.MIN_VALUE;
                break;
        }
    }

    private Type toIcebergType(int jdbcType, int p, int s) {
        switch (jdbcType) {
            case java.sql.Types.BOOLEAN:
                return Types.BooleanType.get();
            case java.sql.Types.INTEGER:
                return Types.IntegerType.get();
            case java.sql.Types.BIGINT:
                return Types.LongType.get();
            case java.sql.Types.FLOAT:
                return Types.FloatType.get();
            case java.sql.Types.DOUBLE:
                return Types.DoubleType.get();
            case java.sql.Types.VARCHAR:
                return Types.StringType.get();
            case java.sql.Types.TIMESTAMP:
                return Types.TimestampType.withoutZone();
            case java.sql.Types.TIMESTAMP_WITH_TIMEZONE:
                return Types.TimestampType.withZone();
            case java.sql.Types.DATE:
                return Types.DateType.get();
            case java.sql.Types.TIME:
                return Types.TimeType.get();
            case java.sql.Types.BINARY:
                return Types.BinaryType.get();
            case java.sql.Types.NUMERIC:
                int decPrecision = (p <= 0) ? 38 : p;
                int decScale = (s < 0) ? 10 : s;
                return Types.DecimalType.of(decPrecision, decScale);
            default:
                return Types.StringType.get(); // fallback
        }
    }

    private static boolean matchesPattern(String columnName, String pattern) {
        columnName = columnName.toUpperCase();
        pattern = pattern.toUpperCase();

        if (pattern.startsWith("%") && pattern.endsWith("%")) {
            throw new IllegalArgumentException("Only one wildcard (%) at start or end is supported: " + pattern);
        }

        if (pattern.startsWith("%")) {
            String endPattern = pattern.substring(1);
            return columnName.endsWith(endPattern);
        } else if (pattern.endsWith("%")) {
            String startPattern = pattern.substring(0, pattern.length() - 1);
            return columnName.startsWith(startPattern);
        } else {
            return columnName.equals(pattern);
        }
    }

    private static String guessOracleTypeName(int jdbcType) {
        // Map known JDBC types to Oracle type names for override matching
        switch (jdbcType) {
            case java.sql.Types.NUMERIC:
                return "NUMBER";
            case java.sql.Types.VARCHAR:
                return "VARCHAR2";
            case java.sql.Types.TIMESTAMP:
                return "TIMESTAMP";
            case java.sql.Types.TIMESTAMP_WITH_TIMEZONE:
                return "TIMESTAMP WITH TIME ZONE";
            case java.sql.Types.BINARY:
                return "RAW";
            case java.sql.Types.BIGINT:
            case java.sql.Types.INTEGER:
            case java.sql.Types.BOOLEAN:
            case java.sql.Types.FLOAT:
            case java.sql.Types.DOUBLE:
                return "NUMBER";
            default:
                return "OTHER";
        }
    }

    public int getMappedType() {
        return mappedType;
    }

    public Type getType() {
        return icebergType;
    }

    public int getPrecision() {
        return finalPrecision;
    }

    public int getScale() {
        return finalScale;
    }
}