/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.extensions.sql.zetasql.translation;

import static com.google.zetasql.ZetaSQLResolvedNodeKind.ResolvedNodeKind.RESOLVED_FUNCTION_CALL;
import static com.google.zetasql.ZetaSQLType.TypeKind.TYPE_BOOL;
import static com.google.zetasql.ZetaSQLType.TypeKind.TYPE_BYTES;
import static com.google.zetasql.ZetaSQLType.TypeKind.TYPE_DOUBLE;
import static com.google.zetasql.ZetaSQLType.TypeKind.TYPE_INT64;
import static com.google.zetasql.ZetaSQLType.TypeKind.TYPE_NUMERIC;
import static com.google.zetasql.ZetaSQLType.TypeKind.TYPE_STRING;
import static com.google.zetasql.ZetaSQLType.TypeKind.TYPE_TIMESTAMP;
import static org.apache.beam.sdk.extensions.sql.zetasql.BeamZetaSqlCatalog.PRE_DEFINED_WINDOW_FUNCTIONS;
import static org.apache.beam.sdk.extensions.sql.zetasql.BeamZetaSqlCatalog.USER_DEFINED_JAVA_SCALAR_FUNCTIONS;
import static org.apache.beam.sdk.extensions.sql.zetasql.BeamZetaSqlCatalog.USER_DEFINED_SQL_FUNCTIONS;
import static org.apache.beam.sdk.extensions.sql.zetasql.BeamZetaSqlCatalog.ZETASQL_FUNCTION_GROUP_NAME;
import static org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Preconditions.checkArgument;

import com.google.common.base.Ascii;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.zetasql.TVFRelation;
import com.google.zetasql.TVFRelation.Column;
import com.google.zetasql.TableValuedFunction;
import com.google.zetasql.TableValuedFunction.FixedOutputSchemaTVF;
import com.google.zetasql.Type;
import com.google.zetasql.Value;
import com.google.zetasql.ZetaSQLType.TypeKind;
import com.google.zetasql.io.grpc.Status;
import com.google.zetasql.resolvedast.ResolvedColumn;
import com.google.zetasql.resolvedast.ResolvedNodes;
import com.google.zetasql.resolvedast.ResolvedNodes.ResolvedAggregateScan;
import com.google.zetasql.resolvedast.ResolvedNodes.ResolvedArgumentRef;
import com.google.zetasql.resolvedast.ResolvedNodes.ResolvedCast;
import com.google.zetasql.resolvedast.ResolvedNodes.ResolvedColumnRef;
import com.google.zetasql.resolvedast.ResolvedNodes.ResolvedComputedColumn;
import com.google.zetasql.resolvedast.ResolvedNodes.ResolvedCreateFunctionStmt;
import com.google.zetasql.resolvedast.ResolvedNodes.ResolvedExpr;
import com.google.zetasql.resolvedast.ResolvedNodes.ResolvedFunctionCall;
import com.google.zetasql.resolvedast.ResolvedNodes.ResolvedGetStructField;
import com.google.zetasql.resolvedast.ResolvedNodes.ResolvedLiteral;
import com.google.zetasql.resolvedast.ResolvedNodes.ResolvedOrderByScan;
import com.google.zetasql.resolvedast.ResolvedNodes.ResolvedParameter;
import com.google.zetasql.resolvedast.ResolvedNodes.ResolvedProjectScan;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.beam.repackaged.core.org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.beam.sdk.annotations.Internal;
import org.apache.beam.sdk.extensions.sql.impl.QueryPlanner.QueryParameters;
import org.apache.beam.sdk.extensions.sql.impl.ZetaSqlUserDefinedSQLNativeTableValuedFunction;
import org.apache.beam.sdk.extensions.sql.impl.utils.TVFStreamingUtils;
import org.apache.beam.sdk.extensions.sql.zetasql.ZetaSqlCalciteTranslationUtils;
import org.apache.beam.sdk.extensions.sql.zetasql.ZetaSqlException;
import org.apache.beam.vendor.calcite.v1_28_0.org.apache.calcite.avatica.util.TimeUnit;
import org.apache.beam.vendor.calcite.v1_28_0.org.apache.calcite.plan.RelOptCluster;
import org.apache.beam.vendor.calcite.v1_28_0.org.apache.calcite.rel.RelNode;
import org.apache.beam.vendor.calcite.v1_28_0.org.apache.calcite.rel.type.RelDataType;
import org.apache.beam.vendor.calcite.v1_28_0.org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.beam.vendor.calcite.v1_28_0.org.apache.calcite.rel.type.RelDataTypeFieldImpl;
import org.apache.beam.vendor.calcite.v1_28_0.org.apache.calcite.rel.type.RelRecordType;
import org.apache.beam.vendor.calcite.v1_28_0.org.apache.calcite.rex.RexBuilder;
import org.apache.beam.vendor.calcite.v1_28_0.org.apache.calcite.rex.RexCall;
import org.apache.beam.vendor.calcite.v1_28_0.org.apache.calcite.rex.RexInputRef;
import org.apache.beam.vendor.calcite.v1_28_0.org.apache.calcite.rex.RexLiteral;
import org.apache.beam.vendor.calcite.v1_28_0.org.apache.calcite.rex.RexNode;
import org.apache.beam.vendor.calcite.v1_28_0.org.apache.calcite.sql.SqlIdentifier;
import org.apache.beam.vendor.calcite.v1_28_0.org.apache.calcite.sql.SqlIntervalQualifier;
import org.apache.beam.vendor.calcite.v1_28_0.org.apache.calcite.sql.SqlKind;
import org.apache.beam.vendor.calcite.v1_28_0.org.apache.calcite.sql.SqlOperator;
import org.apache.beam.vendor.calcite.v1_28_0.org.apache.calcite.sql.fun.SqlRowOperator;
import org.apache.beam.vendor.calcite.v1_28_0.org.apache.calcite.sql.parser.SqlParserPos;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Extracts expressions (function calls, field accesses) from the resolve query nodes, converts them
 * to RexNodes.
 */
@Internal
@SuppressWarnings({
  "nullness" // TODO(https://github.com/apache/beam/issues/20497)
})
public class ExpressionConverter {

  // Constants of pre-defined functions.
  private static final String WINDOW_START = "_START";
  private static final String WINDOW_END = "_END";
  private static final String FIXED_WINDOW = "TUMBLE";
  private static final String FIXED_WINDOW_START = FIXED_WINDOW + WINDOW_START;
  private static final String FIXED_WINDOW_END = FIXED_WINDOW + WINDOW_END;
  private static final String SLIDING_WINDOW = "HOP";
  private static final String SLIDING_WINDOW_START = SLIDING_WINDOW + WINDOW_START;
  private static final String SLIDING_WINDOW_END = SLIDING_WINDOW + WINDOW_END;
  private static final String SESSION_WINDOW = "SESSION";
  private static final String SESSION_WINDOW_START = SESSION_WINDOW + WINDOW_START;
  private static final String SESSION_WINDOW_END = SESSION_WINDOW + WINDOW_END;

  private static final ImmutableMap<String, String> WINDOW_START_END_TO_WINDOW_MAP =
      ImmutableMap.<String, String>builder()
          .put(FIXED_WINDOW_START, FIXED_WINDOW)
          .put(FIXED_WINDOW_END, FIXED_WINDOW)
          .put(SLIDING_WINDOW_START, SLIDING_WINDOW)
          .put(SLIDING_WINDOW_END, SLIDING_WINDOW)
          .put(SESSION_WINDOW_START, SESSION_WINDOW)
          .put(SESSION_WINDOW_END, SESSION_WINDOW)
          .build();

  private static final ImmutableSet<String> WINDOW_START_END_FUNCTION_SET =
      ImmutableSet.of(
          FIXED_WINDOW_START,
          FIXED_WINDOW_END,
          SLIDING_WINDOW_START,
          SLIDING_WINDOW_END,
          SESSION_WINDOW_START,
          SESSION_WINDOW_END);

  private static final ImmutableMap<TypeKind, ImmutableSet<TypeKind>> UNSUPPORTED_CASTING =
      ImmutableMap.<TypeKind, ImmutableSet<TypeKind>>builder()
          .put(TYPE_INT64, ImmutableSet.of(TYPE_DOUBLE))
          .put(TYPE_BOOL, ImmutableSet.of(TYPE_STRING))
          .put(TYPE_STRING, ImmutableSet.of(TYPE_BOOL, TYPE_DOUBLE))
          .build();

  private static final ImmutableSet<String> DATE_PART_UNITS_TO_MILLIS =
      ImmutableSet.of("DAY", "HOUR", "MINUTE", "SECOND");
  private static final ImmutableSet<String> DATE_PART_UNITS_TO_MONTHS = ImmutableSet.of("YEAR");

  private static final long ONE_SECOND_IN_MILLIS = 1000L;
  private static final long ONE_MINUTE_IN_MILLIS = 60L * ONE_SECOND_IN_MILLIS;
  private static final long ONE_HOUR_IN_MILLIS = 60L * ONE_MINUTE_IN_MILLIS;
  private static final long ONE_DAY_IN_MILLIS = 24L * ONE_HOUR_IN_MILLIS;

  @SuppressWarnings("unused")
  private static final long ONE_MONTH_IN_MILLIS = 30L * ONE_DAY_IN_MILLIS;

  @SuppressWarnings("unused")
  private static final long ONE_YEAR_IN_MILLIS = 365L * ONE_DAY_IN_MILLIS;

  // Constants of error messages.
  private static final String INTERVAL_DATE_PART_MSG =
      "YEAR, QUARTER, MONTH, WEEK, DAY, HOUR, MINUTE, SECOND, MILLISECOND";
  private static final String INTERVAL_FORMAT_MSG =
      "INTERVAL should be set as a STRING in the specific format: \"INTERVAL int64 date_part\"."
          + " The date_part includes: "
          + INTERVAL_DATE_PART_MSG;

  private final RelOptCluster cluster;
  private final QueryParameters queryParams;
  private int nullParamCount = 0;
  final UserFunctionDefinitions userFunctionDefinitions;

  public ExpressionConverter(
      RelOptCluster cluster,
      QueryParameters params,
      UserFunctionDefinitions userFunctionDefinitions) {
    this.cluster = cluster;
    this.queryParams = params;
    this.userFunctionDefinitions = userFunctionDefinitions;
  }

  /** Extract expressions from a project scan node. */
  public List<RexNode> retrieveRexNode(ResolvedProjectScan node, List<RelDataTypeField> fieldList) {
    List<RexNode> ret = new ArrayList<>();

    for (ResolvedColumn column : node.getColumnList()) {
      int index = -1;
      if ((index = indexOfResolvedColumnInExprList(node.getExprList(), column)) != -1) {
        ResolvedComputedColumn computedColumn = node.getExprList().get(index);
        int windowFieldIndex = -1;
        if (computedColumn.getExpr().nodeKind() == RESOLVED_FUNCTION_CALL) {
          String functionName =
              ((ResolvedFunctionCall) computedColumn.getExpr()).getFunction().getName();
          if (WINDOW_START_END_FUNCTION_SET.contains(functionName)) {
            ResolvedAggregateScan resolvedAggregateScan =
                (ResolvedAggregateScan) node.getInputScan();
            windowFieldIndex =
                indexOfWindowField(
                    resolvedAggregateScan.getGroupByList(),
                    resolvedAggregateScan.getColumnList(),
                    WINDOW_START_END_TO_WINDOW_MAP.get(functionName));
          }
        }
        ret.add(
            convertRexNodeFromComputedColumnWithFieldList(
                computedColumn, node.getInputScan().getColumnList(), fieldList, windowFieldIndex));
      } else {
        // ResolvedColumn is not a expression, which means it has to be an input column reference.
        index = indexOfProjectionColumnRef(column.getId(), node.getInputScan().getColumnList());
        if (index < 0 || index >= node.getInputScan().getColumnList().size()) {
          throw new IllegalStateException(
              String.format("Cannot find %s in fieldList %s", column, fieldList));
        }

        ret.add(rexBuilder().makeInputRef(fieldList.get(index).getType(), index));
      }
    }
    return ret;
  }

  /** Extract expressions from order by scan node. */
  public List<RexNode> retrieveRexNodeFromOrderByScan(
      RelOptCluster cluster, ResolvedOrderByScan node, List<RelDataTypeField> fieldList) {
    final RexBuilder rexBuilder = cluster.getRexBuilder();
    List<RexNode> ret = new ArrayList<>();

    for (ResolvedColumn column : node.getColumnList()) {
      int index = indexOfProjectionColumnRef(column.getId(), node.getInputScan().getColumnList());
      ret.add(rexBuilder.makeInputRef(fieldList.get(index).getType(), index));
    }

    return ret;
  }

  private static int indexOfResolvedColumnInExprList(
      ImmutableList<ResolvedComputedColumn> exprList, ResolvedColumn column) {
    if (exprList == null || exprList.isEmpty()) {
      return -1;
    }

    for (int i = 0; i < exprList.size(); i++) {
      ResolvedComputedColumn computedColumn = exprList.get(i);
      if (computedColumn.getColumn().equals(column)) {
        return i;
      }
    }

    return -1;
  }

  private static int indexOfWindowField(
      List<ResolvedComputedColumn> groupByList, List<ResolvedColumn> columnList, String windowFn) {
    for (ResolvedComputedColumn groupByComputedColumn : groupByList) {
      if (groupByComputedColumn.getExpr().nodeKind() == RESOLVED_FUNCTION_CALL) {
        ResolvedFunctionCall functionCall = (ResolvedFunctionCall) groupByComputedColumn.getExpr();
        if (functionCall.getFunction().getName().equals(windowFn)) {
          int ret =
              indexOfResolvedColumnInColumnList(columnList, groupByComputedColumn.getColumn());
          if (ret == -1) {
            throw new IllegalStateException("Cannot find " + windowFn + " in " + groupByList);
          } else {
            return ret;
          }
        }
      }
    }

    throw new IllegalStateException("Cannot find " + windowFn + " in " + groupByList);
  }

  private static int indexOfResolvedColumnInColumnList(
      List<ResolvedColumn> columnList, ResolvedColumn column) {
    if (columnList == null || columnList.isEmpty()) {
      return -1;
    }

    for (int i = 0; i < columnList.size(); i++) {
      if (columnList.get(i).equals(column)) {
        return i;
      }
    }

    return -1;
  }

  /** Create a RexNode for a corresponding resolved expression node. */
  public RexNode convertRexNodeFromResolvedExpr(
      ResolvedExpr expr,
      List<ResolvedColumn> columnList,
      List<RelDataTypeField> fieldList,
      Map<String, RexNode> functionArguments) {
    if (columnList == null || fieldList == null) {
      return convertRexNodeFromResolvedExpr(expr);
    }

    RexNode ret;

    switch (expr.nodeKind()) {
      case RESOLVED_LITERAL:
        ret = convertResolvedLiteral((ResolvedLiteral) expr);
        break;
      case RESOLVED_COLUMN_REF:
        ret = convertResolvedColumnRef((ResolvedColumnRef) expr, columnList, fieldList);
        break;
      case RESOLVED_FUNCTION_CALL:
        ret =
            convertResolvedFunctionCall(
                (ResolvedFunctionCall) expr, columnList, fieldList, functionArguments);
        break;
      case RESOLVED_CAST:
        ret = convertResolvedCast((ResolvedCast) expr, columnList, fieldList, functionArguments);
        break;
      case RESOLVED_PARAMETER:
        ret = convertResolvedParameter((ResolvedParameter) expr);
        break;
      case RESOLVED_GET_STRUCT_FIELD:
        ret =
            convertResolvedStructFieldAccess(
                (ResolvedGetStructField) expr, columnList, fieldList, functionArguments);
        break;
      case RESOLVED_ARGUMENT_REF:
        ret = convertResolvedArgumentRef((ResolvedArgumentRef) expr, functionArguments);
        break;
      default:
        ret = convertRexNodeFromResolvedExpr(expr);
    }

    return ret;
  }

  public RexNode convertRelNodeToRexRangeRef(RelNode rel) {
    return rexBuilder().makeRangeReference(rel);
  }

  /** Create a RexNode for a corresponding resolved expression. */
  public RexNode convertRexNodeFromResolvedExpr(ResolvedExpr expr) {
    RexNode ret;

    switch (expr.nodeKind()) {
      case RESOLVED_LITERAL:
        ret = convertResolvedLiteral((ResolvedLiteral) expr);
        break;
      case RESOLVED_COLUMN_REF:
        ret = convertResolvedColumnRef((ResolvedColumnRef) expr);
        break;
      case RESOLVED_FUNCTION_CALL:
        // TODO: is there a better way to shared code for different cases of
        // convertResolvedFunctionCall than passing nulls?
        ret =
            convertResolvedFunctionCall((ResolvedFunctionCall) expr, null, null, ImmutableMap.of());
        break;
      case RESOLVED_CAST:
        ret = convertResolvedCast((ResolvedCast) expr, null, null, ImmutableMap.of());
        break;
      case RESOLVED_PARAMETER:
        ret = convertResolvedParameter((ResolvedParameter) expr);
        break;
      case RESOLVED_GET_STRUCT_FIELD:
        ret = convertResolvedStructFieldAccess((ResolvedGetStructField) expr);
        break;
      case RESOLVED_SUBQUERY_EXPR:
        throw new UnsupportedOperationException("Does not support sub-queries");
      default:
        throw new UnsupportedOperationException(
            "Does not support expr node kind " + expr.nodeKind());
    }

    return ret;
  }

  private RexNode convertRexNodeFromComputedColumnWithFieldList(
      ResolvedComputedColumn column,
      List<ResolvedColumn> columnList,
      List<RelDataTypeField> fieldList,
      int windowFieldIndex) {
    if (column.getExpr().nodeKind() != RESOLVED_FUNCTION_CALL) {
      return convertRexNodeFromResolvedExpr(
          column.getExpr(), columnList, fieldList, ImmutableMap.of());
    }

    ResolvedFunctionCall functionCall = (ResolvedFunctionCall) column.getExpr();

    // TODO: is there any other illegal case?
    if (functionCall.getFunction().getName().equals(FIXED_WINDOW)
        || functionCall.getFunction().getName().equals(SLIDING_WINDOW)
        || functionCall.getFunction().getName().equals(SESSION_WINDOW)) {
      throw new ZetaSqlException(
          functionCall.getFunction().getName() + " shouldn't appear in SELECT exprlist.");
    }

    if (!functionCall.getFunction().getGroup().equals(PRE_DEFINED_WINDOW_FUNCTIONS)) {
      // non-window function should still go through normal FunctionCall conversion process.
      return convertRexNodeFromResolvedExpr(
          column.getExpr(), columnList, fieldList, ImmutableMap.of());
    }

    // ONLY window_start and window_end should arrive here.
    // TODO: Have extra verification here to make sure window start/end functions have the same
    // parameter with window function.
    List<RexNode> operands = new ArrayList<>();
    switch (functionCall.getFunction().getName()) {
      case FIXED_WINDOW_START:
      case SLIDING_WINDOW_START:
      case SESSION_WINDOW_START:
        // TODO: in Calcite implementation, session window's start is equal to end. Need to fix it
        // in Calcite.
      case SESSION_WINDOW_END:
        return rexBuilder()
            .makeInputRef(fieldList.get(windowFieldIndex).getType(), windowFieldIndex);
      case FIXED_WINDOW_END:
        operands.add(
            rexBuilder().makeInputRef(fieldList.get(windowFieldIndex).getType(), windowFieldIndex));
        // TODO: check window_end 's duration is the same as it's aggregate window.
        operands.add(
            convertIntervalToRexIntervalLiteral(
                (ResolvedLiteral) functionCall.getArgumentList().get(0)));
        return rexBuilder().makeCall(SqlOperators.ZETASQL_TIMESTAMP_ADD, operands);
      case SLIDING_WINDOW_END:
        operands.add(
            rexBuilder().makeInputRef(fieldList.get(windowFieldIndex).getType(), windowFieldIndex));
        operands.add(
            convertIntervalToRexIntervalLiteral(
                (ResolvedLiteral) functionCall.getArgumentList().get(1)));
        return rexBuilder().makeCall(SqlOperators.ZETASQL_TIMESTAMP_ADD, operands);
      default:
        throw new UnsupportedOperationException(
            "Does not support window start/end: " + functionCall.getFunction().getName());
    }
  }

  public RexNode trueLiteral() {
    return rexBuilder().makeLiteral(true);
  }

  /** Convert a resolved literal to a RexNode. */
  public RexNode convertResolvedLiteral(ResolvedLiteral resolvedLiteral) {
    return ZetaSqlCalciteTranslationUtils.toRexNode(resolvedLiteral.getValue(), rexBuilder());
  }

  /** Convert a TableValuedFunction in ZetaSQL to a RexCall in Calcite. */
  public RexCall convertTableValuedFunction(
      RelNode input,
      TableValuedFunction tvf,
      List<ResolvedNodes.ResolvedFunctionArgument> argumentList,
      List<ResolvedColumn> inputTableColumns) {
    ResolvedColumn wmCol;
    // Handle builtin windowing TVF.
    switch (tvf.getName()) {
      case TVFStreamingUtils.FIXED_WINDOW_TVF:
        // TUMBLE tvf's second argument is descriptor.
        wmCol = extractWatermarkColumnFromDescriptor(argumentList.get(1).getDescriptorArg());

        return (RexCall)
            rexBuilder()
                .makeCall(
                    new SqlWindowTableFunction(SqlKind.TUMBLE.name()),
                    convertRelNodeToRexRangeRef(input),
                    convertResolvedColumnToRexInputRef(wmCol, inputTableColumns),
                    convertIntervalToRexIntervalLiteral(
                        (ResolvedLiteral) argumentList.get(2).getExpr()));

      case TVFStreamingUtils.SLIDING_WINDOW_TVF:
        // HOP tvf's second argument is descriptor.
        wmCol = extractWatermarkColumnFromDescriptor(argumentList.get(1).getDescriptorArg());
        return (RexCall)
            rexBuilder()
                .makeCall(
                    new SqlWindowTableFunction(SqlKind.HOP.name()),
                    convertRelNodeToRexRangeRef(input),
                    convertResolvedColumnToRexInputRef(wmCol, inputTableColumns),
                    convertIntervalToRexIntervalLiteral(
                        (ResolvedLiteral) argumentList.get(2).getExpr()),
                    convertIntervalToRexIntervalLiteral(
                        (ResolvedLiteral) argumentList.get(3).getExpr()));

      case TVFStreamingUtils.SESSION_WINDOW_TVF:
        // SESSION tvf's second argument is descriptor.
        wmCol = extractWatermarkColumnFromDescriptor(argumentList.get(1).getDescriptorArg());
        // SESSION tvf's third argument is descriptor.
        List<ResolvedColumn> keyCol =
            extractSessionKeyColumnFromDescriptor(argumentList.get(2).getDescriptorArg());
        List<RexNode> operands = new ArrayList<>();
        operands.add(convertRelNodeToRexRangeRef(input));
        operands.add(convertResolvedColumnToRexInputRef(wmCol, inputTableColumns));
        operands.add(
            convertIntervalToRexIntervalLiteral((ResolvedLiteral) argumentList.get(3).getExpr()));
        operands.addAll(convertResolvedColumnsToRexInputRef(keyCol, inputTableColumns));
        return (RexCall)
            rexBuilder().makeCall(new SqlWindowTableFunction(SqlKind.SESSION.name()), operands);
    }

    if (tvf instanceof FixedOutputSchemaTVF) {
      FixedOutputSchemaTVF fixedOutputSchemaTVF = (FixedOutputSchemaTVF) tvf;
      return (RexCall)
          rexBuilder()
              .makeCall(
                  new ZetaSqlUserDefinedSQLNativeTableValuedFunction(
                      new SqlIdentifier(tvf.getName(), SqlParserPos.ZERO),
                      opBinding -> {
                        TVFRelation rel = fixedOutputSchemaTVF.getOutputSchema();
                        // TODO(yathu) revert this workaround when ZetaSQL adds back this API.
                        List<Column> cols;
                        try {
                          cols = (List<Column>) FieldUtils.readField(rel, "columns", true);
                        } catch (IllegalAccessException e) {
                          throw new RuntimeException(e);
                        }
                        List<RelDataTypeField> relDataTypeFields =
                            convertTVFRelationColumnsToRelDataTypeFields(cols);
                        return new RelRecordType(relDataTypeFields);
                      },
                      null,
                      null,
                      null,
                      null));
    }

    throw new UnsupportedOperationException(
        "Does not support table-valued function: " + tvf.getName());
  }

  private List<RelDataTypeField> convertTVFRelationColumnsToRelDataTypeFields(
      List<TVFRelation.Column> columns) {
    return IntStream.range(0, columns.size())
        .mapToObj(
            i ->
                new RelDataTypeFieldImpl(
                    columns.get(i).getName(),
                    i,
                    ZetaSqlCalciteTranslationUtils.toCalciteType(
                        columns.get(i).getType(), false, rexBuilder())))
        .collect(Collectors.toList());
  }

  private List<RexInputRef> convertResolvedColumnsToRexInputRef(
      List<ResolvedColumn> columns, List<ResolvedColumn> inputTableColumns) {
    List<RexInputRef> ret = new ArrayList<>();
    for (ResolvedColumn column : columns) {
      ret.add(convertResolvedColumnToRexInputRef(column, inputTableColumns));
    }
    return ret;
  }

  private RexInputRef convertResolvedColumnToRexInputRef(
      ResolvedColumn column, List<ResolvedColumn> inputTableColumns) {
    for (int i = 0; i < inputTableColumns.size(); i++) {
      if (inputTableColumns.get(i).equals(column)) {
        return rexBuilder()
            .makeInputRef(
                ZetaSqlCalciteTranslationUtils.toCalciteType(column.getType(), false, rexBuilder()),
                i);
      }
    }

    throw new IllegalArgumentException(
        "ZetaSQL parser guarantees that wmCol can be found from inputTableColumns so it shouldn't reach here.");
  }

  private ResolvedColumn extractWatermarkColumnFromDescriptor(
      ResolvedNodes.ResolvedDescriptor descriptor) {
    ResolvedColumn wmCol = descriptor.getDescriptorColumnList().get(0);
    checkArgument(
        wmCol.getType().getKind() == TYPE_TIMESTAMP,
        "Watermarked column should be TIMESTAMP type: %s",
        descriptor.getDescriptorColumnNameList().get(0));
    return wmCol;
  }

  private List<ResolvedColumn> extractSessionKeyColumnFromDescriptor(
      ResolvedNodes.ResolvedDescriptor descriptor) {
    checkArgument(
        descriptor.getDescriptorColumnNameList().size() > 0,
        "Session key descriptor should not be empty");

    return descriptor.getDescriptorColumnList();
  }

  private RexNode convertResolvedColumnRef(
      ResolvedColumnRef columnRef,
      List<ResolvedColumn> columnList,
      List<RelDataTypeField> fieldList) {
    int index = indexOfProjectionColumnRef(columnRef.getColumn().getId(), columnList);
    if (index < 0 || index >= columnList.size()) {
      throw new IllegalStateException(
          String.format("Cannot find %s in fieldList %s", columnRef.getColumn(), fieldList));
    }
    return rexBuilder().makeInputRef(fieldList.get(index).getType(), index);
  }

  private RexNode convertResolvedColumnRef(ResolvedColumnRef columnRef) {
    // TODO: id - 1 might be only correct if the columns read from TableScan.
    // What if the columns come from other scans (which means their id are not indexed from 0),
    // and what if there are some mis-order?
    // TODO: can join key be NULL?
    return rexBuilder()
        .makeInputRef(
            ZetaSqlCalciteTranslationUtils.toCalciteType(columnRef.getType(), false, rexBuilder()),
            (int) columnRef.getColumn().getId() - 1);
  }

  /** Return an index of the projection column reference. */
  public int indexOfProjectionColumnRef(long colId, List<ResolvedColumn> columnList) {
    int ret = -1;
    for (int i = 0; i < columnList.size(); i++) {
      if (columnList.get(i).getId() == colId) {
        ret = i;
        break;
      }
    }

    return ret;
  }

  private RexNode convertResolvedFunctionCall(
      ResolvedFunctionCall functionCall,
      @Nullable List<ResolvedColumn> columnList,
      @Nullable List<RelDataTypeField> fieldList,
      Map<String, RexNode> outerFunctionArguments) {
    final String funGroup = functionCall.getFunction().getGroup();
    final String funName = functionCall.getFunction().getName();
    SqlOperator op = SqlOperatorMappingTable.create(functionCall);
    List<RexNode> operands = new ArrayList<>();

    if (PRE_DEFINED_WINDOW_FUNCTIONS.equals(funGroup)) {
      switch (funName) {
        case FIXED_WINDOW:
        case SESSION_WINDOW:
          // TODO: check size and type of window function argument list.
          // Add ts column reference to operands.
          operands.add(
              convertRexNodeFromResolvedExpr(
                  functionCall.getArgumentList().get(0),
                  columnList,
                  fieldList,
                  outerFunctionArguments));
          // Add fixed window size or session window gap to operands.
          operands.add(
              convertIntervalToRexIntervalLiteral(
                  (ResolvedLiteral) functionCall.getArgumentList().get(1)));
          break;
        case SLIDING_WINDOW:
          // Add ts column reference to operands.
          operands.add(
              convertRexNodeFromResolvedExpr(
                  functionCall.getArgumentList().get(0),
                  columnList,
                  fieldList,
                  outerFunctionArguments));
          // add sliding window emit frequency to operands.
          operands.add(
              convertIntervalToRexIntervalLiteral(
                  (ResolvedLiteral) functionCall.getArgumentList().get(1)));
          // add sliding window size to operands.
          operands.add(
              convertIntervalToRexIntervalLiteral(
                  (ResolvedLiteral) functionCall.getArgumentList().get(2)));
          break;
        default:
          throw new UnsupportedOperationException(
              "Unsupported function: " + funName + ". Only support TUMBLE, HOP, and SESSION now.");
      }
    } else if (ZETASQL_FUNCTION_GROUP_NAME.equals(funGroup)) {
      if (op == null) {
        Type returnType = functionCall.getSignature().getResultType().getType();
        if (returnType != null) {
          op =
              SqlOperators.createZetaSqlFunction(
                  funName,
                  ZetaSqlCalciteTranslationUtils.toCalciteType(returnType, false, rexBuilder())
                      .getSqlTypeName());
        } else {
          throw new UnsupportedOperationException("Does not support ZetaSQL function: " + funName);
        }
      }

      for (ResolvedExpr expr : functionCall.getArgumentList()) {
        operands.add(
            convertRexNodeFromResolvedExpr(expr, columnList, fieldList, outerFunctionArguments));
      }
    } else if (USER_DEFINED_JAVA_SCALAR_FUNCTIONS.equals(funGroup)) {
      UserFunctionDefinitions.JavaScalarFunction javaScalarFunction =
          userFunctionDefinitions
              .javaScalarFunctions()
              .get(functionCall.getFunction().getNamePath());
      ArrayList<RexNode> innerFunctionArguments = new ArrayList<>();
      for (int i = 0; i < functionCall.getArgumentList().size(); i++) {
        ResolvedExpr argExpr = functionCall.getArgumentList().get(i);
        RexNode argNode =
            convertRexNodeFromResolvedExpr(argExpr, columnList, fieldList, outerFunctionArguments);
        innerFunctionArguments.add(argNode);
      }
      return rexBuilder()
          .makeCall(
              SqlOperators.createUdfOperator(
                  functionCall.getFunction().getName(),
                  javaScalarFunction.method(),
                  USER_DEFINED_JAVA_SCALAR_FUNCTIONS,
                  javaScalarFunction.jarPath()),
              innerFunctionArguments);
    } else if (USER_DEFINED_SQL_FUNCTIONS.equals(funGroup)) {
      ResolvedCreateFunctionStmt createFunctionStmt =
          userFunctionDefinitions
              .sqlScalarFunctions()
              .get(functionCall.getFunction().getNamePath());
      ResolvedExpr functionExpression = createFunctionStmt.getFunctionExpression();
      ImmutableMap.Builder<String, RexNode> innerFunctionArguments = ImmutableMap.builder();
      for (int i = 0; i < functionCall.getArgumentList().size(); i++) {
        String argName = createFunctionStmt.getArgumentNameList().get(i);
        ResolvedExpr argExpr = functionCall.getArgumentList().get(i);
        RexNode argNode =
            convertRexNodeFromResolvedExpr(argExpr, columnList, fieldList, outerFunctionArguments);
        innerFunctionArguments.put(argName, argNode);
      }
      return this.convertRexNodeFromResolvedExpr(
          functionExpression, columnList, fieldList, innerFunctionArguments.build());
    } else {
      throw new UnsupportedOperationException("Does not support function group: " + funGroup);
    }

    SqlOperatorRewriter rewriter =
        SqlOperatorMappingTable.ZETASQL_FUNCTION_TO_CALCITE_SQL_OPERATOR_REWRITER.get(funName);

    if (rewriter != null) {
      return rewriter.apply(rexBuilder(), operands);
    } else {
      return rexBuilder().makeCall(op, operands);
    }
  }

  private RexNode convertIntervalToRexIntervalLiteral(ResolvedLiteral resolvedLiteral) {
    if (resolvedLiteral.getType().getKind() != TYPE_STRING) {
      throw new ZetaSqlException(INTERVAL_FORMAT_MSG);
    }

    String valStr = resolvedLiteral.getValue().getStringValue();
    List<String> stringList =
        Arrays.stream(valStr.split(" ")).filter(s -> !s.isEmpty()).collect(Collectors.toList());

    if (stringList.size() != 3) {
      throw new ZetaSqlException(INTERVAL_FORMAT_MSG);
    }

    if (!Ascii.toUpperCase(stringList.get(0)).equals("INTERVAL")) {
      throw new ZetaSqlException(INTERVAL_FORMAT_MSG);
    }

    long intervalValue;
    try {
      intervalValue = Long.parseLong(stringList.get(1));
    } catch (NumberFormatException e) {
      throw new ZetaSqlException(
          Status.UNIMPLEMENTED
              .withDescription(INTERVAL_FORMAT_MSG)
              .withCause(e)
              .asRuntimeException());
    }

    String intervalDatepart = Ascii.toUpperCase(stringList.get(2));
    return createCalciteIntervalRexLiteral(intervalValue, intervalDatepart);
  }

  private RexLiteral createCalciteIntervalRexLiteral(long intervalValue, String intervalTimeUnit) {
    SqlIntervalQualifier sqlIntervalQualifier =
        convertIntervalDatepartToSqlIntervalQualifier(intervalTimeUnit);
    BigDecimal decimalValue;
    if (DATE_PART_UNITS_TO_MILLIS.contains(intervalTimeUnit)) {
      decimalValue = convertIntervalValueToMillis(sqlIntervalQualifier, intervalValue);
    } else if (DATE_PART_UNITS_TO_MONTHS.contains(intervalTimeUnit)) {
      decimalValue = new BigDecimal(intervalValue * 12);
    } else {
      decimalValue = new BigDecimal(intervalValue);
    }
    return rexBuilder().makeIntervalLiteral(decimalValue, sqlIntervalQualifier);
  }

  private static BigDecimal convertIntervalValueToMillis(
      SqlIntervalQualifier qualifier, long value) {
    switch (qualifier.typeName()) {
      case INTERVAL_DAY:
        return new BigDecimal(value * ONE_DAY_IN_MILLIS);
      case INTERVAL_HOUR:
        return new BigDecimal(value * ONE_HOUR_IN_MILLIS);
      case INTERVAL_MINUTE:
        return new BigDecimal(value * ONE_MINUTE_IN_MILLIS);
      case INTERVAL_SECOND:
        return new BigDecimal(value * ONE_SECOND_IN_MILLIS);
      default:
        throw new ZetaSqlException(qualifier.typeName().toString());
    }
  }

  private static SqlIntervalQualifier convertIntervalDatepartToSqlIntervalQualifier(
      String datePart) {
    switch (datePart) {
      case "YEAR":
        return new SqlIntervalQualifier(TimeUnit.YEAR, null, SqlParserPos.ZERO);
      case "MONTH":
        return new SqlIntervalQualifier(TimeUnit.MONTH, null, SqlParserPos.ZERO);
      case "DAY":
        return new SqlIntervalQualifier(TimeUnit.DAY, null, SqlParserPos.ZERO);
      case "HOUR":
        return new SqlIntervalQualifier(TimeUnit.HOUR, null, SqlParserPos.ZERO);
      case "MINUTE":
        return new SqlIntervalQualifier(TimeUnit.MINUTE, null, SqlParserPos.ZERO);
      case "SECOND":
        return new SqlIntervalQualifier(TimeUnit.SECOND, null, SqlParserPos.ZERO);
      case "WEEK":
        return new SqlIntervalQualifier(TimeUnit.WEEK, null, SqlParserPos.ZERO);
      case "QUARTER":
        return new SqlIntervalQualifier(TimeUnit.QUARTER, null, SqlParserPos.ZERO);
      case "MILLISECOND":
        return new SqlIntervalQualifier(TimeUnit.MILLISECOND, null, SqlParserPos.ZERO);
      default:
        throw new ZetaSqlException(
            String.format(
                "Received an undefined INTERVAL unit: %s. Please specify unit from the following"
                    + " list: %s.",
                datePart, INTERVAL_DATE_PART_MSG));
    }
  }

  private RexNode convertResolvedCast(
      ResolvedCast resolvedCast,
      List<ResolvedColumn> columnList,
      List<RelDataTypeField> fieldList,
      Map<String, RexNode> functionArguments) {
    return convertResolvedCast(
        resolvedCast,
        convertRexNodeFromResolvedExpr(
            resolvedCast.getExpr(), columnList, fieldList, functionArguments));
  }

  private RexNode convertResolvedCast(ResolvedCast resolvedCast, RexNode input) {
    TypeKind fromType = resolvedCast.getExpr().getType().getKind();
    TypeKind toType = resolvedCast.getType().getKind();
    isCastingSupported(fromType, toType, input);

    // nullability of the output type should match that of the input node's type
    RelDataType outputType =
        ZetaSqlCalciteTranslationUtils.toCalciteType(
            resolvedCast.getType(), input.getType().isNullable(), rexBuilder());

    if (isZetaSQLCast(fromType, toType)) {
      return rexBuilder().makeCall(outputType, SqlOperators.CAST_OP, ImmutableList.of(input));
    } else {
      return rexBuilder().makeCast(outputType, input);
    }
  }

  private static void isCastingSupported(TypeKind fromType, TypeKind toType, RexNode input) {
    if (UNSUPPORTED_CASTING.containsKey(toType)
        && UNSUPPORTED_CASTING.get(toType).contains(fromType)) {
      throw new UnsupportedOperationException(
          "Does not support CAST(" + fromType + " AS " + toType + ")");
    }
    if (fromType.equals(TYPE_DOUBLE)
        && toType.equals(TYPE_NUMERIC)
        && input instanceof RexLiteral) {
      BigDecimal value = (BigDecimal) ((RexLiteral) input).getValue();
      if (value.compareTo(ZetaSqlCalciteTranslationUtils.ZETASQL_NUMERIC_MAX_VALUE) > 0) {
        throw new ZetaSqlException(
            Status.OUT_OF_RANGE
                .withDescription(
                    String.format(
                        "Casting %s as %s would cause overflow of literal %s.",
                        fromType, toType, value))
                .asRuntimeException());
      }
      if (value.compareTo(ZetaSqlCalciteTranslationUtils.ZETASQL_NUMERIC_MIN_VALUE) < 0) {
        throw new ZetaSqlException(
            Status.OUT_OF_RANGE
                .withDescription(
                    String.format(
                        "Casting %s as %s would cause underflow of literal %s.",
                        fromType, toType, value))
                .asRuntimeException());
      }
      if (value.scale() > ZetaSqlCalciteTranslationUtils.ZETASQL_NUMERIC_SCALE) {
        throw new ZetaSqlException(
            Status.OUT_OF_RANGE
                .withDescription(
                    String.format(
                        "Cannot cast %s as %s: scale %d exceeds %d for literal %s.",
                        fromType,
                        toType,
                        value.scale(),
                        ZetaSqlCalciteTranslationUtils.ZETASQL_NUMERIC_SCALE,
                        value))
                .asRuntimeException());
      }
    }
  }

  private static boolean isZetaSQLCast(TypeKind fromType, TypeKind toType) {
    // TODO: Structure CAST_OP so that we don't have to repeat the supported types
    // here
    return (fromType.equals(TYPE_BYTES) && toType.equals(TYPE_STRING))
        || (fromType.equals(TYPE_INT64) && toType.equals(TYPE_BOOL))
        || (fromType.equals(TYPE_BOOL) && toType.equals(TYPE_INT64))
        || (fromType.equals(TYPE_TIMESTAMP) && toType.equals(TYPE_STRING));
  }

  private RexNode convertResolvedParameter(ResolvedParameter parameter) {
    Value value;
    switch (queryParams.getKind()) {
      case NAMED:
        value = ((Map<String, Value>) queryParams.named()).get(parameter.getName());
        break;
      case POSITIONAL:
        // parameter is 1-indexed, while parameter list is 0-indexed.
        value = ((List<Value>) queryParams.positional()).get((int) parameter.getPosition() - 1);
        break;
      default:
        throw new IllegalArgumentException("Found unexpected parameter " + parameter);
    }
    Preconditions.checkState(parameter.getType().equals(value.getType()));
    if (value.isNull()) {
      // In some cases NULL parameter cannot be substituted with NULL literal
      // Therefore we create a dynamic parameter placeholder here for each NULL parameter
      return rexBuilder()
          .makeDynamicParam(
              ZetaSqlCalciteTranslationUtils.toCalciteType(value.getType(), true, rexBuilder()),
              nullParamCount++);
    } else {
      // Substitute non-NULL parameter with literal
      return ZetaSqlCalciteTranslationUtils.toRexNode(value, rexBuilder());
    }
  }

  private RexNode convertResolvedArgumentRef(
      ResolvedArgumentRef resolvedArgumentRef, Map<String, RexNode> functionArguments) {
    return functionArguments.get(resolvedArgumentRef.getName());
  }

  private RexNode convertResolvedStructFieldAccess(ResolvedGetStructField resolvedGetStructField) {
    RexNode referencedExpr = convertRexNodeFromResolvedExpr(resolvedGetStructField.getExpr());
    return convertResolvedStructFieldAccessInternal(
        referencedExpr, (int) resolvedGetStructField.getFieldIdx());
  }

  private RexNode convertResolvedStructFieldAccess(
      ResolvedGetStructField resolvedGetStructField,
      List<ResolvedColumn> columnList,
      List<RelDataTypeField> fieldList,
      Map<String, RexNode> functionArguments) {
    RexNode referencedExpr =
        convertRexNodeFromResolvedExpr(
            resolvedGetStructField.getExpr(), columnList, fieldList, functionArguments);
    return convertResolvedStructFieldAccessInternal(
        referencedExpr, (int) resolvedGetStructField.getFieldIdx());
  }

  private RexNode convertResolvedStructFieldAccessInternal(RexNode referencedExpr, int fieldIdx) {
    // Calcite SQL does not allow the ROW constructor to be dereferenced directly, so do it here.
    if (referencedExpr instanceof RexCall
        && ((RexCall) referencedExpr).getOperator() instanceof SqlRowOperator) {
      return ((RexCall) referencedExpr).getOperands().get(fieldIdx);
    }
    return rexBuilder().makeFieldAccess(referencedExpr, fieldIdx);
  }

  private RexBuilder rexBuilder() {
    return cluster.getRexBuilder();
  }
}
