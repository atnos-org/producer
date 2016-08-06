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
import transducers._

class GeneratorSpec(implicit ee: ExecutionEnv) extends Specification with ScalaCheck { def is = s2"""

  emit / collect             $emitCollect
  emit / filter / collect    $emitFilterCollect
  chunk                      $chunkProducer
  flattenList                $flattenList1
  flattenProducer            $flattenProducer1
  map                        $map1
  sequence futures           $sequenceFutures

  a producer can be followed by another one  $followed
  a producer can be modified by a transducer $transduced
  a producer can be modified by a receiver   $received
  a receiver can run another producer if the first one is empty  $receivedOr

"""

  type WriterInt[A]    = Writer[Int, A]
  type WriterOption[A] = Writer[Option[Int], A]
  type WriterString[A] = Writer[String, A]
  type WriterList[A]   = Writer[List[Int], A]

  type S = Fx.fx1[WriterInt]
  type S1 = Fx.fx1[WriterString]
  type SL = Fx.fx1[WriterList]
  type SO = Fx.fx1[WriterOption]

  def emitCollect = prop { xs: List[Int] =>
    emit[S, Int](xs).runLog ==== xs
  }.noShrink

  def emitFilterCollect = prop { xs: List[Int] =>
    emit[S, Int](xs).filter(_ > 2).runLog ==== xs.filter(_ > 2)
  }

  def chunkProducer = prop { (xs: List[Int], n: Int) =>
    emit[SL, Int](xs).chunk(n).runLog.flatten ==== xs
  }.setGen2(Gen.choose(0, 5)).noShrink

  def flattenList1 = prop { (xs: List[Int], n: Int) =>
    emit[S, Int](xs).chunk(n).flattenList.runLog ==== xs
  }.setGen2(Gen.choose(0, 5)).noShrink

  def flattenProducer1 = prop { (xs: List[Int], n: Int) =>
    emit[S, Producer[S, Int]](List.fill(n)(emit[S, Int](xs))).flatten.runLog ==== List.fill(n)(xs).flatten
  }.setGen2(Gen.choose(0, 5)).noShrink

  def map1 = prop { xs: List[Int] =>
    val f = (x: Int) => (x+ 1).toString
    emit[S1, Int](xs).map(f).runLog ==== xs.map(f)
  }.setGen(Gen.listOf(Gen.choose(1, 100)))

  def sequenceFutures = prop { xs: List[Int] =>
    type SF = Fx.fx2[Writer[Int, ?], Future]

    def doIt[R](implicit f: Future |= R): Producer[R, Int] =
      sequence[R, Future, Int](4)(emit(xs.map(x => async(action(x)))))

    doIt[SF].runLog.detach must be_==(xs).await
  }.noShrink.setGen(Gen.listOfN(3, Gen.choose(20, 300))).set(minTestsOk = 1)

  def followed = prop { (xs1: List[Int], xs2: List[Int]) =>
    (emit[S, Int](xs1) > emit(xs2)).runLog ==== xs1 ++ xs2
  }

  def transduced = prop { xs: List[Int] =>
    val f = (x: Int) => (x+ 1).toString

    (emit[S1, Int](xs) |> transducer(f)).runLog ==== xs.map(f)
  }.setGen(Gen.listOf(Gen.choose(1, 100)))

  def received = prop { xs: List[Int] =>
    val f = (x: Int) => (x+ 1).toString

    def plusOne[R] =
      receive[R, Int, String](a => yielded(f(a)))

    (emit[S1, Int](xs) |> plusOne).runLog ==== xs.map(f)
  }.setGen(Gen.listOf(Gen.choose(1, 100)))

  def receivedOr = prop { xs: List[Int] =>
    val f = (x: Int) => (x+ 1).toString

    def plusOne[R] =
      receiveOr[R, Int, String](a => yielded(f(a)))(emit(List("1", "2", "3")))

    (emit[S1, Int](xs) |> plusOne).runLog ==== xs.map(f) ++ List("1", "2", "3")
  }.setGen(Gen.listOf(Gen.choose(1, 100)))

  def receivedOption = prop { xs: List[Int] =>
    (emit[SO, Int](xs) |> receiveOption).runLog ==== xs.map(Option(_)) ++ List(None)
  }.setGen(Gen.listOf(Gen.choose(1, 100)))

  /**
   * HELPERS
   */

  implicit class ProducerOperations[W](p: Producer[Fx1[Writer[W, ?]], W]) {
    def runLog: List[W] =
      collect[Fx1[Writer[W, ?]], W](p).runWriterLog.run
  }

  implicit class ProducerOperations2[W, U[_]](p: Producer[Fx2[Writer[W, ?], U], W]) {
    def runLog =
      collect[Fx2[Writer[W, ?], U], W](p).runWriterLog
  }

  def action(x: Int) = {
    Thread.sleep(x.toLong)
    x
  }

}
