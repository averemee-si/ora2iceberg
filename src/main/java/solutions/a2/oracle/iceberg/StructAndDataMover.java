/**
 * Copyright (c) 2018-present, A2 Rešitve d.o.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See
 * the License for the specific language governing permissions and limitations under the License.
 */

package solutions.a2.oracle.iceberg;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericAppenderFactory;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.io.OutputFileFactory;
import org.apache.iceberg.io.PartitionedFanoutWriter;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.BaseMetastoreCatalog;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import oracle.jdbc.OracleTypes;

/**
 * 
 * @author <a href="mailto:averemee@a2.solutions">Aleksei Veremeev</a>
 */
public class StructAndDataMover {

	private static final Logger LOGGER = LoggerFactory.getLogger(StructAndDataMover.class);

	final Connection connection;
	final boolean isTableOrView;
	final String sourceSchema;
	final String sourceObject;
	final Map<String, int[]> columnsMap;
	final Table table;
	final long targetFileSize;

	StructAndDataMover(
			final DatabaseMetaData dbMetaData,
			final String sourceSchema,
			final String sourceObject,
			final boolean isTableOrView,
			final BaseMetastoreCatalog catalog,
			final TableIdentifier icebergTable,
			final Set<String> idColumnNames,
			//TODO
			final String[] partitionDefs,
			final long targetFileSize) throws SQLException {
		connection = dbMetaData.getConnection();
		columnsMap = new HashMap<>();
		this.isTableOrView = isTableOrView;
		this.sourceSchema = sourceSchema;
		this.sourceObject = sourceObject;
		this.targetFileSize = targetFileSize;

		final String sourceCatalog;
		if (isTableOrView) {
			final ResultSet tables = dbMetaData.getTables(null, sourceSchema, sourceObject, null);
			if (tables.next()) {
				sourceCatalog = tables.getString("TABLE_CAT");
				LOGGER.info("Working with {} {}.{}",
						tables.getString("TABLE_TYPE"), sourceSchema, sourceObject);
			} else {
				LOGGER.error(
						"\n=====================\n" +
						"Unable to access {}.{} !" +
						"\n=====================\n",
						sourceSchema, sourceObject);
				throw new SQLException();
			}
			tables.close();

			final List<Types.NestedField> allColumns = new ArrayList<>();
			final Set<Integer> pkIds = new HashSet<>();
			int columnId = 0;

			final boolean idColumnsPresent = idColumnNames != null && !idColumnNames.isEmpty();
			final ResultSet columns = dbMetaData.getColumns(sourceCatalog, sourceSchema, sourceObject, "%");
			while (columns.next()) {
				final String columnName = columns.getString("COLUMN_NAME");
				final int jdbcType = columns.getInt("DATA_TYPE");
				final boolean nullable = StringUtils.equals("YES", columns.getString("IS_NULLABLE"));
				final int precision = columns.getInt("COLUMN_SIZE"); 
				final int scale = columns.getInt("DECIMAL_DIGITS");
				boolean addColumn = false;
				final Type type;
				final int mappedType;
				switch (jdbcType) {
				case java.sql.Types.BOOLEAN:
					type = Types.BooleanType.get();
					mappedType = java.sql.Types.BOOLEAN;
					addColumn = true;
					break;
				case java.sql.Types.NUMERIC:
					if (scale == 0 && precision < 10) {
						mappedType = java.sql.Types.INTEGER;
						type = Types.IntegerType.get();
					} else if (scale == 0 && precision < 19) {
						mappedType = java.sql.Types.BIGINT;
						type = Types.LongType.get();
					} else {
						mappedType = java.sql.Types.NUMERIC;
						//TODO
						type = Types.DecimalType.of(
								precision <= 0 ? 38 : precision,
								scale < 0 ? 19: scale);
					}
					addColumn = true;
					break;
				case OracleTypes.BINARY_FLOAT:
					mappedType = java.sql.Types.FLOAT;
					type = Types.FloatType.get();
					addColumn = true;
					break;
				case OracleTypes.BINARY_DOUBLE:
					mappedType = java.sql.Types.DOUBLE;
					type = Types.DoubleType.get();
					addColumn = true;
					break;
				case java.sql.Types.VARCHAR:
					mappedType = java.sql.Types.VARCHAR;
					type = Types.StringType.get();
					addColumn = true;
					break;
				case java.sql.Types.TIMESTAMP:
					mappedType = java.sql.Types.TIMESTAMP;
					type = Types.TimestampType.withoutZone();
					addColumn = true;
					break;
				case OracleTypes.TIMESTAMPLTZ:
				case OracleTypes.TIMESTAMPTZ:
					mappedType = java.sql.Types.TIMESTAMP_WITH_TIMEZONE;
					type = Types.TimestampType.withZone();
					addColumn = true;
					break;
				default:
					mappedType = Integer.MAX_VALUE;
					type = null;
					LOGGER.warn("Skipping column {} with jdbcType {}", columnName, jdbcType);
				}
				if (addColumn) {
					final int[] typeAndScale = new int[2];
					typeAndScale[0] = mappedType;
					typeAndScale[1] = mappedType != java.sql.Types.NUMERIC ? Integer.MIN_VALUE :
						scale < 0 ? 19: scale;
					columnsMap.put(columnName, typeAndScale);
					columnId++;
					if (nullable) {
						allColumns.add(
								Types.NestedField.optional(columnId, columnName, type));
					} else {
						allColumns.add(
								Types.NestedField.required(columnId, columnName, type));
					}
					if (idColumnsPresent) {
						if (idColumnNames.contains(columnName) && !nullable) {
							pkIds.add(columnId);
						}
					}
				}
			}
			//TODO
			//TODO
			//TODO
			final PartitionSpec spec = PartitionSpec.unpartitioned();
			table = catalog.createTable(
					icebergTable,
					pkIds.isEmpty() ? new Schema(allColumns) : new Schema(allColumns, pkIds),
					spec);
		} else {
			//TODO
			//TODO
			//TODO
			throw new SQLException("Not supported yet!");
		}
	}

	void loadData() throws SQLException {

		int partitionId = 1, taskId = 1;
		final GenericAppenderFactory af = new GenericAppenderFactory(table.schema());
		final OutputFileFactory off = OutputFileFactory.builderFor(table, partitionId, taskId).format(FileFormat.PARQUET).build();
		final PartitionKey partitionKey = new PartitionKey(table.spec(), table.spec().schema());

		if (isTableOrView) {

			PartitionedFanoutWriter<Record> partitionedFanoutWriter = new PartitionedFanoutWriter<Record>(
																			table.spec(),
																			//TODO - only parquet?
																			FileFormat.PARQUET,
																			af, off, table.io(),
																			targetFileSize) {
				@Override
				protected PartitionKey partition(Record record) {
					partitionKey.partition(record);
					return partitionKey;
				}
			};

			//TODO - where clause!!!
			final PreparedStatement ps = connection.prepareStatement("select * from \"" + sourceSchema + "\".\"" + sourceObject + "\"");
			final ResultSet rs = ps.executeQuery();
			//TODO - run statistic!
			//TODO - progress on screen!!!
			while (rs.next()) {
				final GenericRecord record = GenericRecord.create(table.schema());
				for (final Map.Entry<String, int[]> entry : columnsMap.entrySet()) {
					switch (entry.getValue()[0]) {
					case java.sql.Types.BOOLEAN:
						record.setField(entry.getKey(), rs.getBoolean(entry.getKey()));
						break;
					case java.sql.Types.INTEGER:
						record.setField(entry.getKey(), rs.getInt(entry.getKey()));
						break;
					case java.sql.Types.BIGINT:
						record.setField(entry.getKey(), rs.getLong(entry.getKey()));
						break;
					case java.sql.Types.NUMERIC:
						BigDecimal bd = rs.getBigDecimal(entry.getKey());
						record.setField(entry.getKey(), bd != null ? bd.setScale(entry.getValue()[1]) : null);
						break;
					case java.sql.Types.FLOAT:
						record.setField(entry.getKey(), rs.getFloat(entry.getKey()));
						break;
					case java.sql.Types.DOUBLE:
						record.setField(entry.getKey(), rs.getDouble(entry.getKey()));
						break;
					case java.sql.Types.TIMESTAMP:
					case java.sql.Types.TIME_WITH_TIMEZONE:
						record.setField(entry.getKey(), rs.getTimestamp(entry.getKey()));
						break;
					}
				}
				columnsMap.forEach((columnName, jdbcType) -> {
				});
				try {
					partitionedFanoutWriter.write(record);
				} catch (IOException ioe) {
					throw new SQLException(ioe);
				}
			}

			rs.close();
			ps.close();

			AppendFiles appendFiles = table.newAppend();
			// submit datafiles to the table
			try {
				Arrays.stream(partitionedFanoutWriter.dataFiles()).forEach(appendFiles::appendFile);
			} catch (IOException ioe) {
				throw new SQLException(ioe);
			}
			Snapshot newSnapshot = appendFiles.apply();
			appendFiles.commit();

		} else {
			//TODO
			//TODO
			//TODO
			throw new SQLException("Not supported yet!");
		}
	}

}
