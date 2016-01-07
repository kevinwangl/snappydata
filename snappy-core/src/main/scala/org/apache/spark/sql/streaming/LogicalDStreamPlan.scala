package org.apache.spark.sql.streaming

import scala.collection.immutable

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.analysis.MultiInstanceRelation
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.catalyst.plans.logical.{LogicalPlan, Statistics}
import org.apache.spark.streaming.dstream.DStream


case class LogicalDStreamPlan(output: Seq[Attribute],
    stream: DStream[InternalRow])
    (val streamingSnappy: SnappyStreamingContext)
    extends LogicalPlan with MultiInstanceRelation {

  def newInstance(): LogicalDStreamPlan =
    LogicalDStreamPlan(output.map(_.newInstance()),
      stream)(streamingSnappy).asInstanceOf[this.type]

  @transient override lazy val statistics = Statistics(
    sizeInBytes = BigInt(streamingSnappy.snappyContext.conf.defaultSizeInBytes)
  )

  def children: immutable.Nil.type = Nil
}
