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

  first                  $firstElement
  last                   $lastElement
  scan                   $scanElements
  scan1                  $scan1Elements
  reduceMap              $reduceElements

  A transducer can transform a producer statefully
    state            $stateTransducer
    stateEval        $stateEvalTransducer
    stateEff         $stateEffTransducer
    with a producer  $producerStateTransducer

"""
  type S = Fx.fx1[Safe]
  type ES[A] = Eff[S, A]

  def transduced = prop { xs: List[Int] =>
    val f = (x: Int) => (x+ 1).toString

    (emit[ES, Int](xs) |> transducer(f)).safeToList ==== xs.map(f)
  }.setGen(Gen.listOf(Gen.choose(1, 100)))

  def received = prop { xs: List[Int] =>
    val f = (x: Int) => (x+ 1).toString

    def plusOne[R :_Safe] =
      receive[Eff[R, ?], Int, String](a => one(f(a)))

    (emit[ES, Int](xs) |> plusOne).safeToList ==== xs.map(f)
  }.setGen(Gen.listOf(Gen.choose(1, 100)))

  def receivedOr = prop { xs: List[Int] =>
    val f = (x: Int) => (x+ 1).toString

    def plusOne[R :_Safe] =
      receiveOr[Eff[R, ?], Int, String](a => one(f(a)))(emit(List("1", "2", "3")))

    (Producer.done[ES, Int] |> plusOne).safeToList ==== List("1", "2", "3")
  }.setGen(Gen.listOf(Gen.choose(1, 100)))

  def receivedOption = prop { xs: List[Int] =>
    (emit[ES, Int](xs).receiveOption).safeToList ==== xs.map(Option(_)) ++ List(None)
  }.setGen(Gen.listOf(Gen.choose(1, 100)))

  def takeN = prop { (xs: List[Int], n: Int) =>
    (emit[ES, Int](xs).take(n)).safeToList ==== xs.take(n)
  }.setGen2(Gen.choose(0, 10))

  def takeException = prop { n: Int =>
    type R = Fx.fx2[WriterInt, Safe]
    type ER[A] = Eff[R, A]

    val producer = emit[ER, Int](List(1)) append emitEval[ER, Int](protect { throw new Exception("boom"); List(2) })
    (producer.take(1)).runLog ==== List(1)
  }

  def zipWithNextElement = prop { xs: List[Int] =>
    (emit[ES, Int](xs).zipWithNext).safeToList ==== (xs zip (xs.drop(1).map(Option(_)) :+ None))
  }

  def zipWithPreviousElement = prop { xs: List[Int] =>
    (emit[ES, Int](xs).zipWithPrevious).safeToList ==== ((None +: xs.dropRight(1).map(Option(_))) zip xs)
  }

  def zipWithPreviousAndNextElement = prop { xs: List[Int] =>
    val ls = emit[ES, Int](xs)
    (ls.zipWithPreviousAndNext).safeToList ====
      (ls.zipWithPrevious).zip(ls.zipWithNext).map { case ((previous, a), (_, next)) => (previous, a, next) }.safeToList
  }

  def zipWithIndex1 = prop { xs: List[Int] =>
    emit[ES, Int](xs).zipWithIndex.safeToList ==== xs.zipWithIndex
  }

  def intersperse1 = prop { (xs: List[String], c: String) =>
    emit[ES, String](xs).intersperse(c).safeToList ==== intersperse(xs, c)
  }.noShrink.setGens(Gen.choose(0, 5).flatMap(n => Gen.listOfN(n, Gen.identifier)), Gen.oneOf("-", "_", ":"))

  def firstElement = prop { xs: List[Int] =>
    emit[ES, Int](xs).first.safeToList ==== xs.headOption.toList
  }

  def lastElement = prop { xs: List[Int] =>
    emit[ES, Int](xs).last.safeToList ==== xs.lastOption.toList
  }

  def scanElements = prop { (xs: List[Int], s: Char) =>
    val f = (s1: String, i1: Int) => s1 + i1.toString
    emit[ES, Int](xs).scan(s.toString)(f).safeToList ==== xs.scanLeft(s.toString)(f).toList
  }.setGens(Gen.listOf(Gen.choose(1, 5)), Gen.choose('a', 'z'))

  def scan1Elements = prop { xs: List[Int] =>
    val f = (i: Int, j: Int) => i + j
    emit[ES, Int](xs).scan1(f).safeToList ==== xs.headOption.toList.flatMap(h => xs.drop(1).scanLeft(h)(f))
  }.setGen(Gen.listOf(Gen.choose(1, 5)))

  def reduceElements = prop { xs: List[String] =>
    emit[ES, String](xs).reduceMap(_.size).safeToList ==== (if (xs.isEmpty) Nil else List(xs.map(_.size).sum))
  }

  def stateTransducer = prop { xs: List[Int] =>
    val t = transducers.state[ES, Int, String, Int](0) { case (i: Int, sum: Int) =>
      (s"$i and sum: $sum", sum + i)
    }

    t(emit[ES, Int](xs)).safeToList ==== (xs zip xs.scanLeft(0)(_ + _)).map { case (i, s) => s"$i and sum: $s" }
  }

  def stateEvalTransducer = prop { xs: List[Int] =>
    val t = transducers.stateEffEval[S, Int, String, Int](0) { case (i: Int, sum: Int) =>
      protect((s"$i and sum: $sum", sum + i))
    }

    t(emit[ES, Int](xs)).safeToList ==== (xs zip xs.scanLeft(0)(_ + _)).map { case (i, s) => s"$i and sum: $s" }
  }

  def stateEffTransducer = prop { xs: List[Int] =>
    val t = transducers.stateEff[S, Int, String, Int](0) { case (i: Int, sum: Int) =>
      (protect(s"$i and sum: $sum"), sum + i)
    }

    t(emit[ES, Int](xs)).flatMap(n => oneEval(n)).safeToList ==== (xs zip xs.scanLeft(0)(_ + _)).map { case (i, s) => s"$i and sum: $s" }
  }

  def producerStateTransducer = prop { xs: List[Int] =>
    val t = transducers.producerState[Eff[S, ?], Int, String, Int](0, Option((i: Int) => one(i.toString))) { case (i: Int, sum: Int) =>
      (one(s"$i and sum: $sum"), sum + i)
    }

    t(emit[ES, Int](xs)).safeToList ==== ((xs zip xs.scanLeft(0)(_ + _)).map { case (i, s) => s"$i and sum: $s" } :+ xs.sum.toString)
  }

  /**
   * HELPERS
   */

  type WriterInt[A]    = Writer[Int, A]

  implicit class ProducerOperations[W](p: ProducerFx[Fx2[Writer[W, ?], Safe], W]) {
    def runLog: List[W] =
      collect[Fx2[Writer[W, ?], Safe], W](p).runWriterLog.runSafe.run._1.toOption.getOrElse(Nil)
  }

  implicit class ProducerOperations2[W, U[_]](p: ProducerFx[Fx3[Writer[W, ?], U, Safe], W]) {
    def runLog =
      collect[Fx3[Writer[W, ?], U, Safe], W](p).runWriterLog.runSafe.map(_._1.toOption.getOrElse(Nil))
  }

  implicit class ProducerOperations3[A](p: ProducerFx[Fx1[Safe], A]) {
    def safeToList =
      p.runList.runSafe.run._1.toOption.getOrElse(Nil)
  }

  def intersperse[A](as: List[A], a: A): List[A] =
    if (as.isEmpty) Nil else as.init.foldRight(as.last +: Nil)(_ +: a +: _)


}


