/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import scala.jdk.CollectionConverters._
import scala.util.Try

import org.scalatest.Assertions._

import org.apache.spark.TestUtils
import org.apache.spark.api.python.{PythonBroadcast, PythonEvalType, PythonFunction, PythonUtils, SimplePythonFunction}
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.sql.catalyst.expressions.{Cast, Expression, ExprId, PythonUDF}
import org.apache.spark.sql.catalyst.plans.SQLHelper
import org.apache.spark.sql.execution.python.{UserDefinedPythonDataSource, UserDefinedPythonFunction, UserDefinedPythonTableFunction}
import org.apache.spark.sql.expressions.SparkUserDefinedFunction
import org.apache.spark.sql.types.{DataType, IntegerType, NullType, StringType, StructType}
import org.apache.spark.util.ArrayImplicits._

/**
 * This object targets to integrate various UDF test cases so that Scalar UDF, Python UDF,
 * Scalar Pandas UDF and Grouped Aggregate Pandas UDF can be tested in SBT & Maven tests.
 *
 * The available UDFs are special. For Scalar UDF, Python UDF and Scalar Pandas UDF,
 * it defines an UDF wrapped by cast. So, the input column is casted into string,
 * UDF returns strings as are, and then output column is casted back to the input column.
 * In this way, UDF is virtually no-op.
 *
 * Note that, due to this implementation limitation, complex types such as map, array and struct
 * types do not work with this UDFs because they cannot be same after the cast roundtrip.
 *
 * To register Scala UDF in SQL:
 * {{{
 *   val scalaTestUDF = TestScalaUDF(name = "udf_name")
 *   registerTestUDF(scalaTestUDF, spark)
 * }}}
 *
 * To register Python UDF in SQL:
 * {{{
 *   val pythonTestUDF = TestPythonUDF(name = "udf_name")
 *   registerTestUDF(pythonTestUDF, spark)
 * }}}
 *
 * To register Scalar Pandas UDF in SQL:
 * {{{
 *   val pandasTestUDF = TestScalarPandasUDF(name = "udf_name")
 *   registerTestUDF(pandasTestUDF, spark)
 * }}}
 *
 * To use it in Scala API and SQL:
 * {{{
 *   sql("SELECT udf_name(1)")
 *   val df = spark.range(10)
 *   df.select(expr("udf_name(id)")
 *   df.select(pandasTestUDF(df("id")))
 * }}}
 *
 * For Grouped Aggregate Pandas UDF, it defines an UDF that calculates the count using pandas.
 * The UDF returns the count of the given column. In this way, UDF is virtually not no-op.
 *
 * To register Grouped Aggregate Pandas UDF in SQL:
 * {{{
 *   val groupedAggPandasTestUDF = TestGroupedAggPandasUDF(name = "udf_name")
 *   registerTestUDF(groupedAggPandasTestUDF, spark)
 * }}}
 *
 * To use it in Scala API and SQL:
 * {{{
 *   sql("SELECT udf_name(1)")
 *   val df = Seq(
 *     (536361, "85123A", 2, 17850),
 *     (536362, "85123B", 4, 17850),
 *     (536363, "86123A", 6, 17851)
 *   ).toDF("InvoiceNo", "StockCode", "Quantity", "CustomerID")
 *
 *   df.groupBy("CustomerID").agg(expr("udf_name(Quantity)"))
 *   df.groupBy("CustomerID").agg(groupedAggPandasTestUDF(df("Quantity")))
 * }}}
 */
object IntegratedUDFTestUtils extends SQLHelper {
  import scala.sys.process._

  private[spark] lazy val pythonPath = sys.env.getOrElse("PYTHONPATH", "")

  // Note that we will directly refer pyspark's source, not the zip from a regular build.
  // It is possible the test is being ran without the build.
  private lazy val sourcePath = Paths.get(sparkHome, "python").toAbsolutePath
  private lazy val py4jPath = Paths.get(
    sparkHome, "python", "lib", PythonUtils.PY4J_ZIP_NAME).toAbsolutePath
  private[spark] lazy val pysparkPythonPath = s"$py4jPath:$sourcePath"

  private lazy val isPythonAvailable: Boolean = TestUtils.testCommandAvailable(pythonExec)

  private lazy val isPySparkAvailable: Boolean = isPythonAvailable && Try {
    Process(
      Seq(pythonExec, "-c", "import pyspark"),
      None,
      "PYTHONPATH" -> s"$pysparkPythonPath:$pythonPath").!!
    true
  }.getOrElse(false)

  private lazy val isPandasAvailable: Boolean = isPythonAvailable && isPySparkAvailable && Try {
    Process(
      Seq(
        pythonExec,
        "-c",
        "from pyspark.sql.pandas.utils import require_minimum_pandas_version;" +
          "require_minimum_pandas_version()"),
      None,
      "PYTHONPATH" -> s"$pysparkPythonPath:$pythonPath").!!
    true
  }.getOrElse(false)

  private lazy val isPyArrowAvailable: Boolean = isPythonAvailable && isPySparkAvailable  && Try {
    Process(
      Seq(
        pythonExec,
        "-c",
        "from pyspark.sql.pandas.utils import require_minimum_pyarrow_version;" +
          "require_minimum_pyarrow_version()"),
      None,
      "PYTHONPATH" -> s"$pysparkPythonPath:$pythonPath").!!
    true
  }.getOrElse(false)

  lazy val pythonVer: String = if (isPythonAvailable) {
    Process(
      Seq(pythonExec, "-c", "import sys; print('%d.%d' % sys.version_info[:2])"),
      None,
      "PYTHONPATH" -> s"$pysparkPythonPath:$pythonPath").!!.trim()
  } else {
    throw new RuntimeException(s"Python executable [$pythonExec] is unavailable.")
  }

  lazy val pandasVer: String = if (isPandasAvailable) {
    Process(
      Seq(pythonExec, "-c", "import pandas; print(pandas.__version__)"),
      None,
      "PYTHONPATH" -> s"$pysparkPythonPath:$pythonPath").!!.trim()
  } else {
    throw new RuntimeException("Pandas is unavailable.")
  }

  lazy val pyarrowVer: String = if (isPyArrowAvailable) {
    Process(
      Seq(pythonExec, "-c", "import pyarrow; print(pyarrow.__version__)"),
      None,
      "PYTHONPATH" -> s"$pysparkPythonPath:$pythonPath").!!.trim()
  } else {
    throw new RuntimeException("PyArrow is unavailable.")
  }

  // Dynamically pickles and reads the Python instance into JVM side in order to mimic
  // Python native function within Python UDF.
  private lazy val pythonFunc: Array[Byte] = if (shouldTestPythonUDFs) {
    var binaryPythonFunc: Array[Byte] = null
    withTempPath { path =>
      Process(
        Seq(
          pythonExec,
          "-c",
          "from pyspark.sql.types import StringType; " +
            "from pyspark.serializers import CloudPickleSerializer; " +
            s"f = open('$path', 'wb');" +
            "f.write(CloudPickleSerializer().dumps((" +
            "lambda x: None if x is None else str(x), StringType())))"),
        None,
        "PYTHONPATH" -> s"$pysparkPythonPath:$pythonPath").!!
      binaryPythonFunc = Files.readAllBytes(path.toPath)
    }
    assert(binaryPythonFunc != null)
    binaryPythonFunc
  } else {
    throw new RuntimeException(s"Python executable [$pythonExec] and/or pyspark are unavailable.")
  }

  private def createPythonUDTF(funcName: String, pythonScript: String): Array[Byte] = {
    if (!shouldTestPythonUDFs) {
      throw new RuntimeException(s"Python executable [$pythonExec] and/or pyspark are unavailable.")
    }
    var binaryPythonUDTF: Array[Byte] = null
    withTempPath { codePath =>
      Files.write(codePath.toPath, pythonScript.getBytes(StandardCharsets.UTF_8))
      withTempPath { path =>
        Process(
          Seq(
            pythonExec,
            "-c",
            "from pyspark.serializers import CloudPickleSerializer; " +
              s"f = open('$path', 'wb');" +
              s"exec(open('$codePath', 'r').read());" +
              "f.write(CloudPickleSerializer().dumps(" +
              s"$funcName))"),
          None,
          "PYTHONPATH" -> s"$pysparkPythonPath:$pythonPath").!!
        binaryPythonUDTF = Files.readAllBytes(path.toPath)
      }
    }
    assert(binaryPythonUDTF != null)
    binaryPythonUDTF
  }

  private def createPythonDataSource(dataSourceName: String, pythonScript: String): Array[Byte] = {
    if (!shouldTestPythonUDFs) {
      throw new RuntimeException(s"Python executable [$pythonExec] and/or pyspark are unavailable.")
    }
    var binaryPythonDataSource: Array[Byte] = null
    withTempPath { codePath =>
      Files.write(codePath.toPath, pythonScript.getBytes(StandardCharsets.UTF_8))
      withTempPath { path =>
        Process(
          Seq(
            pythonExec,
            "-c",
            "from pyspark.serializers import CloudPickleSerializer; " +
              s"f = open('$path', 'wb');" +
              s"exec(open('$codePath', 'r').read());" +
              s"dataSourceCls = $dataSourceName;" +
              "f.write(CloudPickleSerializer().dumps(dataSourceCls))"),
          None,
          "PYTHONPATH" -> s"$pysparkPythonPath:$pythonPath").!!
        binaryPythonDataSource = Files.readAllBytes(path.toPath)
      }
    }
    assert(binaryPythonDataSource != null)
    binaryPythonDataSource
  }

  private lazy val pandasFunc: Array[Byte] = if (shouldTestPandasUDFs) {
    var binaryPandasFunc: Array[Byte] = null
    withTempPath { path =>
      Process(
        Seq(
          pythonExec,
          "-c",
          "from pyspark.sql.types import StringType; " +
            "from pyspark.serializers import CloudPickleSerializer; " +
            s"f = open('$path', 'wb');" +
            "f.write(CloudPickleSerializer().dumps((" +
            "lambda x: x.apply(" +
            "lambda v: None if v is None else str(v)), StringType())))"),
        None,
        "PYTHONPATH" -> s"$pysparkPythonPath:$pythonPath").!!
      binaryPandasFunc = Files.readAllBytes(path.toPath)
    }
    assert(binaryPandasFunc != null)
    binaryPandasFunc
  } else {
    throw new RuntimeException(s"Python executable [$pythonExec] and/or pyspark are unavailable.")
  }

  private lazy val pandasGroupedAggFunc: Array[Byte] = if (shouldTestPandasUDFs) {
    var binaryPandasFunc: Array[Byte] = null
    withTempPath { path =>
      Process(
        Seq(
          pythonExec,
          "-c",
          "from pyspark.sql.types import IntegerType; " +
            "from pyspark.serializers import CloudPickleSerializer; " +
            s"f = open('$path', 'wb');" +
            "f.write(CloudPickleSerializer().dumps((" +
            "lambda x: x.agg('count'), IntegerType())))"),
        None,
        "PYTHONPATH" -> s"$pysparkPythonPath:$pythonPath").!!
      binaryPandasFunc = Files.readAllBytes(path.toPath)
    }
    assert(binaryPandasFunc != null)
    binaryPandasFunc
  } else {
    throw new RuntimeException(s"Python executable [$pythonExec] and/or pyspark are unavailable.")
  }

  private def createPandasGroupedMapFuncWithState(pythonScript: String): Array[Byte] = {
    if (shouldTestPandasUDFs) {
      var binaryPandasFunc: Array[Byte] = null
      withTempPath { codePath =>
        Files.write(codePath.toPath, pythonScript.getBytes(StandardCharsets.UTF_8))
        withTempPath { path =>
          Process(
            Seq(
              pythonExec,
              "-c",
              "from pyspark.serializers import CloudPickleSerializer; " +
                s"f = open('$path', 'wb');" +
                s"exec(open('$codePath', 'r').read());" +
                "f.write(CloudPickleSerializer().dumps((" +
                "func, tpe)))"),
            None,
            "PYTHONPATH" -> s"$pysparkPythonPath:$pythonPath").!!
          binaryPandasFunc = Files.readAllBytes(path.toPath)
        }
      }
      assert(binaryPandasFunc != null)
      binaryPandasFunc
    } else {
      throw new RuntimeException(s"Python executable [$pythonExec] and/or pyspark are unavailable.")
    }
  }

  // Make sure this map stays mutable - this map gets updated later in Python runners.
  private val workerEnv = new java.util.HashMap[String, String]()
  workerEnv.put("PYTHONPATH", s"$pysparkPythonPath:$pythonPath")

  lazy val pythonExec: String = {
    val pythonExec = sys.env.getOrElse(
      "PYSPARK_DRIVER_PYTHON", sys.env.getOrElse("PYSPARK_PYTHON", "python3"))
    if (TestUtils.testCommandAvailable(pythonExec)) {
      pythonExec
    } else {
      "python"
    }
  }

  lazy val shouldTestPythonUDFs: Boolean = isPythonAvailable && isPySparkAvailable

  lazy val shouldTestPandasUDFs: Boolean =
    isPythonAvailable && isPandasAvailable && isPyArrowAvailable

  /**
   * A base trait for various UDFs defined in this object.
   */
  sealed trait TestUDF {
    def apply(exprs: Column*): Column

    val prettyName: String
  }

  sealed trait TestUDTF {
    def apply(session: SparkSession, exprs: Column*): DataFrame =
      udtf.apply(session, exprs: _*)

    val name: String = getClass.getSimpleName.stripSuffix("$")
    val pythonScript: String
    lazy val udtf: UserDefinedPythonTableFunction = createUserDefinedPythonTableFunction(
      name = name,
      pythonScript = pythonScript,
      returnType = None)
  }

  class PythonUDFWithoutId(
      name: String,
      func: PythonFunction,
      dataType: DataType,
      children: Seq[Expression],
      evalType: Int,
      udfDeterministic: Boolean,
      resultId: ExprId)
    extends PythonUDF(name, func, dataType, children, evalType, udfDeterministic, resultId) {

    def this(pudf: PythonUDF) = {
      this(pudf.name, pudf.func, pudf.dataType, pudf.children,
        pudf.evalType, pudf.udfDeterministic, pudf.resultId)
    }

    override def toString: String = s"$name(${children.mkString(", ")})"

    override protected def withNewChildrenInternal(
        newChildren: IndexedSeq[Expression]): PythonUDFWithoutId = {
      new PythonUDFWithoutId(super.withNewChildrenInternal(newChildren))
    }
  }

  /**
   * A Python UDF that takes one column, casts into string, executes the Python native function,
   * and casts back to the type of input column.
   *
   * Virtually equivalent to:
   *
   * {{{
   *   from pyspark.sql.functions import udf
   *
   *   df = spark.range(3).toDF("col")
   *   python_udf = udf(lambda x: str(x), "string")
   *   casted_col = python_udf(df.col.cast("string"))
   *   casted_col.cast(df.schema["col"].dataType)
   * }}}
   */
  case class TestPythonUDF(name: String, returnType: Option[DataType] = None) extends TestUDF {
    private[IntegratedUDFTestUtils] lazy val udf = new UserDefinedPythonFunction(
      name = name,
      func = SimplePythonFunction(
        command = pythonFunc.toImmutableArraySeq,
        envVars = workerEnv.clone().asInstanceOf[java.util.Map[String, String]],
        pythonIncludes = List.empty[String].asJava,
        pythonExec = pythonExec,
        pythonVer = pythonVer,
        broadcastVars = List.empty[Broadcast[PythonBroadcast]].asJava,
        accumulator = null),
      dataType = StringType,
      pythonEvalType = PythonEvalType.SQL_BATCHED_UDF,
      udfDeterministic = true) {

      override def builder(e: Seq[Expression]): Expression = {
        assert(e.length == 1, "Defined UDF only has one column")
        val expr = e.head
        val rt = returnType.getOrElse {
          assert(expr.resolved, "column should be resolved to use the same type " +
              "as input. Try df(name) or df.col(name)")
          expr.dataType
        }
        val pythonUDF = new PythonUDFWithoutId(
          super.builder(Cast(expr, StringType) :: Nil).asInstanceOf[PythonUDF])
        Cast(pythonUDF, rt)
      }
    }

    def apply(exprs: Column*): Column = udf(exprs: _*)

    val prettyName: String = "Regular Python UDF"
  }

  def createUserDefinedPythonDataSource(
      name: String,
      pythonScript: String): UserDefinedPythonDataSource = {
    UserDefinedPythonDataSource(
      dataSourceCls = SimplePythonFunction(
        command = createPythonDataSource(name, pythonScript).toImmutableArraySeq,
        envVars = workerEnv.clone().asInstanceOf[java.util.Map[String, String]],
        pythonIncludes = List.empty[String].asJava,
        pythonExec = pythonExec,
        pythonVer = pythonVer,
        broadcastVars = List.empty[Broadcast[PythonBroadcast]].asJava,
        accumulator = null))
  }

  def createUserDefinedPythonTableFunction(
      name: String,
      pythonScript: String,
      returnType: Option[StructType],
      evalType: Int = PythonEvalType.SQL_TABLE_UDF,
      deterministic: Boolean = false): UserDefinedPythonTableFunction = {
    UserDefinedPythonTableFunction(
      name = name,
      func = SimplePythonFunction(
        command = createPythonUDTF(name, pythonScript).toImmutableArraySeq,
        envVars = workerEnv.clone().asInstanceOf[java.util.Map[String, String]],
        pythonIncludes = List.empty[String].asJava,
        pythonExec = pythonExec,
        pythonVer = pythonVer,
        broadcastVars = List.empty[Broadcast[PythonBroadcast]].asJava,
        accumulator = null),
      returnType = returnType,
      pythonEvalType = evalType,
      udfDeterministic = deterministic)
  }

  case class TestPythonUDTF(override val name: String) extends TestUDTF {
    val pythonScript: String =
      """
        |class TestUDTF:
        |    def eval(self, a: int, b: int):
        |        if a > 0 and b > 0:
        |            yield a, a - b
        |            yield b, b - a
        |        elif a == 0 and b == 0:
        |            yield 0, 0
        |        else:
        |            ...
        |""".stripMargin

    override lazy val udtf: UserDefinedPythonTableFunction = createUserDefinedPythonTableFunction(
      name = "TestUDTF",
      pythonScript = pythonScript,
      returnType = Some(StructType.fromDDL("x int, y int"))
    )
  }

  object UDTFCountSumLast extends TestUDTF {
    val pythonScript: String =
      s"""
         |from pyspark.sql.functions import AnalyzeResult, OrderingColumn, PartitioningColumn
         |from pyspark.sql.types import IntegerType, Row, StructType
         |class $name:
         |    def __init__(self):
         |        self._count = 0
         |        self._sum = 0
         |        self._last = None
         |
         |    @staticmethod
         |    def analyze(row: Row):
         |        return AnalyzeResult(
         |            schema=StructType()
         |                .add("count", IntegerType())
         |                .add("total", IntegerType())
         |                .add("last", IntegerType()))
         |
         |    def eval(self, row: Row):
         |        self._count += 1
         |        self._last = row["input"]
         |        self._sum += row["input"]
         |
         |    def terminate(self):
         |        yield self._count, self._sum, self._last
         |""".stripMargin
  }

  object UDTFLastString extends TestUDTF {
    val pythonScript: String =
      s"""
         |from pyspark.sql.functions import AnalyzeResult
         |from pyspark.sql.types import Row, StringType, StructType
         |class $name:
         |    def __init__(self):
         |        self._last = ""
         |
         |    @staticmethod
         |    def analyze(row: Row):
         |        return AnalyzeResult(
         |            schema=StructType()
         |                .add("last", StringType()))
         |
         |    def eval(self, row: Row):
         |        self._last = row["input"]
         |
         |    def terminate(self):
         |        yield self._last,
         |""".stripMargin
  }

  object UDTFWithSinglePartition extends TestUDTF {
    val pythonScript: String =
      s"""
        |import json
        |from dataclasses import dataclass
        |from pyspark.sql.functions import AnalyzeResult, OrderingColumn, PartitioningColumn
        |from pyspark.sql.types import IntegerType, Row, StructType
        |
        |@dataclass
        |class AnalyzeResultWithBuffer(AnalyzeResult):
        |    buffer: str = ""
        |
        |class $name:
        |    def __init__(self):
        |        self._count = 0
        |        self._sum = 0
        |        self._last = None
        |
        |    @staticmethod
        |    def analyze(initial_count, input_table):
        |        buffer = ""
        |        if initial_count.value is not None:
        |            assert(not initial_count.isTable)
        |            assert(initial_count.dataType == IntegerType())
        |            count = initial_count.value
        |            buffer = json.dumps({"initial_count": count})
        |        return AnalyzeResultWithBuffer(
        |            schema=StructType()
        |                .add("count", IntegerType())
        |                .add("total", IntegerType())
        |                .add("last", IntegerType()),
        |            withSinglePartition=True,
        |            orderBy=[
        |                OrderingColumn("input"),
        |                OrderingColumn("partition_col")],
        |            buffer=buffer)
        |
        |    def eval(self, initial_count, row):
        |        self._count += 1
        |        self._last = row["input"]
        |        self._sum += row["input"]
        |
        |    def terminate(self):
        |        yield self._count, self._sum, self._last
        |""".stripMargin
  }

  object UDTFPartitionByOrderBy extends TestUDTF {
    val pythonScript: String =
      s"""
        |from pyspark.sql.functions import AnalyzeResult, OrderingColumn, PartitioningColumn
        |from pyspark.sql.types import IntegerType, Row, StructType
        |class $name:
        |    def __init__(self):
        |        self._partition_col = None
        |        self._count = 0
        |        self._sum = 0
        |        self._last = None
        |
        |    @staticmethod
        |    def analyze(row: Row):
        |        return AnalyzeResult(
        |            schema=StructType()
        |                .add("partition_col", IntegerType())
        |                .add("count", IntegerType())
        |                .add("total", IntegerType())
        |                .add("last", IntegerType()),
        |            partitionBy=[
        |                PartitioningColumn("partition_col")
        |            ],
        |            orderBy=[
        |                OrderingColumn("input")
        |            ])
        |
        |    def eval(self, row: Row):
        |        self._partition_col = row["partition_col"]
        |        self._count += 1
        |        self._last = row["input"]
        |        self._sum += row["input"]
        |
        |    def terminate(self):
        |        yield self._partition_col, self._count, self._sum, self._last
        |""".stripMargin
  }

  object UDTFInvalidPartitionByAndWithSinglePartition extends TestUDTF {
    val pythonScript: String =
      s"""
         |from pyspark.sql.functions import AnalyzeResult, OrderingColumn, PartitioningColumn
         |from pyspark.sql.types import IntegerType, Row, StructType
         |class $name:
         |    def __init__(self):
         |        self._last = None
         |
         |    @staticmethod
         |    def analyze(row: Row):
         |        return AnalyzeResult(
         |            schema=StructType()
         |                .add("last", IntegerType()),
         |            withSinglePartition=True,
         |            partitionBy=[
         |                PartitioningColumn("partition_col")
         |            ])
         |
         |    def eval(self, row: Row):
         |        self._last = row["input"]
         |
         |    def terminate(self):
         |        yield self._last,
         |""".stripMargin
  }

  object UDTFInvalidOrderByWithoutPartitionBy extends TestUDTF {
    val pythonScript: String =
      s"""
         |from pyspark.sql.functions import AnalyzeResult, OrderingColumn, PartitioningColumn
         |from pyspark.sql.types import IntegerType, Row, StructType
         |class $name:
         |    def __init__(self):
         |        self._last = None
         |
         |    @staticmethod
         |    def analyze(row: Row):
         |        return AnalyzeResult(
         |            schema=StructType()
         |                .add("last", IntegerType()),
         |            orderBy=[
         |                OrderingColumn("input")
         |            ])
         |
         |    def eval(self, row: Row):
         |        self._last = row["input"]
         |
         |    def terminate(self):
         |        yield self._last,
         |""".stripMargin
  }

  object UDTFForwardStateFromAnalyze extends TestUDTF {
    val pythonScript: String =
      s"""
         |from dataclasses import dataclass
         |from pyspark.sql.functions import AnalyzeResult
         |from pyspark.sql.types import StringType, StructType
         |
         |@dataclass
         |class AnalyzeResultWithBuffer(AnalyzeResult):
         |    buffer: str = ""
         |
         |class $name:
         |    def __init__(self, analyze_result):
         |        self._analyze_result = analyze_result
         |
         |    @staticmethod
         |    def analyze(argument):
         |        assert(argument.dataType == StringType())
         |        return AnalyzeResultWithBuffer(
         |            schema=StructType()
         |                .add("result", StringType()),
         |            buffer=argument.value)
         |
         |    def eval(self, argument):
         |        pass
         |
         |    def terminate(self):
         |        yield self._analyze_result.buffer,
         |""".stripMargin
  }

  object UDTFForwardStateFromAnalyzeWithKwargs extends TestUDTF {
    val pythonScript: String =
      s"""
         |from dataclasses import dataclass
         |from pyspark.sql.functions import AnalyzeResult
         |from pyspark.sql.types import StringType, StructType
         |
         |@dataclass
         |class AnalyzeResultWithBuffer(AnalyzeResult):
         |    buffer: str = ""
         |
         |class $name:
         |    def __init__(self, analyze_result):
         |        self._analyze_result = analyze_result
         |
         |    @staticmethod
         |    def analyze(**kwargs):
         |        argument = kwargs.get("argument")
         |        if argument is not None:
         |            assert(argument.dataType == StringType())
         |            argument_value = argument.value
         |        else:
         |            argument_value = None
         |        return AnalyzeResultWithBuffer(
         |            schema=StructType()
         |                .add("result", StringType()),
         |            buffer=argument_value)
         |
         |    def eval(self, argument: str):
         |        pass
         |
         |    def terminate(self):
         |        yield self._analyze_result.buffer,
         |""".stripMargin
  }

  object InvalidAnalyzeMethodReturnsNonStructTypeSchema extends TestUDTF {
    val pythonScript: String =
      s"""
         |from dataclasses import dataclass
         |from pyspark.sql.functions import AnalyzeResult
         |from pyspark.sql.types import StringType, StructType
         |
         |class $name:
         |    @staticmethod
         |    def analyze(argument):
         |        return AnalyzeResult(
         |            schema=42)
         |
         |    def eval(self, argument):
         |        pass
         |
         |    def terminate(self):
         |        yield 42,
         |""".stripMargin
  }

  object InvalidAnalyzeMethodWithSinglePartitionNoInputTable extends TestUDTF {
    val pythonScript: String =
      s"""
         |from dataclasses import dataclass
         |from pyspark.sql.functions import AnalyzeResult
         |from pyspark.sql.types import StringType, StructType
         |
         |class $name:
         |    @staticmethod
         |    def analyze(**wkargs):
         |        return AnalyzeResult(
         |            schema=StructType()
         |                .add("result", StringType()),
         |            withSinglePartition=True)
         |
         |    def eval(self, argument):
         |        pass
         |
         |    def terminate(self):
         |        yield 42,
         |""".stripMargin
  }

  object InvalidAnalyzeMethodWithPartitionByNoInputTable extends TestUDTF {
    val pythonScript: String =
      s"""
         |from dataclasses import dataclass
         |from pyspark.sql.functions import AnalyzeResult, PartitioningColumn
         |from pyspark.sql.types import StringType, StructType
         |
         |class $name:
         |    @staticmethod
         |    def analyze(**wkargs):
         |        return AnalyzeResult(
         |            schema=StructType()
         |                .add("result", StringType()),
         |            partitionBy=[
         |                PartitioningColumn("partition_col")
         |            ])
         |
         |    def eval(self, argument):
         |        pass
         |
         |    def terminate(self):
         |        yield 42,
         |""".stripMargin
  }

  object InvalidAnalyzeMethodWithPartitionByListOfStrings extends TestUDTF {
    val pythonScript: String =
      s"""
         |from dataclasses import dataclass
         |from pyspark.sql.functions import AnalyzeResult, PartitioningColumn
         |from pyspark.sql.types import StringType, StructType
         |
         |class $name:
         |    @staticmethod
         |    def analyze(**wkargs):
         |        return AnalyzeResult(
         |            schema=StructType()
         |                .add("result", StringType()),
         |            partitionBy=[
         |                "partition_col"
         |            ])
         |
         |    def eval(self, argument):
         |        pass
         |
         |    def terminate(self):
         |        yield 42,
         |""".stripMargin
  }

  object InvalidForwardStateFromAnalyzeTooManyInitArgs extends TestUDTF {
    val pythonScript: String =
      s"""
         |from dataclasses import dataclass
         |from pyspark.sql.functions import AnalyzeResult
         |from pyspark.sql.types import StringType, StructType
         |
         |@dataclass
         |class AnalyzeResultWithBuffer(AnalyzeResult):
         |    buffer: str = ""
         |
         |class $name:
         |    def __init__(self, analyze_result, other_argument):
         |        self._analyze_result = analyze_result
         |
         |    @staticmethod
         |    def analyze(argument):
         |        return AnalyzeResultWithBuffer(
         |            schema=StructType()
         |                .add("result", StringType()),
         |            buffer=argument.value)
         |
         |    def eval(self, argument):
         |        pass
         |
         |    def terminate(self):
         |        yield self._analyze_result.buffer,
         |""".stripMargin
  }

  object InvalidNotForwardStateFromAnalyzeTooManyInitArgs extends TestUDTF {
    val pythonScript: String =
      s"""
         |class $name:
         |    def __init__(self, other_argument):
         |        pass
         |
         |    def eval(self, argument):
         |        pass
         |
         |    def terminate(self):
         |        yield 'abc',
         |""".stripMargin

    override lazy val udtf: UserDefinedPythonTableFunction = createUserDefinedPythonTableFunction(
      name = name,
      pythonScript = pythonScript,
      returnType = Some(StructType.fromDDL("result string"))
    )
  }

  object InvalidEvalReturnsNoneToNonNullableColumnScalarType extends TestUDTF {
    val pythonScript: String =
      s"""
         |from pyspark.sql.functions import AnalyzeResult
         |from pyspark.sql.types import StringType, StructType
         |
         |class $name:
         |    def __init__(self, analyze_result):
         |        self._analyze_result = analyze_result
         |
         |    @staticmethod
         |    def analyze(*args):
         |        return AnalyzeResult(
         |            schema=StructType()
         |                .add("result", StringType(), False)
         |            )
         |
         |    def eval(self, *args):
         |        yield None,
         |""".stripMargin
  }

  object InvalidEvalReturnsNoneToNonNullableColumnArrayType extends TestUDTF {
    val pythonScript: String =
      s"""
         |from pyspark.sql.functions import AnalyzeResult
         |from pyspark.sql.types import ArrayType, IntegerType, StringType, StructType
         |
         |class $name:
         |    def __init__(self, analyze_result):
         |        self._analyze_result = analyze_result
         |
         |    @staticmethod
         |    def analyze(*args):
         |        return AnalyzeResult(
         |            schema=StructType()
         |                .add("result", ArrayType(IntegerType(), containsNull=True), False)
         |            )
         |
         |    def eval(self, *args):
         |        yield None,
         |""".stripMargin
  }

  object InvalidEvalReturnsNoneToNonNullableColumnArrayElementType extends TestUDTF {
    val pythonScript: String =
      s"""
         |from pyspark.sql.functions import AnalyzeResult
         |from pyspark.sql.types import ArrayType, IntegerType, StringType, StructType
         |
         |class $name:
         |    def __init__(self, analyze_result):
         |        self._analyze_result = analyze_result
         |
         |    @staticmethod
         |    def analyze(*args):
         |        return AnalyzeResult(
         |            schema=StructType()
         |                .add("result", ArrayType(IntegerType(), containsNull=False), True)
         |            )
         |
         |    def eval(self, *args):
         |        yield [1, 2, None, 3],
         |""".stripMargin
  }

  object InvalidEvalReturnsNoneToNonNullableColumnStructType extends TestUDTF {
    val pythonScript: String =
      s"""
         |from pyspark.sql.functions import AnalyzeResult
         |from pyspark.sql.types import IntegerType, Row, StringType, StructType
         |
         |class $name:
         |    def __init__(self, analyze_result):
         |        self._analyze_result = analyze_result
         |
         |    @staticmethod
         |    def analyze(*args):
         |        return AnalyzeResult(
         |            schema=StructType()
         |                .add("result", StructType().add("field", IntegerType(), False), True)
         |            )
         |
         |    def eval(self, *args):
         |        yield Row(field=None),
         |""".stripMargin
  }

  object InvalidEvalReturnsNoneToNonNullableColumnMapType extends TestUDTF {
    val pythonScript: String =
      s"""
         |from pyspark.sql.functions import AnalyzeResult
         |from pyspark.sql.types import IntegerType, MapType, StringType, StructType
         |
         |class $name:
         |    def __init__(self, analyze_result):
         |        self._analyze_result = analyze_result
         |
         |    @staticmethod
         |    def analyze(*args):
         |        return AnalyzeResult(
         |            schema=StructType()
         |                .add("result", MapType(IntegerType(), StringType(), False), True)
         |            )
         |
         |    def eval(self, *args):
         |        yield {42: None},
         |""".stripMargin
  }

  object InvalidTerminateReturnsNoneToNonNullableColumnScalarType extends TestUDTF {
    val pythonScript: String =
      s"""
         |from pyspark.sql.functions import AnalyzeResult
         |from pyspark.sql.types import StringType, StructType
         |
         |class $name:
         |    def __init__(self, analyze_result):
         |        self._analyze_result = analyze_result
         |
         |    @staticmethod
         |    def analyze(*args):
         |        return AnalyzeResult(
         |            schema=StructType()
         |                .add("result", StringType(), False)
         |            )
         |
         |    def eval(self, *args):
         |        yield 'abc',
         |
         |    def terminate(self):
         |        yield None,
         |""".stripMargin
  }

  object InvalidTerminateReturnsNoneToNonNullableColumnArrayType extends TestUDTF {
    val pythonScript: String =
      s"""
         |from pyspark.sql.functions import AnalyzeResult
         |from pyspark.sql.types import ArrayType, IntegerType, StringType, StructType
         |
         |class $name:
         |    def __init__(self, analyze_result):
         |        self._analyze_result = analyze_result
         |
         |    @staticmethod
         |    def analyze(*args):
         |        return AnalyzeResult(
         |            schema=StructType()
         |                .add("result", ArrayType(IntegerType(), containsNull=True), False)
         |            )
         |
         |    def eval(self, *args):
         |        yield [1, 2, 3, 4],
         |
         |    def terminate(self):
         |        yield None,
         |""".stripMargin
  }

  object InvalidTerminateReturnsNoneToNonNullableColumnArrayElementType extends TestUDTF {
    val pythonScript: String =
      s"""
         |from pyspark.sql.functions import AnalyzeResult
         |from pyspark.sql.types import ArrayType, IntegerType, StringType, StructType
         |
         |class $name:
         |    def __init__(self, analyze_result):
         |        self._analyze_result = analyze_result
         |
         |    @staticmethod
         |    def analyze(*args):
         |        return AnalyzeResult(
         |            schema=StructType()
         |                .add("result", ArrayType(IntegerType(), containsNull=False), True)
         |            )
         |
         |    def eval(self, *args):
         |        yield [1, 2, 3, 4],
         |
         |    def terminate(self):
         |        yield [1, 2, None, 3],
         |""".stripMargin
  }

  object InvalidTerminateReturnsNoneToNonNullableColumnStructType extends TestUDTF {
    val pythonScript: String =
      s"""
         |from pyspark.sql.functions import AnalyzeResult
         |from pyspark.sql.types import IntegerType, Row, StringType, StructType
         |
         |class $name:
         |    def __init__(self, analyze_result):
         |        self._analyze_result = analyze_result
         |
         |    @staticmethod
         |    def analyze(*args):
         |        return AnalyzeResult(
         |            schema=StructType()
         |                .add("result", StructType().add("field", IntegerType(), False), True)
         |            )
         |
         |    def eval(self, *args):
         |       yield Row(field=42),
         |
         |    def terminate(self):
         |       yield Row(field=None),
         |""".stripMargin
  }

  object InvalidTerminateReturnsNoneToNonNullableColumnMapType extends TestUDTF {
    val pythonScript: String =
      s"""
         |from pyspark.sql.functions import AnalyzeResult
         |from pyspark.sql.types import IntegerType, MapType, StringType, StructType
         |
         |class $name:
         |    def __init__(self, analyze_result):
         |        self._analyze_result = analyze_result
         |
         |    @staticmethod
         |    def analyze(*args):
         |        return AnalyzeResult(
         |            schema=StructType()
         |                .add("result", MapType(IntegerType(), StringType(), False), True)
         |            )
         |
         |    def eval(self, *args):
         |       yield {42: 'abc'},
         |
         |    def terminate(self):
         |        yield {42: None},
         |""".stripMargin
  }

  def AllTestUDTFs: Seq[TestUDTF] = Seq(
    TestPythonUDTF("udtf"),
    UDTFCountSumLast,
    UDTFLastString,
    UDTFWithSinglePartition,
    UDTFPartitionByOrderBy,
    UDTFInvalidPartitionByAndWithSinglePartition,
    UDTFInvalidOrderByWithoutPartitionBy,
    UDTFForwardStateFromAnalyze,
    UDTFForwardStateFromAnalyzeWithKwargs,
    InvalidAnalyzeMethodReturnsNonStructTypeSchema,
    InvalidAnalyzeMethodWithSinglePartitionNoInputTable,
    InvalidAnalyzeMethodWithPartitionByNoInputTable,
    InvalidAnalyzeMethodWithPartitionByListOfStrings,
    InvalidForwardStateFromAnalyzeTooManyInitArgs,
    InvalidNotForwardStateFromAnalyzeTooManyInitArgs,
    InvalidEvalReturnsNoneToNonNullableColumnScalarType,
    InvalidEvalReturnsNoneToNonNullableColumnArrayType,
    InvalidEvalReturnsNoneToNonNullableColumnArrayElementType,
    InvalidEvalReturnsNoneToNonNullableColumnStructType,
    InvalidEvalReturnsNoneToNonNullableColumnMapType,
    InvalidTerminateReturnsNoneToNonNullableColumnScalarType,
    InvalidTerminateReturnsNoneToNonNullableColumnArrayType,
    InvalidTerminateReturnsNoneToNonNullableColumnArrayElementType,
    InvalidTerminateReturnsNoneToNonNullableColumnStructType,
    InvalidTerminateReturnsNoneToNonNullableColumnMapType
  )

  /**
   * A Scalar Pandas UDF that takes one column, casts into string, executes the
   * Python native function, and casts back to the type of input column.
   *
   * Virtually equivalent to:
   *
   * {{{
   *   from pyspark.sql.functions import pandas_udf
   *
   *   df = spark.range(3).toDF("col")
   *   scalar_udf = pandas_udf(lambda x: x.apply(lambda v: str(v)), "string")
   *   casted_col = scalar_udf(df.col.cast("string"))
   *   casted_col.cast(df.schema["col"].dataType)
   * }}}
   */
  case class TestScalarPandasUDF(
      name: String,
      returnType: Option[DataType] = None) extends TestUDF {
    private[IntegratedUDFTestUtils] lazy val udf = new UserDefinedPythonFunction(
      name = name,
      func = SimplePythonFunction(
        command = pandasFunc.toImmutableArraySeq,
        envVars = workerEnv.clone().asInstanceOf[java.util.Map[String, String]],
        pythonIncludes = List.empty[String].asJava,
        pythonExec = pythonExec,
        pythonVer = pythonVer,
        broadcastVars = List.empty[Broadcast[PythonBroadcast]].asJava,
        accumulator = null),
      dataType = StringType,
      pythonEvalType = PythonEvalType.SQL_SCALAR_PANDAS_UDF,
      udfDeterministic = true) {

      override def builder(e: Seq[Expression]): Expression = {
        assert(e.length == 1, "Defined UDF only has one column")
        val expr = e.head
        val rt = returnType.getOrElse {
          assert(expr.resolved, "column should be resolved to use the same type " +
            "as input. Try df(name) or df.col(name)")
          expr.dataType
        }
        val pythonUDF = new PythonUDFWithoutId(
          super.builder(Cast(expr, StringType) :: Nil).asInstanceOf[PythonUDF])
        Cast(pythonUDF, rt)
      }
    }

    def apply(exprs: Column*): Column = udf(exprs: _*)

    val prettyName: String = "Scalar Pandas UDF"
  }

  /**
   * A Grouped Aggregate Pandas UDF that takes one column, executes the
   * Python native function calculating the count of the column using pandas.
   *
   * Virtually equivalent to:
   *
   * {{{
   *   import pandas as pd
   *   from pyspark.sql.functions import pandas_udf
   *
   *   df = spark.createDataFrame(
   *       [(1, 1.0), (1, 2.0), (2, 3.0), (2, 5.0), (2, 10.0)], ("id", "v"))
   *
   *   @pandas_udf("double")
   *   def pandas_count(v: pd.Series) -> int:
   *       return v.count()
   *
   *   count_col = pandas_count(df['v'])
   * }}}
   */
  case class TestGroupedAggPandasUDF(name: String) extends TestUDF {
    private[IntegratedUDFTestUtils] lazy val udf = new UserDefinedPythonFunction(
      name = name,
      func = SimplePythonFunction(
        command = pandasGroupedAggFunc.toImmutableArraySeq,
        envVars = workerEnv.clone().asInstanceOf[java.util.Map[String, String]],
        pythonIncludes = List.empty[String].asJava,
        pythonExec = pythonExec,
        pythonVer = pythonVer,
        broadcastVars = List.empty[Broadcast[PythonBroadcast]].asJava,
        accumulator = null),
      dataType = IntegerType,
      pythonEvalType = PythonEvalType.SQL_GROUPED_AGG_PANDAS_UDF,
      udfDeterministic = true)

    def apply(exprs: Column*): Column = udf(exprs: _*)

    val prettyName: String = "Grouped Aggregate Pandas UDF"
  }

  /**
   * Arbitrary stateful processing in Python is used for
   * `DataFrame.groupBy.applyInPandasWithState`. It requires `pythonScript` to
   * define `func` (Python function) and `tpe` (`StructType` for state key).
   *
   * Virtually equivalent to:
   *
   * {{{
   *   # exec defines 'func' and 'tpe' (struct type for state key)
   *   exec(pythonScript)
   *
   *   # ... are filled when this UDF is invoked, see also 'PythonFlatMapGroupsWithStateSuite'.
   *   df.groupBy(...).applyInPandasWithState(func, ..., tpe, ..., ...)
   * }}}
   */
  case class TestGroupedMapPandasUDFWithState(name: String, pythonScript: String) extends TestUDF {
    private[IntegratedUDFTestUtils] lazy val udf = new UserDefinedPythonFunction(
      name = name,
      func = SimplePythonFunction(
        command = createPandasGroupedMapFuncWithState(pythonScript).toImmutableArraySeq,
        envVars = workerEnv.clone().asInstanceOf[java.util.Map[String, String]],
        pythonIncludes = List.empty[String].asJava,
        pythonExec = pythonExec,
        pythonVer = pythonVer,
        broadcastVars = List.empty[Broadcast[PythonBroadcast]].asJava,
        accumulator = null),
      dataType = NullType,  // This is not respected.
      pythonEvalType = PythonEvalType.SQL_GROUPED_MAP_PANDAS_UDF_WITH_STATE,
      udfDeterministic = true)

    def apply(exprs: Column*): Column = udf(exprs: _*)

    val prettyName: String = "Grouped Map Pandas UDF with State"
  }

  /**
   * A Scala UDF that takes one column, casts into string, executes the
   * Scala native function, and casts back to the type of input column.
   *
   * Virtually equivalent to:
   *
   * {{{
   *   import org.apache.spark.sql.functions.udf
   *
   *   val df = spark.range(3).toDF("col")
   *   val scala_udf = udf((input: Any) => input.toString)
   *   val casted_col = scala_udf(df.col("col").cast("string"))
   *   casted_col.cast(df.schema("col").dataType)
   * }}}
   */
  class TestInternalScalaUDF(
      name: String,
      returnType: Option[DataType] = None) extends SparkUserDefinedFunction(
    (input: Any) => if (input == null) {
      null
    } else {
      input.toString
    },
    StringType,
    inputEncoders = Seq.fill(1)(None),
    name = Some(name)) {

    override def apply(exprs: Column*): Column = {
      assert(exprs.length == 1, "Defined UDF only has one column")
      val expr = exprs.head.expr
      val rt = returnType.getOrElse {
        assert(expr.resolved, "column should be resolved to use the same type " +
            "as input. Try df(name) or df.col(name)")
        expr.dataType
      }
      Column(Cast(createScalaUDF(Cast(expr, StringType) :: Nil), rt))
    }

    override def withName(name: String): TestInternalScalaUDF = {
      // "withName" should overridden to return TestInternalScalaUDF. Otherwise, the current object
      // is sliced and the overridden "apply" is not invoked.
      new TestInternalScalaUDF(name)
    }
  }

  case class TestScalaUDF(name: String, returnType: Option[DataType] = None) extends TestUDF {
    private[IntegratedUDFTestUtils] lazy val udf = new TestInternalScalaUDF(name, returnType)

    def apply(exprs: Column*): Column = udf(exprs: _*)

    val prettyName: String = "Scala UDF"
  }

  /**
   * Register UDFs used in this test case.
   */
  def registerTestUDF(testUDF: TestUDF, session: SparkSession): Unit = testUDF match {
    case udf: TestPythonUDF => session.udf.registerPython(udf.name, udf.udf)
    case udf: TestScalarPandasUDF => session.udf.registerPython(udf.name, udf.udf)
    case udf: TestGroupedAggPandasUDF => session.udf.registerPython(udf.name, udf.udf)
    case udf: TestScalaUDF => session.udf.register(udf.name, udf.udf)
    case other => throw new RuntimeException(s"Unknown UDF class [${other.getClass}]")
  }

  /**
   * Register UDTFs used in the test cases.
   */
  case class TestUDTFSet(udtfs: Seq[TestUDTF])
  def registerTestUDTFs(testUDTFSet: TestUDTFSet, session: SparkSession): Unit = {
    testUDTFSet.udtfs.foreach {
      _ match {
        case udtf: TestUDTF => session.udtf.registerPython(udtf.name, udtf.udtf)
        case other => throw new RuntimeException(s"Unknown UDTF class [${other.getClass}]")
      }
    }
  }
}
