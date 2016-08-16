package org.atnos.producer
import Producer._
import org.scalacheck._
import org.specs2._
import cats._
import cats.data._
import cats.implicits._
import transducers._
import org.atnos.eff._
import org.atnos.eff.all._
import org.atnos.eff.syntax.all._

class TransducerSpec extends Specification with ScalaCheck { def is = s2"""

  a producer can be modified by a transducer $transduced
  a producer can be modified by a receiver   $received
  a receiver can run another producer if the first one is empty  $receivedOr

  take(n) $takeN
  take(n) + exception $takeException

  zipWithPrevious        $zipWithPreviousElement
  zipWithNext            $zipWithNextElement
  zipWithPreviousAndNext $zipWithPreviousAndNextElement
  zipWithIndex           $zipWithIndex1
  intersperse            $intersperse1

"""
  type S = Fx.fx1[Eval]
  
  def transduced = prop { xs: List[Int] =>
    val f = (x: Int) => (x+ 1).toString

    (emit[S, Int](xs) |> transducer(f)).evalToList ==== xs.map(f)
  }.setGen(Gen.listOf(Gen.choose(1, 100)))

  def received = prop { xs: List[Int] =>
    val f = (x: Int) => (x+ 1).toString

    def plusOne[R :_eval] =
      receive[R, Int, String](a => one(f(a)))

    (emit[S, Int](xs) |> plusOne).evalToList ==== xs.map(f)
  }.setGen(Gen.listOf(Gen.choose(1, 100)))

  def receivedOr = prop { xs: List[Int] =>
    val f = (x: Int) => (x+ 1).toString

    def plusOne[R :_eval] =
      receiveOr[R, Int, String](a => one(f(a)))(emit(List("1", "2", "3")))

    (Producer.done[S, Int] |> plusOne).evalToList ==== List("1", "2", "3")
  }.setGen(Gen.listOf(Gen.choose(1, 100)))

  def receivedOption = prop { xs: List[Int] =>
    (emit[S, Int](xs) |> receiveOption).evalToList ==== xs.map(Option(_)) ++ List(None)
  }.setGen(Gen.listOf(Gen.choose(1, 100)))

  def takeN = prop { (xs: List[Int], n: Int) =>
    (emit[S, Int](xs) |> take(n)).evalToList ==== xs.take(n)
  }.setGen2(Gen.choose(0, 10))

  def takeException = prop { n: Int =>
    type R = Fx.fx2[WriterInt, Eval]

    val producer = emit[R, Int](List(1)) append emitEff[R, Int](delay { throw new Exception("boom"); List(1) })
    (producer |> take(1)).runLog ==== List(1)
  }

  def zipWithNextElement = prop { xs: List[Int] =>
    (emit[S, Int](xs) |> zipWithNext).evalToList ==== (xs zip (xs.drop(1).map(Option(_)) :+ None))
  }

  def zipWithPreviousElement = prop { xs: List[Int] =>
    (emit[S, Int](xs) |> zipWithPrevious).evalToList ==== ((None +: xs.dropRight(1).map(Option(_))) zip xs)
  }

  def zipWithPreviousAndNextElement = prop { xs: List[Int] =>
    val ls = emit[S, Int](xs)
    (ls |> zipWithPreviousAndNext).evalToList ====
      (ls |> zipWithPrevious).zip(ls |> zipWithNext).map { case ((previous, a), (_, next)) => (previous, a, next) }.evalToList
  }

  def zipWithIndex1 = prop { xs: List[Int] =>
    emit[S, Int](xs).zipWithIndex.evalToList ==== xs.zipWithIndex
  }

  def intersperse1 = prop { (xs: List[String], c: String) =>
    emit[S, String](xs).intersperse(c).evalToList ==== intersperse(xs, c)
  }.noShrink.setGens(Gen.choose(0, 5).flatMap(n => Gen.listOfN(n, Gen.identifier)), Gen.oneOf("-", "_", ":"))

  /**
   * HELPERS
   */

  type WriterInt[A]    = Writer[Int, A]

  implicit class ProducerOperations[W](p: Producer[Fx2[Writer[W, ?], Eval], W]) {
    def runLog: List[W] =
      collect[Fx2[Writer[W, ?], Eval], W](p).runWriterLog.runEval.run
  }

  implicit class ProducerOperations2[W, U[_]](p: Producer[Fx3[Writer[W, ?], U, Eval], W]) {
    def runLog =
      collect[Fx3[Writer[W, ?], U, Eval], W](p).runWriterLog.runEval
  }

  implicit class ProducerOperations3[A](p: Producer[Fx1[Eval], A]) {
    def evalToList =
      p.runList.runEval.run
  }

  def intersperse[A](as: List[A], a: A): List[A] =
    if (as.isEmpty) Nil else as.init.foldRight(as.last +: Nil)(_ +: a +: _)


}


