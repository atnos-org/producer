package org.atnos.generator

import org.atnos.eff._
import org.atnos.eff.all._
import syntax.all._
import org.atnos.generator.Generator._
import org.scalacheck._
import org.specs2._
import org.specs2.concurrent.ExecutionEnv
import scala.concurrent.Future
import cats.implicits._
import cats.data.Writer

class GeneratorSpec(implicit ee: ExecutionEnv) extends Specification with ScalaCheck { def is = s2"""

  emit / collect             $emitCollect
  emit / filter / collect    $emitFilterCollect
  chunk                      $chunkProducer
  flattenList                $flattenList1
  flattenProducer            $flattenProducer1
  sequence futures           $sequenceFutures

"""

  type S = Writer[Int, ?] |: NoEffect

  def emitCollect = prop { xs: List[Int] =>
    collect[S, Int](emit(xs)).runWriterLog.run ==== xs
  }.noShrink

  def emitFilterCollect = prop { xs: List[Int] =>
    collect(filter(emit[S, Int](xs))(_ > 2)).runWriterLog.run ==== xs.filter(_ > 2)
  }

  def chunkProducer = prop { (xs: List[Int], n: Int) =>
    collect[Writer[List[Int], ?] |: NoEffect, List[Int]](chunk(n)(emit(xs))).runWriterLog.run.flatten ==== xs
  }.setGen2(Gen.choose(0, 5)).noShrink

  def flattenList1 = prop { (xs: List[Int], n: Int) =>
    collect[S, Int](flattenList(chunk(n)(emit(xs)))).runWriterLog.run ==== xs
  }.setGen2(Gen.choose(0, 5)).noShrink

  def flattenProducer1 = prop { (xs: List[Int], n: Int) =>
    collect[S, Int](flatten(emit(List.fill(n)(emit(xs))))).runWriterLog.run ==== List.fill(n)(xs).flatten
  }.setGen2(Gen.choose(0, 5)).noShrink

  def sequenceFutures = prop { xs: List[Int] =>
    type SF = Writer[Int, ?] |: Future |: NoEffect

    def doIt[R](implicit f: Future <= R) =
      sequence[R, Future, Int](4)(emit(xs.map(x => async(action(x)))))

    collect(doIt[SF]).runWriterLog.detach must be_==(xs).await
  }.noShrink.setGen(Gen.listOfN(3, Gen.choose(20, 300))).set(minTestsOk = 1)

  def action(x: Int) = {
    Thread.sleep(x.toLong)
    x
  }

}
