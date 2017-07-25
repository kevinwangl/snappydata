/*
 * Copyright (c) 2017 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */

package org.apache.spark.sql.execution.columnar.encoding

import java.nio.ByteBuffer

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.util.{ArrayData, MapData}
import org.apache.spark.sql.types.{DataType, Decimal, StructField}
import org.apache.spark.unsafe.types.{CalendarInterval, UTF8String}

/**
 * Decodes a column of a batch that has seen some changes (updates or deletes) by
 * combining all delta values, delete bitmask and full column value obtained from
 * [[ColumnDeltaEncoder]]s and column encoders. Callers should
 * provide this with the set of all deltas and delete bitmask for the column
 * apart from the full column value, then use it like a normal decoder.
 *
 * Only the first column being decoded should set the "deleteBuffer", if present,
 * and generated code should check the return value of "next*" methods to continue
 * if it is negative.
 */
final class MutatedColumnDecoder(decoder: ColumnDecoder, field: StructField,
    delta1Buffer: ByteBuffer, delta2Buffer: ByteBuffer, delta3Buffer: ByteBuffer,
    deleteBuffer: ByteBuffer)
    extends MutatedColumnDecoderBase(decoder, field,
      delta1Buffer, delta2Buffer, delta3Buffer, deleteBuffer) {

  override protected[sql] def hasNulls: Boolean = false

  override def isNull(columnBytes: AnyRef, ordinal: Int, mutated: Int): Int = 0
}

final class MutatedColumnDecoderNullable(decoder: ColumnDecoder, field: StructField,
    delta1Buffer: ByteBuffer, delta2Buffer: ByteBuffer, delta3Buffer: ByteBuffer,
    deleteBuffer: ByteBuffer)
    extends MutatedColumnDecoderBase(decoder, field,
      delta1Buffer, delta2Buffer, delta3Buffer, deleteBuffer) {

  override protected[sql] def hasNulls: Boolean = true

  override def isNull(columnBytes: AnyRef, ordinal: Int, mutated: Int): Int = {
    if (mutated == 0) {
      decoder.isNull(columnBytes, ordinal, mutated)
    } else {
      // delete should never land here and should continue after mutated call
      nextDeltaBuffer.isNull
    }
  }
}

abstract class MutatedColumnDecoderBase(decoder: ColumnDecoder, field: StructField,
    delta1Buffer: ByteBuffer, delta2Buffer: ByteBuffer, delta3Buffer: ByteBuffer,
    deleteBuffer: ByteBuffer) extends ColumnDecoder {

  // positions are initialized at max so that they always are greater
  // than a valid index

  private final var delta1Position = Int.MaxValue
  private final val delta1 = if (delta1Buffer ne null) {
    val d = new ColumnDeltaDecoder(ColumnEncoding.getColumnDecoder(delta1Buffer, field))
    delta1Position = d.initialize(delta1Buffer, field).toInt
    d
  } else null

  private final var delta2Position = Int.MaxValue
  private final val delta2 = if (delta2Buffer ne null) {
    val d = new ColumnDeltaDecoder(ColumnEncoding.getColumnDecoder(delta2Buffer, field))
    delta2Position = d.initialize(delta2Buffer, field).toInt
    d
  } else null

  private final var delta3Position = Int.MaxValue
  private final val delta3 = if (delta3Buffer ne null) {
    val d = new ColumnDeltaDecoder(ColumnEncoding.getColumnDecoder(delta3Buffer, field))
    delta3Position = d.initialize(delta3Buffer, field).toInt
    d
  } else null

  private final var deletePosition = Int.MaxValue
  private final var deleteCursor: Long = _
  private final var deleteEndCursor: Long = _
  private final val deleteBytes = if (deleteBuffer ne null) {
    val allocator = ColumnEncoding.getAllocator(deleteBuffer)
    deleteCursor = allocator.baseOffset(deleteBuffer) + deleteBuffer.position()
    deleteEndCursor = deleteCursor + deleteBuffer.limit()
    deletePosition = nextDeletedPosition()
    allocator.baseObject(deleteBuffer)
  } else null

  protected final var nextMutationPosition: Int = -1
  protected final var nextDeltaBuffer: ColumnDeltaDecoder = _

  override def typeId: Int = decoder.typeId

  override final def supports(dataType: DataType): Boolean = decoder.supports(dataType)

  protected[sql] val SW_hasNulls: Boolean = {
    field.nullable && (decoder.hasNulls || ((delta1 ne null) && delta1.hasNulls)
        || ((delta2 ne null) && delta2.hasNulls) || ((delta3 ne null) && delta3.hasNulls))
  }

  override protected[sql] def initializeNulls(columnBytes: AnyRef, cursor: Long,
      field: StructField): Long = decoder.initializeNulls(columnBytes, cursor, field)

  override protected[sql] def initializeCursor(columnBytes: AnyRef, cursor: Long,
      field: StructField): Long = {
    updateNextMutationPosition()
    decoder.initializeCursor(columnBytes, cursor, field)
  }

  private def nextDeletedPosition(): Int = {
    val cursor = deleteCursor
    if (cursor < deleteEndCursor) {
      deleteCursor += 4
      ColumnEncoding.readInt(deleteBytes, cursor)
    } else Int.MaxValue
  }

  protected final def updateNextMutationPosition(): Unit = {
    var next = Int.MaxValue
    var movedIndex = -1

    // check deleted first which overrides everyone else
    if (deletePosition < next) {
      next = deletePosition
      movedIndex = 0
    }
    // first delta is the lowest in hierarchy and overrides others
    if (delta1Position < next) {
      next = delta1Position
      movedIndex = 1
    }
    // next delta in hierarchy
    if (delta2Position < next) {
      next = delta2Position
      movedIndex = 2
    }
    // last delta in hierarchy
    if (delta3Position < next) {
      next = delta3Position
      movedIndex = 3
    }
    movedIndex match {
      case 0 =>
        nextDeletedPosition()
        nextDeltaBuffer = null
      case 1 =>
        delta1Position = delta1.moveToNextPosition()
        nextDeltaBuffer = delta1
      case 2 =>
        delta2Position = delta2.moveToNextPosition()
        nextDeltaBuffer = delta2
      case 3 =>
        delta3Position = delta3.moveToNextPosition()
        nextDeltaBuffer = delta3
      case _ =>
    }
    nextMutationPosition = next
  }

  override final def mutated(ordinal: Int): Int = {
    if (nextMutationPosition != ordinal) 0
    else {
      updateNextMutationPosition()
      // value of -1 indicates deleted to the caller
      if (nextDeltaBuffer ne null) 1 else -1
    }
  }

  override final def nextBoolean(columnBytes: AnyRef, cursor: Long,
      mutated: Int): Long = {
    if (mutated == 0) {
      decoder.nextBoolean(columnBytes, cursor, mutated = 0)
    } else {
      // delete should never land here and should continue after mutated call
      nextDeltaBuffer.nextBoolean()
      cursor
    }
  }

  override final def readBoolean(columnBytes: AnyRef, cursor: Long,
      mutated: Int): Boolean = {
    if (mutated == 0) {
      decoder.readBoolean(columnBytes, cursor, mutated = 0)
    } else {
      nextDeltaBuffer.readBoolean
    }
  }

  override final def nextByte(columnBytes: AnyRef, cursor: Long,
      mutated: Int): Long = {
    if (mutated == 0) {
      decoder.nextByte(columnBytes, cursor, mutated = 0)
    } else {
      // delete should never land here and should continue after mutated call
      nextDeltaBuffer.nextByte()
      cursor
    }
  }

  override final def readByte(columnBytes: AnyRef, cursor: Long,
      mutated: Int): Byte = {
    if (mutated == 0) {
      decoder.readByte(columnBytes, cursor, mutated = 0)
    } else {
      nextDeltaBuffer.readByte
    }
  }

  override final def nextShort(columnBytes: AnyRef, cursor: Long,
      mutated: Int): Long = {
    if (mutated == 0) {
      decoder.nextShort(columnBytes, cursor, mutated = 0)
    } else {
      // delete should never land here and should continue after mutated call
      nextDeltaBuffer.nextShort()
      cursor
    }
  }

  override final def readShort(columnBytes: AnyRef, cursor: Long,
      mutated: Int): Short = {
    if (mutated == 0) {
      decoder.readShort(columnBytes, cursor, mutated = 0)
    } else {
      nextDeltaBuffer.readShort
    }
  }

  override final def nextInt(columnBytes: AnyRef, cursor: Long,
      mutated: Int): Long = {
    if (mutated == 0) {
      decoder.nextInt(columnBytes, cursor, mutated = 0)
    } else {
      // delete should never land here and should continue after mutated call
      nextDeltaBuffer.nextInt()
      cursor
    }
  }

  override final def readInt(columnBytes: AnyRef, cursor: Long,
      mutated: Int): Int = {
    if (mutated == 0) {
      decoder.readInt(columnBytes, cursor, mutated = 0)
    } else {
      nextDeltaBuffer.readInt
    }
  }

  override final def nextLong(columnBytes: AnyRef, cursor: Long,
      mutated: Int): Long = {
    if (mutated == 0) {
      decoder.nextLong(columnBytes, cursor, mutated = 0)
    } else {
      // delete should never land here and should continue after mutated call
      nextDeltaBuffer.nextLong()
      cursor
    }
  }

  override final def readLong(columnBytes: AnyRef, cursor: Long,
      mutated: Int): Long = {
    if (mutated == 0) {
      decoder.readLong(columnBytes, cursor, mutated = 0)
    } else {
      nextDeltaBuffer.readLong
    }
  }

  override final def nextFloat(columnBytes: AnyRef, cursor: Long,
      mutated: Int): Long = {
    if (mutated == 0) {
      decoder.nextFloat(columnBytes, cursor, mutated = 0)
    } else {
      // delete should never land here and should continue after mutated call
      nextDeltaBuffer.nextFloat()
      cursor
    }
  }

  override final def readFloat(columnBytes: AnyRef, cursor: Long,
      mutated: Int): Float = {
    if (mutated == 0) {
      decoder.readFloat(columnBytes, cursor, mutated = 0)
    } else {
      nextDeltaBuffer.readFloat
    }
  }

  override final def nextDouble(columnBytes: AnyRef, cursor: Long,
      mutated: Int): Long = {
    if (mutated == 0) {
      decoder.nextDouble(columnBytes, cursor, mutated = 0)
    } else {
      // delete should never land here and should continue after mutated call
      nextDeltaBuffer.nextDouble()
      cursor
    }
  }

  override final def readDouble(columnBytes: AnyRef, cursor: Long,
      mutated: Int): Double = {
    if (mutated == 0) {
      decoder.readDouble(columnBytes, cursor, mutated = 0)
    } else {
      nextDeltaBuffer.readDouble
    }
  }

  override final def nextLongDecimal(columnBytes: AnyRef, cursor: Long,
      mutated: Int): Long = {
    if (mutated == 0) {
      decoder.nextLongDecimal(columnBytes, cursor, mutated = 0)
    } else {
      // delete should never land here and should continue after mutated call
      nextDeltaBuffer.nextLongDecimal()
      cursor
    }
  }

  override final def readLongDecimal(columnBytes: AnyRef, precision: Int,
      scale: Int, cursor: Long, mutated: Int): Decimal = {
    if (mutated == 0) {
      decoder.readLongDecimal(columnBytes, precision, scale, cursor, mutated = 0)
    } else {
      nextDeltaBuffer.readLongDecimal(precision, scale)
    }
  }

  override final def nextDecimal(columnBytes: AnyRef, cursor: Long,
      mutated: Int): Long = {
    if (mutated == 0) {
      decoder.nextDecimal(columnBytes, cursor, mutated = 0)
    } else {
      // delete should never land here and should continue after mutated call
      nextDeltaBuffer.nextDecimal()
      cursor
    }
  }

  override final def readDecimal(columnBytes: AnyRef, precision: Int,
      scale: Int, cursor: Long, mutated: Int): Decimal = {
    if (mutated == 0) {
      decoder.readDecimal(columnBytes, precision, scale, cursor, mutated = 0)
    } else {
      nextDeltaBuffer.readDecimal(precision, scale)
    }
  }

  override final def nextUTF8String(columnBytes: AnyRef, cursor: Long,
      mutated: Int): Long = {
    if (mutated == 0) {
      decoder.nextUTF8String(columnBytes, cursor, mutated = 0)
    } else {
      // delete should never land here and should continue after mutated call
      nextDeltaBuffer.nextUTF8String()
      cursor
    }
  }

  override final def readUTF8String(columnBytes: AnyRef, cursor: Long,
      mutated: Int): UTF8String = {
    if (mutated == 0) {
      decoder.readUTF8String(columnBytes, cursor, mutated = 0)
    } else {
      nextDeltaBuffer.readUTF8String
    }
  }

  override final def nextInterval(columnBytes: AnyRef, cursor: Long,
      mutated: Int): Long = {
    if (mutated == 0) {
      decoder.nextInterval(columnBytes, cursor, mutated = 0)
    } else {
      // delete should never land here and should continue after mutated call
      nextDeltaBuffer.nextInterval()
      cursor
    }
  }

  override final def readInterval(columnBytes: AnyRef,
      cursor: Long, mutated: Int): CalendarInterval = {
    if (mutated == 0) {
      decoder.readInterval(columnBytes, cursor, mutated = 0)
    } else {
      nextDeltaBuffer.readInterval
    }
  }

  override final def nextBinary(columnBytes: AnyRef, cursor: Long,
      mutated: Int): Long = {
    if (mutated == 0) {
      decoder.nextBinary(columnBytes, cursor, mutated = 0)
    } else {
      // delete should never land here and should continue after mutated call
      nextDeltaBuffer.nextBinary()
      cursor
    }
  }

  override final def readBinary(columnBytes: AnyRef, cursor: Long,
      mutated: Int): Array[Byte] = {
    if (mutated == 0) {
      decoder.readBinary(columnBytes, cursor, mutated = 0)
    } else {
      nextDeltaBuffer.readBinary
    }
  }

  override final def nextArray(columnBytes: AnyRef, cursor: Long,
      mutated: Int): Long = {
    if (mutated == 0) {
      decoder.nextArray(columnBytes, cursor, mutated = 0)
    } else {
      // delete should never land here and should continue after mutated call
      nextDeltaBuffer.nextArray()
      cursor
    }
  }

  override final def readArray(columnBytes: AnyRef, cursor: Long,
      mutated: Int): ArrayData = {
    if (mutated == 0) {
      decoder.readArray(columnBytes, cursor, mutated = 0)
    } else {
      nextDeltaBuffer.readArray
    }
  }

  override final def nextMap(columnBytes: AnyRef, cursor: Long,
      mutated: Int): Long = {
    if (mutated == 0) {
      decoder.nextMap(columnBytes, cursor, mutated = 0)
    } else {
      // delete should never land here and should continue after mutated call
      nextDeltaBuffer.nextMap()
      cursor
    }
  }

  override final def readMap(columnBytes: AnyRef, cursor: Long,
      mutated: Int): MapData = {
    if (mutated == 0) {
      decoder.readMap(columnBytes, cursor, mutated = 0)
    } else {
      nextDeltaBuffer.readMap
    }
  }

  override final def nextStruct(columnBytes: AnyRef, cursor: Long,
      mutated: Int): Long = {
    if (mutated == 0) {
      decoder.nextStruct(columnBytes, cursor, mutated = 0)
    } else {
      // delete should never land here and should continue after mutated call
      nextDeltaBuffer.nextStruct()
      cursor
    }
  }

  override final def readStruct(columnBytes: AnyRef, numFields: Int,
      cursor: Long, mutated: Int): InternalRow = {
    if (mutated == 0) {
      decoder.readStruct(columnBytes, numFields, cursor, mutated = 0)
    } else {
      nextDeltaBuffer.readStruct(numFields)
    }
  }
}
