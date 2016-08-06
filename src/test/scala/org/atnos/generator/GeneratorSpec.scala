package org.atnos.generator

import org.atnos.eff._
import org.atnos.eff.syntax.all._
import org.atnos.generator.Generator._
import org.scalacheck._
import Gen._
import org.specs2._
import org.specs2.concurrent.ExecutionEnv
import scala.concurrent.Future

class GeneratorSpec(implicit ee: ExecutionEnv) extends Specification with ScalaCheck { def is = s2"""

  emit / collect             $emitCollect
  emit / filter / collect    $emitFilterCollect
  chunk                      $chunkProducer
  flattenList                $flattenList1
  flattenProducer            $flattenProducer1
  sequence futures           $sequenceFutures

"""

  def emitCollect = prop { xs: List[Int] =>
    collect[NoEffect, Int](emit(xs)).run ==== xs
  }

  def emitFilterCollect = prop { xs: List[Int] =>
    collect[NoEffect, Int](filter(emit(xs))(_ > 2)).run ==== xs.filter(_ > 2)
  }

  def chunkProducer = prop { (xs: List[Int], n: Int) =>
    collect[NoEffect, List[Int]](chunk(n)(emit(xs))).run.flatten ==== xs
  }.setGen2(Gen.choose(0, 5)).noShrink

  def flattenList1 = prop { (xs: List[Int], n: Int) =>
    collect[NoEffect, Int](flattenList(chunk(n)(emit(xs)))).run ==== xs
  }.setGen2(Gen.choose(0, 5)).noShrink

  def flattenProducer1 = prop { (xs: List[Int], n: Int) =>
    collect[NoEffect, Int](flatten(emit(List.fill(n)(emit(xs))))).run ==== List.fill(n)(xs).flatten
  }.setGen2(Gen.choose(0, 5)).noShrink

  def sequenceFutures = prop { xs: List[Int] =>
    emit(xs.map(x => Future(Thread.sleep(x))))
  }.setGen(listOf(3, choose(20, 300)))
}
