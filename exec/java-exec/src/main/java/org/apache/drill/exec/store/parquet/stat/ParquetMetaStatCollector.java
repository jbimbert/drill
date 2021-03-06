/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.store.parquet.stat;

import com.google.common.base.Stopwatch;
import org.apache.drill.common.expression.SchemaPath;
import org.apache.drill.common.types.TypeProtos;
import org.apache.drill.common.types.Types;
import org.apache.drill.exec.store.parquet.ParquetReaderUtility;
import org.apache.parquet.column.statistics.BinaryStatistics;
import org.apache.parquet.column.statistics.BooleanStatistics;
import org.apache.parquet.column.statistics.DoubleStatistics;
import org.apache.parquet.column.statistics.FloatStatistics;
import org.apache.parquet.column.statistics.IntStatistics;
import org.apache.parquet.column.statistics.LongStatistics;
import org.apache.parquet.column.statistics.Statistics;
import org.apache.parquet.schema.OriginalType;
import org.apache.parquet.schema.PrimitiveType;
import org.joda.time.DateTimeConstants;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.apache.drill.exec.store.parquet.metadata.Metadata_V3.ColumnTypeMetadata_v3;
import static org.apache.drill.exec.store.parquet.metadata.Metadata_V3.ParquetTableMetadata_v3;
import static org.apache.drill.exec.store.parquet.metadata.MetadataBase.ColumnMetadata;
import static org.apache.drill.exec.store.parquet.metadata.MetadataBase.ParquetTableMetadataBase;

public class ParquetMetaStatCollector implements  ColumnStatCollector {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ParquetMetaStatCollector.class);

  private final ParquetTableMetadataBase parquetTableMetadata;
  private final List<? extends ColumnMetadata> columnMetadataList;
  private final Map<String, String> implicitColValues;

  public ParquetMetaStatCollector(ParquetTableMetadataBase parquetTableMetadata,
      List<? extends ColumnMetadata> columnMetadataList, Map<String, String> implicitColValues) {
    this.parquetTableMetadata = parquetTableMetadata;
    this.columnMetadataList = columnMetadataList;

    // Reasons to pass implicit columns and their values:
    // 1. Differentiate implicit columns from regular non-exist columns. Implicit columns do not
    //    exist in parquet metadata. Without such knowledge, implicit columns is treated as non-exist
    //    column.  A condition on non-exist column would lead to canDrop = true, which is not the
    //    right behavior for condition on implicit columns.

    // 2. Pass in the implicit column name with corresponding values, and wrap them in Statistics with
    //    min and max having same value. This expands the possibility of pruning.
    //    For example, regCol = 5 or dir0 = 1995. If regCol is not a partition column, we would not do
    //    any partition pruning in the current partition pruning logical. Pass the implicit column values
    //    may allow us to prune some row groups using condition regCol = 5 or dir0 = 1995.

    this.implicitColValues = implicitColValues;
  }

  @Override
  public Map<SchemaPath, ColumnStatistics> collectColStat(Set<SchemaPath> fields) {
    Stopwatch timer = logger.isDebugEnabled() ? Stopwatch.createStarted() : null;

    // map from column to ColumnMetadata
    final Map<SchemaPath, ColumnMetadata> columnMetadataMap = new HashMap<>();

    // map from column name to column statistics.
    final Map<SchemaPath, ColumnStatistics> statMap = new HashMap<>();

    for (final ColumnMetadata columnMetadata : columnMetadataList) {
      SchemaPath schemaPath = SchemaPath.getCompoundPath(columnMetadata.getName());
      columnMetadataMap.put(schemaPath, columnMetadata);
    }

    for (final SchemaPath field : fields) {
      final PrimitiveType.PrimitiveTypeName primitiveType;
      final OriginalType originalType;

      final ColumnMetadata columnMetadata = columnMetadataMap.get(field.getUnIndexed());

      if (columnMetadata != null) {
        final Object min = columnMetadata.getMinValue();
        final Object max = columnMetadata.getMaxValue();
        final long numNulls = columnMetadata.getNulls() == null ? -1 : columnMetadata.getNulls();

        primitiveType = this.parquetTableMetadata.getPrimitiveType(columnMetadata.getName());
        originalType = this.parquetTableMetadata.getOriginalType(columnMetadata.getName());
        int precision = 0;
        int scale = 0;
        // ColumnTypeMetadata_v3 stores information about scale and precision
        if (parquetTableMetadata instanceof ParquetTableMetadata_v3) {
          ColumnTypeMetadata_v3 columnTypeInfo = ((ParquetTableMetadata_v3) parquetTableMetadata)
                                                                          .getColumnTypeInfo(columnMetadata.getName());
          scale = columnTypeInfo.scale;
          precision = columnTypeInfo.precision;
        }

        statMap.put(field, getStat(min, max, numNulls, primitiveType, originalType, scale, precision));
      } else {
        final String columnName = field.getRootSegment().getPath();
        if (implicitColValues.containsKey(columnName)) {
          TypeProtos.MajorType type = Types.required(TypeProtos.MinorType.VARCHAR);
          Statistics stat = new BinaryStatistics();
          stat.setNumNulls(0);
          byte[] val = implicitColValues.get(columnName).getBytes();
          stat.setMinMaxFromBytes(val, val);
          statMap.put(field, new ColumnStatistics(stat, type));
        }
      }
    }

    if (timer != null) {
      logger.debug("Took {} ms to column statistics for row group", timer.elapsed(TimeUnit.MILLISECONDS));
      timer.stop();
    }

    return statMap;
  }

  /**
   * Builds column statistics using given primitiveType, originalType, scale,
   * precision, numNull, min and max values.
   *
   * @param min             min value for statistics
   * @param max             max value for statistics
   * @param numNulls        num_nulls for statistics
   * @param primitiveType   type that determines statistics class
   * @param originalType    type that determines statistics class
   * @param scale           scale value (used for DECIMAL type)
   * @param precision       precision value (used for DECIMAL type)
   * @return column statistics
   */
  private ColumnStatistics getStat(Object min, Object max, long numNulls,
                                   PrimitiveType.PrimitiveTypeName primitiveType, OriginalType originalType,
                                   int scale, int precision) {
    Statistics stat = Statistics.getStatsBasedOnType(primitiveType);
    Statistics convertedStat = stat;

    TypeProtos.MajorType type = ParquetReaderUtility.getType(primitiveType, originalType, scale, precision);
    stat.setNumNulls(numNulls);

    if (min != null && max != null ) {
      switch (type.getMinorType()) {
      case INT :
      case TIME:
        ((IntStatistics) stat).setMinMax(Integer.parseInt(min.toString()), Integer.parseInt(max.toString()));
        break;
      case BIGINT:
      case TIMESTAMP:
        ((LongStatistics) stat).setMinMax(Long.parseLong(min.toString()), Long.parseLong(max.toString()));
        break;
      case FLOAT4:
        ((FloatStatistics) stat).setMinMax(Float.parseFloat(min.toString()), Float.parseFloat(max.toString()));
        break;
      case FLOAT8:
        ((DoubleStatistics) stat).setMinMax(Double.parseDouble(min.toString()), Double.parseDouble(max.toString()));
        break;
      case DATE:
        convertedStat = new LongStatistics();
        convertedStat.setNumNulls(stat.getNumNulls());
        final long minMS = convertToDrillDateValue(Integer.parseInt(min.toString()));
        final long maxMS = convertToDrillDateValue(Integer.parseInt(max.toString()));
        ((LongStatistics) convertedStat ).setMinMax(minMS, maxMS);
        break;
      case BIT:
        ((BooleanStatistics) stat).setMinMax(Boolean.parseBoolean(min.toString()), Boolean.parseBoolean(max.toString()));
        break;
      default:
      }
    }

    return new ColumnStatistics(convertedStat, type);
  }

  private static long convertToDrillDateValue(int dateValue) {
      return dateValue * (long) DateTimeConstants.MILLIS_PER_DAY;
  }

}
