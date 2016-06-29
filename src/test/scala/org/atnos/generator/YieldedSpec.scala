package org.atnos.generator

import org.atnos.eff._
import org.atnos.eff.syntax.all._
import org.atnos.generator.Yielded._
import org.scalacheck._
import org.specs2._

class YieldedSpec extends Specification with ScalaCheck { def is = s2"""

  emit / collect             $emitCollect
  emit / filter / collect    $emitFilterCollect
  chunk                      $chunkProducer

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

}
