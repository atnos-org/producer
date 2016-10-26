package org.atnos.producer

import org.atnos.eff._
import org.atnos.eff.all._
import org.atnos.eff.syntax.all._
import org.atnos.producer.Producer._
import org.scalacheck._
import org.specs2._
import org.specs2.matcher._
import XorMatchers._
import org.specs2.concurrent.ExecutionEnv

import scala.concurrent.Future
import cats.implicits._
import cats.data._
import transducers._
import org.atnos.eff._
import all._
import org.atnos.origami._
import org.atnos.origami.folds._
import syntax.all._

import scala.collection.mutable.ListBuffer

class ProducerSpec(implicit ee: ExecutionEnv) extends Specification with ScalaCheck with ThrownExpectations { def is = s2"""

  producers form a monoid with `append` and `empty` $monoid
  producers form a monad with `one` and `flatMap`   $monad
  producers behave like a list monad                $listMonad
  a producer with no effect is a Foldable           $foldable
  a producer can be folded with effects             $effectFoldable

  emit / collect             $emitCollect
  emit / filter / collect    $emitFilterCollect
  repeat a value             $repeat1
  repeat a producer          $repeat2
  repeat an effect           $repeat3
  slide                      $slidingProducer
  chunk                      $chunkProducer
  flattenList                $flattenList1
  flattenSeq                 $flattenSeq1
  flattenProducer            $flattenProducer1
  map                        $map1
  sequence futures           $sequenceFutures

  a producer can be followed by another one               $followed
  a producer can be injected into a larger set of effects $producerInto

  a producer can ensure resource safety
    it is possible to register an action which will only be executed when the producer has finished running $finalAction

"""

  def monoid = prop { (p1: ProducerWriterInt, p2: ProducerWriterInt, p3: ProducerWriterInt) =>
    Producer.empty[S, Int] > p1 must produceLike(p1 > Producer.empty)
    p1 > (p2 > p3) must produceLike((p1 > p2) > p3)
  }

  def monad = prop { (a: Int, f: Int => ProducerWriterString, g: String => ProducerWriterOption, h: Option[Int] => ProducerWriterInt) =>
    (one[S1, Int](a) flatMap f) must produceLike(f(a))
    (f(a) flatMap one[S1, String]) must produceLike(f(a))
  }

  def listMonad = prop { (f: Int => ProducerString, p1: ProducerInt, p2: ProducerInt) =>
    (Producer.empty[S0, Int] flatMap f) must runLike(Producer.empty[S0, String])
    (p1 > p2).flatMap(f) must runLike((p1 flatMap f) > (p2 flatMap f))
  }

  def foldable = prop { list: List[Int] =>
    emit[S0, Int](list).safeToList ==== list
  }

  def effectFoldable = prop { list: List[Int] =>
    type S = Fx.fx1[Safe]

    val messages = new ListBuffer[String]
    val producer: Producer[S, Int] = emitEff[S, Int](protect { messages.append("input"); list })
    val start = protect[S, Int] { messages.append("start"); 0 }
    val f = (a: Int, b: Int) => { messages.append(s"adding $a and $b"); a + b }
    val end = (s: Int) => protect[S, String] { messages.append("end"); s.toString }

    val result = Producer.fold(producer)(start, f, end).execSafe.run.toOption.get
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

  def repeat1 = prop { n: Int =>
    collect(repeatValue[S, Int](1).take(n)).runSafe.runWriterLog.run ==== List.fill(n)(1)
  }.setGen(Gen.choose(0, 10))

  def repeat2 = prop { n: Int =>
    collect(repeat[S, Int](one(1)).take(n)).runSafe.runWriterLog.run ==== List.fill(n)(1)
  }.setGen(Gen.choose(0, 10))

  def repeat3 = prop { n: Int =>
    repeatEval(tell[S1, String]("hello") >> pure(1)).take(n).runList.execSafe.runWriter.run must
      be_==((Xor.Right(List.fill(n)(1)), List.fill(n)("hello")))
  }.setGen(Gen.choose(0, 10))

  def slidingProducer = prop { (xs: List[Int], n: Int) =>
    emit[SL, Int](xs).sliding(n).runLog.flatten ==== xs
  }.setGen2(Gen.choose(0, 5)).noShrink

  def chunkProducer = prop { (xs: List[Int], n: Int) =>
    emit[S, Int](xs).chunk(n).runLog ==== xs
  }.setGen2(Gen.choose(0, 5)).noShrink

  def flattenList1 = prop { (xs: List[Int], n: Int) =>
    emit[S, Int](xs).sliding(n).flattenList.runLog ==== xs
  }.setGen2(Gen.choose(0, 5)).noShrink

  def flattenSeq1 = prop { (xs: List[Int], n: Int) =>
    emit[S, Int](xs).sliding(n).map(_.toSeq: Seq[Int]).flattenSeq.runLog ==== xs
  }.setGen2(Gen.choose(0, 5)).noShrink

  def flattenProducer1 = prop { (xs: List[Int], n: Int) =>
    emit[S, Producer[S, Int]](List.fill(n)(emit[S, Int](xs))).flatten.runLog ==== List.fill(n)(xs).flatten
  }.setGen2(Gen.choose(0, 5)).noShrink

  def map1 = prop { xs: List[Int] =>
    val f = (x: Int) => (x+ 1).toString
    emit[S1, Int](xs).map(f).runLog ==== xs.map(f)
  }.setGen(Gen.listOf(Gen.choose(1, 100)))

  def sequenceFutures = prop { xs: List[Int] =>
    type SF = Fx.fx3[Writer[Int, ?], Safe, Future]

    def doIt[R :_safe](implicit f: Future |= R): Producer[R, Int] =
      sequence[R, Future, Int](4)(emit(xs.map(x => async(action(x)))))

    doIt[SF].runLog.detach must be_==(xs).await
  }.noShrink.setGen(Gen.listOfN(3, Gen.choose(20, 300))).set(minTestsOk = 1)

  def followed = prop { (xs1: List[Int], xs2: List[Int]) =>
    (emit[S, Int](xs1) > emit(xs2)).runLog ==== xs1 ++ xs2
  }

  def producerInto = prop { xs: List[Int] =>
    emit[Fx.fx1[Safe], Int](xs).into[Fx.fx2[WriterInt, Safe]].runLog === xs
  }

  def finalAction = prop { xs: List[Int] =>
    type S = Fx.fx2[Safe, Safe]
    val messages = new ListBuffer[String]

    val producer = emitEff[S, Int](protect(xs)).map(x => messages.append(x.toString)).andFinally(protect[S, Unit](messages.append("end")))
    producer.to(folds.list.into[S]).runSafe.runSafe

    messages.toList ==== xs.toList.map(_.toString) :+ "end"
  }

  /**
   * HELPERS
   */

  type WriterInt[A]    = Writer[Int, A]
  type WriterOption[A] = Writer[Option[Int], A]
  type WriterString[A] = Writer[String, A]
  type WriterList[A]   = Writer[List[Int], A]
  type WriterUnit[A]   = Writer[Unit, A]

  type S0 = Fx.fx1[Safe]
  type S  = Fx.fx2[WriterInt, Safe]
  type S1 = Fx.fx2[WriterString, Safe]
  type SL = Fx.fx2[WriterList, Safe]
  type SO = Fx.fx2[WriterOption, Safe]
  type SU  = Fx.fx2[WriterUnit, Safe]

  type ProducerInt          = Producer[Fx1[Safe], Int]
  type ProducerString       = Producer[Fx1[Safe], String]
  type ProducerWriterInt    = Producer[Fx.fx2[WriterInt, Safe], Int]
  type ProducerWriterString = Producer[Fx.fx2[WriterString, Safe], String]
  type ProducerWriterOption = Producer[Fx.fx2[WriterOption, Safe], Option[Int]]

  implicit class ProducerOperations[W](p: Producer[Fx2[Writer[W, ?], Safe], W]) {
    def runLog: List[W] =
      collect[Fx2[Writer[W, ?], Safe], W](p).runWriterLog.execSafe.run.toOption.get
  }

  implicit class EffOperations[W](e: Eff[Fx2[Writer[W, ?], Safe], W]) {
    def runLog: List[W] =
      e.runWriterLog.execSafe.run.toOption.get
  }

  implicit class ProducerOperations2[W, U[_]](p: Producer[Fx3[Writer[W, ?], Safe, U], W]) {
    def runLog =
      collect[Fx3[Writer[W, ?], Safe, U], W](p).runWriterLog.execSafe.map(_.toOption.get)
  }

  def action(x: Int) = {
    Thread.sleep(x.toLong)
    x
  }

  def produceLike[W, A](expected: Producer[Fx.fx2[Writer[W, ?], Safe], W]): Matcher[Producer[Fx.fx2[Writer[W, ?], Safe], W]] =
    (actual: Producer[Fx.fx2[Writer[W, ?], Safe], W]) => actual.runLog ==== expected.runLog

  def runLike[W, A](expected: Producer[Fx1[Safe], W]): Matcher[Producer[Fx1[Safe], W]] =
    (actual: Producer[Fx1[Safe], W]) => actual.safeToList ==== expected.safeToList

  implicit def ArbitraryProducerInt: Arbitrary[ProducerInt] = Arbitrary {
    for {
      n  <- Gen.choose(0, 10)
      xs <- Gen.listOfN(n, Gen.choose(1, 30))
    } yield emit[S0, Int](xs)
  }

  implicit def ArbitraryProducerWriterInt: Arbitrary[ProducerWriterInt] = Arbitrary {
    for {
      n  <- Gen.choose(0, 10)
      xs <- Gen.listOfN(n, Gen.choose(1, 30))
    } yield emit[Fx2[WriterInt, Safe], Int](xs)
  }


  implicit def ArbitraryKleisliString: Arbitrary[Int => ProducerString] = Arbitrary {
    Gen.oneOf(
      (i: Int) => Producer.empty[Fx1[Safe], String],
      (i: Int) => one[Fx1[Safe], String](i.toString),
      (i: Int) => one[Fx1[Safe], String](i.toString) > one((i + 1).toString),
      (i: Int) => emit[Fx1[Safe], String](List(i.toString, (i + 1).toString)))
  }

  implicit def ArbitraryKleisliIntString: Arbitrary[Int => ProducerWriterString] = Arbitrary {
    Gen.oneOf(
      (i: Int) => Producer.empty[Fx.fx2[WriterString, Safe], String],
      (i: Int) => one[Fx.fx2[WriterString, Safe], String](i.toString),
      (i: Int) => one[Fx.fx2[WriterString, Safe], String](i.toString) > one((i + 1).toString))
  }

  implicit def ArbitraryKleisliStringOptionInt: Arbitrary[String => ProducerWriterOption] = Arbitrary {
    Gen.oneOf(
      (i: String) => Producer.empty[Fx.fx2[WriterOption, Safe], Option[Int]],
      (i: String) => one[Fx.fx2[WriterOption, Safe], Option[Int]](None),
      (i: String) => one[Fx.fx2[WriterOption, Safe], Option[Int]](Option(i.size)) > one(Option(i.size * 2)))
  }

  implicit def ArbitraryKleisliOptionIntInt: Arbitrary[Option[Int] => ProducerWriterInt] = Arbitrary {
    Gen.oneOf(
      (i: Option[Int]) => Producer.empty[Fx.fx2[WriterInt, Safe], Int],
      (i: Option[Int]) => one[Fx.fx2[WriterInt, Safe], Int](i.getOrElse(-2)),
      (i: Option[Int]) => one[Fx.fx2[WriterInt, Safe], Int](3) > one(i.map(_ + 1).getOrElse(0)))
  }

  implicit class ProducerFolds[R :_safe, A](p: Producer[R, A]) {
    def to[B](f: Fold[R, A, B]): Eff[R, B] =
      p.fold[B, f.S](f.start, f.fold, f.end)

    def observe(f: Fold[R, A, Unit]): Producer[R, A] =
      producers.observe(p)(f.start, f.fold, f.end)
  }

  implicit class ProducerOperations3[A](p: Producer[Fx1[Safe], A]) {
    def safeToList =
    p.runList.execSafe.run.toOption.get
  }

}

