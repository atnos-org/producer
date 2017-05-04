package org.atnos.producer

import cats.Eval
import cats.data._
import cats.implicits._
import org.atnos.eff._
import org.atnos.eff.all._
import org.atnos.eff.future._
import org.atnos.eff.syntax.all._
import org.atnos.eff.syntax.future._
import org.atnos.origami._
import org.atnos.producer.Producer._
import org.scalacheck._
import org.specs2._
import org.specs2.concurrent.ExecutionEnv
import org.specs2.matcher._

import scala.collection.mutable.ListBuffer

class ProducerEffSpec(implicit ee: ExecutionEnv) extends Specification with ScalaCheck with ThrownExpectations { def is = s2"""

  producers form a monoid with `append` and `empty` $monoid
  producers form a monad with `one` and `flatMap`   $monad
  producers behave like a list monad                $listMonad
  a producer with no effect is a Foldable           $foldable
  a producer can be folded with effects             $effectFoldable
  a producer can be folded with a state effect      $stateEffectFoldable

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
    if we fold over a producer, the final action must be executed *after* the folding is over               $foldingFinalAction

  Producer.append is stacksafe $stacksafeAppend

  It is possible to observe a large list of elements $observeEff

"""

  def monoid = prop { (p1: ProducerWriterInt, p2: ProducerWriterInt, p3: ProducerWriterInt) =>
    Producer.empty[ES, Int] > p1 must produceLike(p1 > Producer.empty)
    p1 > (p2 > p3) must produceLike((p1 > p2) > p3)
  }

  def monad = prop { (a: Int, f: Int => ProducerWriterString, g: String => ProducerWriterOption, h: Option[Int] => ProducerWriterInt) =>
    (one[ES1, Int](a) flatMap f) must produceLike(f(a))
    (f(a) flatMap one[ES1, String]) must produceLike(f(a))
  }

  def listMonad = prop { (f: Int => ProducerString, p1: ProducerInt, p2: ProducerInt) =>
    (Producer.empty[ES0, Int] flatMap f) must runLike(Producer.empty[ES0, String])
    (p1 > p2).flatMap(f) must runLike((p1 flatMap f) > (p2 flatMap f))
  }

  def foldable = prop { list: List[Int] =>
    emit[ES0, Int](list).safeToList ==== list
  }

  def effectFoldable = prop { list: List[Int] =>

    val messages = new ListBuffer[String]
    val producer: ProducerFx[S0, Int] = emitEval[ES0, Int](protect {
      messages.append("input"); list
    })
    val start = protect[S0, Int] {
      messages.append("start"); 0
    }
    val f = (a: Int, b: Int) => protect {
      messages.append(s"adding $a and $b"); a + b
    }
    val end = (s: Int) => protect {
      messages.append("end"); s.toString
    }

    val result = Producer.fold(producer)(start, f, end).execSafe.run.toOption.get
    result ==== list.sum.toString

    messages.toList must contain(atLeast("input", "start", "end")).inOrder.when(list.nonEmpty)

    "the number of additions are of the same size as the list" ==> {
      // drop 2 to remove start and input, dropRight 1 to remove end
      messages.toList.drop(2).dropRight(1) must haveSize(list.size)
    }

  }.noShrink

  def stateEffectFoldable = prop { list: List[Int] =>
    // we want to compute the sum
    type S = Fx.fx2[Safe, State[Int, ?]]

    val producer: ProducerFx[S, Int] = emit[Eff[S, ?], Int](list).mapEval { i =>
      for {
        n <- get[S, Int]
        _ <- put[S, Int](n + i)
      } yield i
    }

    producer.drain.execState(0).execSafe.run must beRight(list.sum)
  }.setGen(Gen.nonEmptyListOf(Gen.choose(1, 10)))

  def emitCollect = prop { xs: List[Int] =>
    emit[ES, Int](xs).runLog ==== xs
  }.noShrink

  def emitFilterCollect = prop { xs: List[Int] =>
    emit[ES, Int](xs).filter(_ > 2).runLog ==== xs.filter(_ > 2)
  }

  def repeat1 = prop { n: Int =>
    collect(repeatValue[ES, Int](1).take(n)).runSafe.runWriterLog.run ==== List.fill(n)(1)
  }.setGen(Gen.choose(0, 10))

  def repeat2 = prop { n: Int =>
    collect(repeat[ES, Int](one(1)).take(n)).runSafe.runWriterLog.run ==== List.fill(n)(1)
  }.setGen(Gen.choose(0, 10))

  def repeat3 = prop { n: Int =>
    repeatEval(tell[S1, String]("hello") >> pure(1)).take(n).runList.execSafe.runWriter.run must
      be_==((Right(List.fill(n)(1)), List.fill(n)("hello")))
  }.setGen(Gen.choose(0, 10))

  def slidingProducer = prop { (xs: List[Int], n: Int) =>
    emit[ESL, Int](xs).sliding(n).runLog.flatten ==== xs
  }.setGen2(Gen.choose(0, 5)).noShrink

  def chunkProducer = prop { (xs: List[Int], n: Int) =>
    emit[ES, Int](xs).chunk(n).runLog ==== xs
  }.setGen2(Gen.choose(0, 5)).noShrink

  def flattenList1 = prop { (xs: List[Int], n: Int) =>
    emit[ES, Int](xs).sliding(n).flattenList.runLog ==== xs
  }.setGen2(Gen.choose(0, 5)).noShrink

  def flattenSeq1 = prop { (xs: List[Int], n: Int) =>
    emit[ES, Int](xs).sliding(n).map(_.toSeq: Seq[Int]).flattenSeq.runLog ==== xs
  }.setGen2(Gen.choose(0, 5)).noShrink

  def flattenProducer1 = prop { (xs: List[Int], n: Int) =>
    emit[ES, ProducerFx[S, Int]](List.fill(n)(emit[ES, Int](xs))).flatten.runLog ==== List.fill(n)(xs).flatten
  }.setGen2(Gen.choose(0, 5)).noShrink

  def map1 = prop { xs: List[Int] =>
    val f = (x: Int) => (x + 1).toString
    emit[ES1, Int](xs).map(f).runLog ==== xs.map(f)
  }.setGen(Gen.listOf(Gen.choose(1, 100)))

  def sequenceFutures = prop { xs: List[Int] =>
    type SF = Fx.fx3[Writer[Int, ?], Safe, TimedFuture]

    implicit val es = ExecutorServices.schedulerFromScheduledExecutorService(ee.scheduledExecutorService)

    doIt[SF](xs).runLog.runAsync must be_==(xs).await

  }.noShrink.setGen(Gen.listOfN(3, Gen.choose(20, 300))).set(minTestsOk = 1)

  def followed = prop { (xs1: List[Int], xs2: List[Int]) =>
    (emit[ES, Int](xs1) > emit(xs2)).runLog ==== xs1 ++ xs2
  }

  def producerInto = prop { xs: List[Int] =>
    emit[ES0, Int](xs).into[S].runLog === xs
  }

  def finalAction = prop { xs: List[Int] =>
    type S = Fx.fx2[Safe, Safe]
    val messages = new ListBuffer[String]

    val producer = emitEval[Eff[S, ?], Int](protect(xs)).map(x => messages.append(x.toString)).andFinally(protect[S, Unit](messages.append("end")))
    producer.to(folds.list.into[Eff[S, ?]]).runSafe.runSafe.run

    messages.toList ==== xs.map(_.toString) :+ "end"
  }

  def foldingFinalAction = prop { xs: List[String] =>
    type R = Fx.fx2[Safe, Eval]
    val messages = new ListBuffer[String]

    val sizeFold: FoldFx[R, Unit, Int] = new FoldFx[R, Unit, Int] {
      type S = Int
      implicit var monad = EffMonad[R]

      def start = pure(0)

      def fold = (s: S, a: Unit) => pure(s + 1)

      def end(s: S) = protect[R, Unit](messages.append("end-fold")).as(s)
    }

    val producer = emitEval[Eff[R, ?], String](protect(xs)).map(x => messages.append(x)).andFinally(protect[R, Unit](messages.append("end")))
    producer.to(sizeFold).runSafe.runEval.run

    messages.toList ==== xs ++ List("end-fold", "end")
  }.setGen(Gen.listOf(Gen.oneOf("a", "b", "c")))

  def stacksafeAppend = {
    val ones = Producer.unfold[Eval, Int, Int](1) { i =>
      if (i >= 10000) None
      else Some((i - 1, 1))
    }

    (one[Eval, Int](1) append ones).take(10).runList.value ==== List.fill(10)(1)
  }

  def observeEff = {
    type R = Fx.fx2[Safe, Eval]
    type ER[A] = Eff[R, A]
    val list: ListBuffer[Int] = new ListBuffer

    val listSink: Sink[ER, Int] = org.atnos.origami.fold.fromSink((i: Int) => protect[R, Unit](list.append(i)))
    range[ER](1, 99999).observe(listSink).runLast.execSafe.runEval.run

    list.toList ==== (1 to 99999).toList
  }

  /**
   * HELPERS
   */

  type WriterInt[A] = Writer[Int, A]
  type WriterOption[A] = Writer[Option[Int], A]
  type WriterString[A] = Writer[String, A]
  type WriterList[A] = Writer[List[Int], A]
  type WriterUnit[A] = Writer[Unit, A]

  type S0 = Fx.fx1[Safe]
  type S  = Fx.fx2[WriterInt, Safe]
  type S1 = Fx.fx2[WriterString, Safe]
  type SL = Fx.fx2[WriterList, Safe]
  type SO = Fx.fx2[WriterOption, Safe]
  type SU = Fx.fx2[WriterUnit, Safe]

  type ES0[A] = Eff[S0, A]
  type ES [A] = Eff[S , A]
  type ES1[A] = Eff[S1, A]
  type ESL[A] = Eff[SL, A]
  type ESO[A] = Eff[SO, A]
  type ESU[A] = Eff[SU, A]

  type ProducerInt = ProducerFx[S0, Int]
  type ProducerString = ProducerFx[S0, String]
  type ProducerWriterInt = ProducerFx[S, Int]
  type ProducerWriterString = ProducerFx[S1, String]
  type ProducerWriterOption = ProducerFx[SO, Option[Int]]

  implicit class ProducerOperations[W](p: ProducerFx[Fx2[Writer[W, ?], Safe], W]) {
    def runLog: List[W] =
      collect[Fx2[Writer[W, ?], Safe], W](p).runWriterLog.execSafe.run.toOption.get
  }

  implicit class EffOperations[W](e: Eff[Fx2[Writer[W, ?], Safe], W]) {
    def runLog: List[W] =
      e.runWriterLog.execSafe.run.toOption.get
  }

  implicit class ProducerOperations2[W, U[_]](p: ProducerFx[Fx3[Writer[W, ?], Safe, U], W]) {
    def runLog =
      collect[Fx3[Writer[W, ?], Safe, U], W](p).runWriterLog.execSafe.map(_.toOption.get)
  }

  def doIt[R :_Safe](xs: List[Int])(implicit future: TimedFuture |= R): ProducerFx[R, Int] =
    emit[Eff[R, ?], Eff[R, Int]](xs.map(x => futureDelay[R, Int](action(x)))).sequence[TimedFuture](4)

  def action(x: Int): Int = {
    Thread.sleep(x.toLong)
    x
  }

  def produceLike[W, A](expected: ProducerFx[Fx.fx2[Writer[W, ?], Safe], W]): Matcher[ProducerFx[Fx.fx2[Writer[W, ?], Safe], W]] =
    (actual: ProducerFx[Fx.fx2[Writer[W, ?], Safe], W]) => actual.runLog ==== expected.runLog

  def runLike[W, A](expected: ProducerFx[Fx1[Safe], W]): Matcher[ProducerFx[Fx1[Safe], W]] =
    (actual: ProducerFx[Fx1[Safe], W]) => actual.safeToList ==== expected.safeToList

  implicit def ArbitraryProducerInt: Arbitrary[ProducerInt] = Arbitrary {
    for {
      n <- Gen.choose(0, 10)
      xs <- Gen.listOfN(n, Gen.choose(1, 30))
    } yield emit[ES0, Int](xs)
  }

  implicit def ArbitraryProducerWriterInt: Arbitrary[ProducerWriterInt] = Arbitrary {
    for {
      n <- Gen.choose(0, 10)
      xs <- Gen.listOfN(n, Gen.choose(1, 30))
    } yield emit[ES, Int](xs)
  }


  implicit def ArbitraryKleisliString: Arbitrary[Int => ProducerString] = Arbitrary {
    Gen.oneOf(
      (i: Int) => Producer.empty[ES0, String],
      (i: Int) => one[ES0, String](i.toString),
      (i: Int) => one[ES0, String](i.toString) > one((i + 1).toString),
      (i: Int) => emit[ES0, String](List(i.toString, (i + 1).toString)))
  }

  implicit def ArbitraryKleisliIntString: Arbitrary[Int => ProducerWriterString] = Arbitrary {
    Gen.oneOf(
      (i: Int) => Producer.empty[ES1, String],
      (i: Int) => one[ES1, String](i.toString),
      (i: Int) => one[ES1, String](i.toString) > one((i + 1).toString))
  }

  implicit def ArbitraryKleisliStringOptionInt: Arbitrary[String => ProducerWriterOption] = Arbitrary {
    Gen.oneOf(
      (i: String) => Producer.empty[ESO, Option[Int]],
      (i: String) => one[ESO, Option[Int]](None),
      (i: String) => one[ESO, Option[Int]](Option(i.size)) > one(Option(i.size * 2)))
  }

  implicit def ArbitraryKleisliOptionIntInt: Arbitrary[Option[Int] => ProducerWriterInt] = Arbitrary {
    Gen.oneOf(
      (i: Option[Int]) => Producer.empty[ES, Int],
      (i: Option[Int]) => one[ES, Int](i.getOrElse(-2)),
      (i: Option[Int]) => one[ES, Int](3) > one(i.map(_ + 1).getOrElse(0)))
  }

  implicit class ProducerOperations3[A](p: ProducerFx[Fx1[Safe], A]) {
    def safeToList =
      p.runList.execSafe.run.toOption.get
  }

}

