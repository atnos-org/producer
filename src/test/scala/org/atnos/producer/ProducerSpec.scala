package org.atnos.producer

import org.atnos.eff._
import org.atnos.eff.all._
import org.atnos.producer.Producer._
import org.scalacheck._
import org.specs2._
import org.specs2.matcher._
import org.specs2.concurrent.ExecutionEnv

import scala.concurrent.Future
import cats.implicits._
import cats.Eval
import cats.data.Writer
import transducers._
import org.atnos.eff._, all._, syntax.all._

import scala.collection.mutable.ListBuffer

class ProducerSpec(implicit ee: ExecutionEnv) extends Specification with ScalaCheck with ThrownExpectations { def is = s2"""

  producers form a monoid with `append` and `empty` $monoid
  producers form a monad with `one` and `flatMap`   $monad
  producers behave like a list monad                $listMonad
  a producer with no effect is a Foldable           $foldable
  a producer can be folded with effects             $effectFoldable

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

  a producer can be injected into a larger set of effects $producerInto

  take(n) $takeN
  take(n) + exception $takeException

"""

  def monoid = prop { (p1: ProducerWriterInt, p2: ProducerWriterInt, p3: ProducerWriterInt) =>
    Producer.empty > p1 must produceLike(p1 > Producer.empty)
    p1 > (p2 > p3) must produceLike((p1 > p2) > p3)
  }

  def monad = prop { (a: Int, f: Int => ProducerWriterString, g: String => ProducerWriterOption, h: Option[Int] => ProducerWriterInt) =>
    (one(a) flatMap f) must produceLike(f(a))
    (f(a) flatMap one) must produceLike(f(a))
  }

  def listMonad = prop { (f: Int => ProducerString, p1: ProducerInt, p2: ProducerInt) =>
    (Producer.empty[NoFx, Int] flatMap f) must runLike(Producer.empty[NoFx, String])
    (p1 > p2).flatMap(f) must runLike((p1 flatMap f) > (p2 flatMap f))
  }

  def foldable = prop { list: List[Int] =>
    emit[NoFx, Int](list).toList ==== list
  }

  def effectFoldable = prop { list: List[Int] =>
    type S = Fx.fx1[Eval]

    val messages = new ListBuffer[String]
    val producer: Producer[S, Int] = emitEff[S, Int](delay { messages.append("input"); list })
    val start = delay[S, Int] { messages.append("start"); 0 }
    val f = (a: Int, b: Int) => { messages.append(s"adding $a and $b"); a + b }
    val end = (s: Int) => delay[S, String] { messages.append("end"); s.toString }

    val result = fold(producer)(start, f, end).runEval.run
    result ==== list.foldLeft(0)(_ + _).toString

    messages.toList must contain(atLeast("start", "input", "end")).inOrder.when(list.nonEmpty)

    "the number of additions are of the same size as the list" ==> {
      // drop 2 to remove start and input, dropRight 1 to remove end
      messages.toList.drop(2).dropRight(1) must haveSize(list.size)
    }

  }.noShrink

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
      receive[R, Int, String](a => one(f(a)))

    (emit[S1, Int](xs) |> plusOne).runLog ==== xs.map(f)
  }.setGen(Gen.listOf(Gen.choose(1, 100)))

  def receivedOr = prop { xs: List[Int] =>
    val f = (x: Int) => (x+ 1).toString

    def plusOne[R] =
      receiveOr[R, Int, String](a => one(f(a)))(emit(List("1", "2", "3")))

    (Producer.done[S1, Int] |> plusOne).runLog ==== List("1", "2", "3")
  }.setGen(Gen.listOf(Gen.choose(1, 100)))

  def receivedOption = prop { xs: List[Int] =>
    (emit[SO, Int](xs) |> receiveOption).runLog ==== xs.map(Option(_)) ++ List(None)
  }.setGen(Gen.listOf(Gen.choose(1, 100)))

  def producerInto = prop { xs: List[Int] =>
    emit[Fx.fx1[Eval], Int](xs).into[Fx.fx2[WriterInt, Eval]].runLog.runEval.run === xs
  }

  def takeN = prop { (xs: List[Int], n: Int) =>
    (emit[S, Int](xs) |> take(n)).runLog ==== xs.take(n)
  }.setGen2(Gen.choose(0, 10))

  def takeException = prop { n: Int =>
    type R = Fx.fx2[WriterInt, Eval]

    val producer = emit[R, Int](List(1)) append emitEff[R, Int](delay { throw new Exception("boom"); List(1) })
    (producer |> take(1)).runLog.runEval.run ==== List(1)
  }

  /**
   * HELPERS
   */

  type WriterInt[A]    = Writer[Int, A]
  type WriterOption[A] = Writer[Option[Int], A]
  type WriterString[A] = Writer[String, A]
  type WriterList[A]   = Writer[List[Int], A]

  type S = Fx.fx1[WriterInt]
  type S1 = Fx.fx1[WriterString]
  type SL = Fx.fx1[WriterList]
  type SO = Fx.fx1[WriterOption]

  type ProducerInt          = Producer[NoFx, Int]
  type ProducerString       = Producer[NoFx, String]
  type ProducerWriterInt    = Producer[Fx.fx1[WriterInt], Int]
  type ProducerWriterString = Producer[Fx.fx1[WriterString], String]
  type ProducerWriterOption = Producer[Fx.fx1[WriterOption], Option[Int]]

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

  def produceLike[W, A](expected: Producer[Fx.fx1[Writer[W, ?]], W]): Matcher[Producer[Fx.fx1[Writer[W, ?]], W]] =
    (actual: Producer[Fx.fx1[Writer[W, ?]], W]) => actual.runLog ==== expected.runLog

  def runLike[W, A](expected: Producer[NoFx, W]): Matcher[Producer[NoFx, W]] =
    (actual: Producer[NoFx, W]) => actual.toList ==== expected.toList

  implicit def ArbitraryProducerInt: Arbitrary[ProducerInt] = Arbitrary {
    for {
      n  <- Gen.choose(0, 10)
      xs <- Gen.listOfN(n, Gen.choose(1, 30))
    } yield emit[NoFx, Int](xs)
  }

  implicit def ArbitraryProducerWriterInt: Arbitrary[ProducerWriterInt] = Arbitrary {
    for {
      n  <- Gen.choose(0, 10)
      xs <- Gen.listOfN(n, Gen.choose(1, 30))
    } yield emit[Fx1[WriterInt], Int](xs)
  }


  implicit def ArbitraryKleisliString: Arbitrary[Int => ProducerString] = Arbitrary {
    Gen.oneOf(
      (i: Int) => Producer.empty[NoFx, String],
      (i: Int) => one[NoFx, String](i.toString),
      (i: Int) => one[NoFx, String](i.toString) > one((i + 1).toString))
  }

  implicit def ArbitraryKleisliIntString: Arbitrary[Int => ProducerWriterString] = Arbitrary {
    Gen.oneOf(
      (i: Int) => Producer.empty[Fx.fx1[WriterString], String],
      (i: Int) => one[Fx.fx1[WriterString], String](i.toString),
      (i: Int) => one[Fx.fx1[WriterString], String](i.toString) > one((i + 1).toString))
  }

  implicit def ArbitraryKleisliStringOptionInt: Arbitrary[String => ProducerWriterOption] = Arbitrary {
    Gen.oneOf(
      (i: String) => Producer.empty[Fx.fx1[WriterOption], Option[Int]],
      (i: String) => one[Fx.fx1[WriterOption], Option[Int]](None),
      (i: String) => one[Fx.fx1[WriterOption], Option[Int]](Option(i.size)) > one(Option(i.size * 2)))
  }

  implicit def ArbitraryKleisliOptionIntInt: Arbitrary[Option[Int] => ProducerWriterInt] = Arbitrary {
    Gen.oneOf(
      (i: Option[Int]) => Producer.empty[Fx.fx1[WriterInt], Int],
      (i: Option[Int]) => one[Fx.fx1[WriterInt], Int](i.getOrElse(-2)),
      (i: Option[Int]) => one[Fx.fx1[WriterInt], Int](3) > one(i.map(_ + 1).getOrElse(0)))
  }
}

