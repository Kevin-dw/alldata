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

package org.apache.spark.sql.avro

import org.apache.avro.Conversions.DecimalConversion
import org.apache.avro.LogicalTypes.{TimestampMicros, TimestampMillis}
import org.apache.avro.Schema.Type._
import org.apache.avro.generic._
import org.apache.avro.util.Utf8
import org.apache.avro.{LogicalTypes, Schema, SchemaBuilder}
import org.apache.spark.sql.avro.AvroDeserializer.{createDateRebaseFuncInRead, createTimestampRebaseFuncInRead}
import org.apache.spark.sql.catalyst.expressions.{SpecificInternalRow, UnsafeArrayData}
import org.apache.spark.sql.catalyst.util.DateTimeConstants.MILLIS_PER_DAY
import org.apache.spark.sql.catalyst.util._
import org.apache.spark.sql.catalyst.{InternalRow, NoopFilters, StructFilters}
import org.apache.spark.sql.execution.datasources.DataSourceUtils
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.internal.SQLConf.LegacyBehaviorPolicy
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String

import java.math.BigDecimal
import java.nio.ByteBuffer
import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

/**
 * A deserializer to deserialize data in avro format to data in catalyst format.
 *
 * NOTE: This code is borrowed from Spark 3.1.2
 *       This code is borrowed, so that we can better control compatibility w/in Spark minor
 *       branches (3.2.x, 3.1.x, etc)
 *
 *       PLEASE REFRAIN MAKING ANY CHANGES TO THIS CODE UNLESS ABSOLUTELY NECESSARY
 */
private[sql] class AvroDeserializer(rootAvroType: Schema,
                                    rootCatalystType: DataType,
                                    datetimeRebaseMode: LegacyBehaviorPolicy.Value,
                                    filters: StructFilters) {

  def this(rootAvroType: Schema, rootCatalystType: DataType) = {
    this(
      rootAvroType,
      rootCatalystType,
      LegacyBehaviorPolicy.withName(SQLConf.get.getConf(SQLConf.LEGACY_AVRO_REBASE_MODE_IN_READ)),
      new NoopFilters)
  }

  private lazy val decimalConversions = new DecimalConversion()

  private val dateRebaseFunc = createDateRebaseFuncInRead(
    datetimeRebaseMode, "Avro")

  private val timestampRebaseFunc = createTimestampRebaseFuncInRead(
    datetimeRebaseMode, "Avro")

  private val converter: Any => Option[Any] = rootCatalystType match {
    // A shortcut for empty schema.
    case st: StructType if st.isEmpty =>
      (data: Any) => Some(InternalRow.empty)

    case st: StructType =>
      val resultRow = new SpecificInternalRow(st.map(_.dataType))
      val fieldUpdater = new RowUpdater(resultRow)
      val applyFilters = filters.skipRow(resultRow, _)
      val writer = getRecordWriter(rootAvroType, st, Nil, applyFilters)
      (data: Any) => {
        val record = data.asInstanceOf[GenericRecord]
        val skipRow = writer(fieldUpdater, record)
        if (skipRow) None else Some(resultRow)
      }

    case _ =>
      val tmpRow = new SpecificInternalRow(Seq(rootCatalystType))
      val fieldUpdater = new RowUpdater(tmpRow)
      val writer = newWriter(rootAvroType, rootCatalystType, Nil)
      (data: Any) => {
        writer(fieldUpdater, 0, data)
        Some(tmpRow.get(0, rootCatalystType))
      }
  }

  def deserialize(data: Any): Option[Any] = converter(data)

  /**
   * Creates a writer to write avro values to Catalyst values at the given ordinal with the given
   * updater.
   */
  private def newWriter(avroType: Schema,
                        catalystType: DataType,
                        path: List[String]): (CatalystDataUpdater, Int, Any) => Unit =
    (avroType.getType, catalystType) match {
      case (NULL, NullType) => (updater, ordinal, _) =>
        updater.setNullAt(ordinal)

      // TODO: we can avoid boxing if future version of avro provide primitive accessors.
      case (BOOLEAN, BooleanType) => (updater, ordinal, value) =>
        updater.setBoolean(ordinal, value.asInstanceOf[Boolean])

      case (INT, IntegerType) => (updater, ordinal, value) =>
        updater.setInt(ordinal, value.asInstanceOf[Int])

      case (INT, DateType) => (updater, ordinal, value) =>
        updater.setInt(ordinal, dateRebaseFunc(value.asInstanceOf[Int]))

      case (LONG, LongType) => (updater, ordinal, value) =>
        updater.setLong(ordinal, value.asInstanceOf[Long])

      case (LONG, TimestampType) => avroType.getLogicalType match {
        // For backward compatibility, if the Avro type is Long and it is not logical type
        // (the `null` case), the value is processed as timestamp type with millisecond precision.
        case null | _: TimestampMillis => (updater, ordinal, value) =>
          val millis = value.asInstanceOf[Long]
          val micros = DateTimeUtils.millisToMicros(millis)
          updater.setLong(ordinal, timestampRebaseFunc(micros))
        case _: TimestampMicros => (updater, ordinal, value) =>
          val micros = value.asInstanceOf[Long]
          updater.setLong(ordinal, timestampRebaseFunc(micros))
        case other => throw new IncompatibleSchemaException(
          s"Cannot convert Avro logical type ${other} to Catalyst Timestamp type.")
      }

      // Before we upgrade Avro to 1.8 for logical type support, spark-avro converts Long to Date.
      // For backward compatibility, we still keep this conversion.
      case (LONG, DateType) => (updater, ordinal, value) =>
        updater.setInt(ordinal, (value.asInstanceOf[Long] / MILLIS_PER_DAY).toInt)

      case (FLOAT, FloatType) => (updater, ordinal, value) =>
        updater.setFloat(ordinal, value.asInstanceOf[Float])

      case (DOUBLE, DoubleType) => (updater, ordinal, value) =>
        updater.setDouble(ordinal, value.asInstanceOf[Double])

      case (STRING, StringType) => (updater, ordinal, value) =>
        val str = value match {
          case s: String => UTF8String.fromString(s)
          case s: Utf8 =>
            val bytes = new Array[Byte](s.getByteLength)
            System.arraycopy(s.getBytes, 0, bytes, 0, s.getByteLength)
            UTF8String.fromBytes(bytes)
        }
        updater.set(ordinal, str)

      case (ENUM, StringType) => (updater, ordinal, value) =>
        updater.set(ordinal, UTF8String.fromString(value.toString))

      case (FIXED, BinaryType) => (updater, ordinal, value) =>
        updater.set(ordinal, value.asInstanceOf[GenericFixed].bytes().clone())

      case (BYTES, BinaryType) => (updater, ordinal, value) =>
        val bytes = value match {
          case b: ByteBuffer =>
            val bytes = new Array[Byte](b.remaining)
            b.get(bytes)
            // Do not forget to reset the position
            b.rewind()
            bytes
          case b: Array[Byte] => b
          case other => throw new RuntimeException(s"$other is not a valid avro binary.")
        }
        updater.set(ordinal, bytes)

      case (FIXED, _: DecimalType) => (updater, ordinal, value) =>
        val d = avroType.getLogicalType.asInstanceOf[LogicalTypes.Decimal]
        val bigDecimal = decimalConversions.fromFixed(value.asInstanceOf[GenericFixed], avroType, d)
        val decimal = createDecimal(bigDecimal, d.getPrecision, d.getScale)
        updater.setDecimal(ordinal, decimal)

      case (BYTES, _: DecimalType) => (updater, ordinal, value) =>
        val d = avroType.getLogicalType.asInstanceOf[LogicalTypes.Decimal]
        val bigDecimal = decimalConversions.fromBytes(value.asInstanceOf[ByteBuffer], avroType, d)
        val decimal = createDecimal(bigDecimal, d.getPrecision, d.getScale)
        updater.setDecimal(ordinal, decimal)

      case (RECORD, st: StructType) =>
        // Avro datasource doesn't accept filters with nested attributes. See SPARK-32328.
        // We can always return `false` from `applyFilters` for nested records.
        val writeRecord = getRecordWriter(avroType, st, path, applyFilters = _ => false)
        (updater, ordinal, value) =>
          val row = new SpecificInternalRow(st)
          writeRecord(new RowUpdater(row), value.asInstanceOf[GenericRecord])
          updater.set(ordinal, row)

      case (ARRAY, ArrayType(elementType, containsNull)) =>
        val elementWriter = newWriter(avroType.getElementType, elementType, path)
        (updater, ordinal, value) =>
          val collection = value.asInstanceOf[java.util.Collection[Any]]
          val result = createArrayData(elementType, collection.size())
          val elementUpdater = new ArrayDataUpdater(result)

          var i = 0
          val iter = collection.iterator()
          while (iter.hasNext) {
            val element = iter.next()
            if (element == null) {
              if (!containsNull) {
                throw new RuntimeException(s"Array value at path ${path.mkString(".")} is not " +
                  "allowed to be null")
              } else {
                elementUpdater.setNullAt(i)
              }
            } else {
              elementWriter(elementUpdater, i, element)
            }
            i += 1
          }

          updater.set(ordinal, result)

      case (MAP, MapType(keyType, valueType, valueContainsNull)) if keyType == StringType =>
        val keyWriter = newWriter(SchemaBuilder.builder().stringType(), StringType, path)
        val valueWriter = newWriter(avroType.getValueType, valueType, path)
        (updater, ordinal, value) =>
          val map = value.asInstanceOf[java.util.Map[AnyRef, AnyRef]]
          val keyArray = createArrayData(keyType, map.size())
          val keyUpdater = new ArrayDataUpdater(keyArray)
          val valueArray = createArrayData(valueType, map.size())
          val valueUpdater = new ArrayDataUpdater(valueArray)
          val iter = map.entrySet().iterator()
          var i = 0
          while (iter.hasNext) {
            val entry = iter.next()
            assert(entry.getKey != null)
            keyWriter(keyUpdater, i, entry.getKey)
            if (entry.getValue == null) {
              if (!valueContainsNull) {
                throw new RuntimeException(s"Map value at path ${path.mkString(".")} is not " +
                  "allowed to be null")
              } else {
                valueUpdater.setNullAt(i)
              }
            } else {
              valueWriter(valueUpdater, i, entry.getValue)
            }
            i += 1
          }

          // The Avro map will never have null or duplicated map keys, it's safe to create a
          // ArrayBasedMapData directly here.
          updater.set(ordinal, new ArrayBasedMapData(keyArray, valueArray))

      case (UNION, _) =>
        val allTypes = avroType.getTypes.asScala
        val nonNullTypes = allTypes.filter(_.getType != NULL)
        val nonNullAvroType = Schema.createUnion(nonNullTypes.asJava)
        if (nonNullTypes.nonEmpty) {
          if (nonNullTypes.length == 1) {
            newWriter(nonNullTypes.head, catalystType, path)
          } else {
            nonNullTypes.map(_.getType).toSeq match {
              case Seq(a, b) if Set(a, b) == Set(INT, LONG) && catalystType == LongType =>
                (updater, ordinal, value) =>
                  value match {
                    case null => updater.setNullAt(ordinal)
                    case l: java.lang.Long => updater.setLong(ordinal, l)
                    case i: java.lang.Integer => updater.setLong(ordinal, i.longValue())
                  }

              case Seq(a, b) if Set(a, b) == Set(FLOAT, DOUBLE) && catalystType == DoubleType =>
                (updater, ordinal, value) =>
                  value match {
                    case null => updater.setNullAt(ordinal)
                    case d: java.lang.Double => updater.setDouble(ordinal, d)
                    case f: java.lang.Float => updater.setDouble(ordinal, f.doubleValue())
                  }

              case _ =>
                catalystType match {
                  case st: StructType if st.length == nonNullTypes.size =>
                    val fieldWriters = nonNullTypes.zip(st.fields).map {
                      case (schema, field) => newWriter(schema, field.dataType, path :+ field.name)
                    }.toArray
                    (updater, ordinal, value) => {
                      val row = new SpecificInternalRow(st)
                      val fieldUpdater = new RowUpdater(row)
                      val i = GenericData.get().resolveUnion(nonNullAvroType, value)
                      fieldWriters(i)(fieldUpdater, i, value)
                      updater.set(ordinal, row)
                    }

                  case _ =>
                    throw new IncompatibleSchemaException(
                      s"Cannot convert Avro to catalyst because schema at path " +
                        s"${path.mkString(".")} is not compatible " +
                        s"(avroType = $avroType, sqlType = $catalystType).\n" +
                        s"Source Avro schema: $rootAvroType.\n" +
                        s"Target Catalyst type: $rootCatalystType")
                }
            }
          }
        } else {
          (updater, ordinal, value) => updater.setNullAt(ordinal)
        }

      case _ =>
        throw new IncompatibleSchemaException(
          s"Cannot convert Avro to catalyst because schema at path ${path.mkString(".")} " +
            s"is not compatible (avroType = $avroType, sqlType = $catalystType).\n" +
            s"Source Avro schema: $rootAvroType.\n" +
            s"Target Catalyst type: $rootCatalystType")
    }

  // TODO: move the following method in Decimal object on creating Decimal from BigDecimal?
  private def createDecimal(decimal: BigDecimal, precision: Int, scale: Int): Decimal = {
    if (precision <= Decimal.MAX_LONG_DIGITS) {
      // Constructs a `Decimal` with an unscaled `Long` value if possible.
      Decimal(decimal.unscaledValue().longValue(), precision, scale)
    } else {
      // Otherwise, resorts to an unscaled `BigInteger` instead.
      Decimal(decimal, precision, scale)
    }
  }

  private def getRecordWriter(avroType: Schema,
                              sqlType: StructType,
                              path: List[String],
                              applyFilters: Int => Boolean): (CatalystDataUpdater, GenericRecord) => Boolean = {
    val validFieldIndexes = ArrayBuffer.empty[Int]
    val fieldWriters = ArrayBuffer.empty[(CatalystDataUpdater, Any) => Unit]

    val avroSchemaHelper = new AvroUtils.AvroSchemaHelper(avroType)
    val length = sqlType.length
    var i = 0
    while (i < length) {
      val sqlField = sqlType.fields(i)
      avroSchemaHelper.getFieldByName(sqlField.name) match {
        case Some(avroField) =>
          validFieldIndexes += avroField.pos()

          val baseWriter = newWriter(avroField.schema(), sqlField.dataType, path :+ sqlField.name)
          val ordinal = i
          val fieldWriter = (fieldUpdater: CatalystDataUpdater, value: Any) => {
            if (value == null) {
              fieldUpdater.setNullAt(ordinal)
            } else {
              baseWriter(fieldUpdater, ordinal, value)
            }
          }
          fieldWriters += fieldWriter
        case None if !sqlField.nullable =>
          val fieldStr = s"${path.mkString(".")}.${sqlField.name}"
          throw new IncompatibleSchemaException(
            s"""
               |Cannot find non-nullable field $fieldStr in Avro schema.
               |Source Avro schema: $rootAvroType.
               |Target Catalyst type: $rootCatalystType.
           """.stripMargin)
        case _ => // nothing to do
      }
      i += 1
    }

    (fieldUpdater, record) => {
      var i = 0
      var skipRow = false
      while (i < validFieldIndexes.length && !skipRow) {
        fieldWriters(i)(fieldUpdater, record.get(validFieldIndexes(i)))
        skipRow = applyFilters(i)
        i += 1
      }
      skipRow
    }
  }

  private def createArrayData(elementType: DataType, length: Int): ArrayData = elementType match {
    case BooleanType => UnsafeArrayData.fromPrimitiveArray(new Array[Boolean](length))
    case ByteType => UnsafeArrayData.fromPrimitiveArray(new Array[Byte](length))
    case ShortType => UnsafeArrayData.fromPrimitiveArray(new Array[Short](length))
    case IntegerType => UnsafeArrayData.fromPrimitiveArray(new Array[Int](length))
    case LongType => UnsafeArrayData.fromPrimitiveArray(new Array[Long](length))
    case FloatType => UnsafeArrayData.fromPrimitiveArray(new Array[Float](length))
    case DoubleType => UnsafeArrayData.fromPrimitiveArray(new Array[Double](length))
    case _ => new GenericArrayData(new Array[Any](length))
  }

  /**
   * A base interface for updating values inside catalyst data structure like `InternalRow` and
   * `ArrayData`.
   */
  sealed trait CatalystDataUpdater {
    def set(ordinal: Int, value: Any): Unit

    def setNullAt(ordinal: Int): Unit = set(ordinal, null)

    def setBoolean(ordinal: Int, value: Boolean): Unit = set(ordinal, value)

    def setByte(ordinal: Int, value: Byte): Unit = set(ordinal, value)

    def setShort(ordinal: Int, value: Short): Unit = set(ordinal, value)

    def setInt(ordinal: Int, value: Int): Unit = set(ordinal, value)

    def setLong(ordinal: Int, value: Long): Unit = set(ordinal, value)

    def setDouble(ordinal: Int, value: Double): Unit = set(ordinal, value)

    def setFloat(ordinal: Int, value: Float): Unit = set(ordinal, value)

    def setDecimal(ordinal: Int, value: Decimal): Unit = set(ordinal, value)
  }

  final class RowUpdater(row: InternalRow) extends CatalystDataUpdater {
    override def set(ordinal: Int, value: Any): Unit = row.update(ordinal, value)

    override def setNullAt(ordinal: Int): Unit = row.setNullAt(ordinal)

    override def setBoolean(ordinal: Int, value: Boolean): Unit = row.setBoolean(ordinal, value)

    override def setByte(ordinal: Int, value: Byte): Unit = row.setByte(ordinal, value)

    override def setShort(ordinal: Int, value: Short): Unit = row.setShort(ordinal, value)

    override def setInt(ordinal: Int, value: Int): Unit = row.setInt(ordinal, value)

    override def setLong(ordinal: Int, value: Long): Unit = row.setLong(ordinal, value)

    override def setDouble(ordinal: Int, value: Double): Unit = row.setDouble(ordinal, value)

    override def setFloat(ordinal: Int, value: Float): Unit = row.setFloat(ordinal, value)

    override def setDecimal(ordinal: Int, value: Decimal): Unit =
      row.setDecimal(ordinal, value, value.precision)
  }

  final class ArrayDataUpdater(array: ArrayData) extends CatalystDataUpdater {
    override def set(ordinal: Int, value: Any): Unit = array.update(ordinal, value)

    override def setNullAt(ordinal: Int): Unit = array.setNullAt(ordinal)

    override def setBoolean(ordinal: Int, value: Boolean): Unit = array.setBoolean(ordinal, value)

    override def setByte(ordinal: Int, value: Byte): Unit = array.setByte(ordinal, value)

    override def setShort(ordinal: Int, value: Short): Unit = array.setShort(ordinal, value)

    override def setInt(ordinal: Int, value: Int): Unit = array.setInt(ordinal, value)

    override def setLong(ordinal: Int, value: Long): Unit = array.setLong(ordinal, value)

    override def setDouble(ordinal: Int, value: Double): Unit = array.setDouble(ordinal, value)

    override def setFloat(ordinal: Int, value: Float): Unit = array.setFloat(ordinal, value)

    override def setDecimal(ordinal: Int, value: Decimal): Unit = array.update(ordinal, value)
  }
}

object AvroDeserializer {

  // NOTE: Following methods have been renamed in Spark 3.1.3 [1] making [[AvroDeserializer]] implementation
  //       (which relies on it) be only compatible with the exact same version of [[DataSourceUtils]].
  //       To make sure this implementation is compatible w/ all Spark versions w/in Spark 3.1.x branch,
  //       we're preemptively cloned those methods to make sure Hudi is compatible w/ Spark 3.1.2 as well as
  //       w/ Spark >= 3.1.3
  //
  // [1] https://github.com/apache/spark/pull/34978

  def createDateRebaseFuncInRead(rebaseMode: LegacyBehaviorPolicy.Value,
                                 format: String): Int => Int = rebaseMode match {
    case LegacyBehaviorPolicy.EXCEPTION => days: Int =>
      if (days < RebaseDateTime.lastSwitchJulianDay) {
        throw DataSourceUtils.newRebaseExceptionInRead(format)
      }
      days
    case LegacyBehaviorPolicy.LEGACY => RebaseDateTime.rebaseJulianToGregorianDays
    case LegacyBehaviorPolicy.CORRECTED => identity[Int]
  }

  def createTimestampRebaseFuncInRead(rebaseMode: LegacyBehaviorPolicy.Value,
                                      format: String): Long => Long = rebaseMode match {
    case LegacyBehaviorPolicy.EXCEPTION => micros: Long =>
      if (micros < RebaseDateTime.lastSwitchJulianTs) {
        throw DataSourceUtils.newRebaseExceptionInRead(format)
      }
      micros
    case LegacyBehaviorPolicy.LEGACY => RebaseDateTime.rebaseJulianToGregorianMicros
    case LegacyBehaviorPolicy.CORRECTED => identity[Long]
  }
}

