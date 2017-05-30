package org.atnos.producer

import org.scalacheck._
import org.specs2._
import org.specs2.matcher._
import cats._
import cats.data.NonEmptyList
import cats.implicits._
import org.atnos.producer.producers._

import scala.collection.mutable.ListBuffer

class ProducerSpec extends Specification with ScalaCheck with ThrownExpectations { def is = s2"""

  producers form a monoid with `append` and `empty` $monoid
  a producer with no effect is a Foldable           $foldable
  a producer can be folded with effects             $effectFoldable

  emit / collect             $emitCollect
  emit / filter / collect    $emitFilterCollect
  repeat a value             $repeat1
  repeat a producer          $repeat2
  slide                      $slidingProducer
  chunk                      $chunkProducer
  flattenList                $flattenList1
  flattenSeq                 $flattenSeq1
  flattenProducer            $flattenProducer1
  map                        $map1
  zip                        $zip1

  a producer can be followed by another one $followed
  Producer.append is stacksafe              $stacksafeAppend

  unfold operations can be used to create a Producer
    producing one element at the time                  $unfold1
    producing one element monadically at the time      $unfold2
    producing several elements at the time             $unfold3
    producing several elements monadically at the time $unfold4

"""

  def monoid = prop { (p1: ProducerInt, p2: ProducerInt, p3: ProducerInt) =>
    Producer.empty > p1 must runLike(p1 > Producer.empty)
    p1 > (p2 > p3) must runLike((p1 > p2) > p3)
  }

  def foldable = prop { list: List[Int] =>
    emit(list).runList.value ==== list
  }

  def effectFoldable = prop { list: List[Int] =>

    val messages = new ListBuffer[String]
    val producer: Producer[Eval, Int] = emitEval(Eval.later {
      messages.append("input"); list
    })
    val start = Eval.later {
      messages.append("start"); 0
    }
    val f = (a: Int, b: Int) => Eval.later {
      messages.append(s"adding $a and $b"); a + b
    }
    val end = (s: Int) => Eval.later {
      messages.append("end"); s.toString
    }

    val result = Producer.fold(producer)(start, f, end).value
    result ==== list.sum.toString

    messages.toList must contain(atLeast("input", "start", "end")).inOrder.when(list.nonEmpty)

    "the number of additions are of the same size as the list" ==> {
      // drop 2 to remove start and input, dropRight 1 to remove end
      messages.toList.drop(2).dropRight(1) must haveSize(list.size)
    }

  }.noShrink

  def emitCollect = prop { xs: List[Int] =>
    emit(xs).runList.value ==== xs
  }.noShrink

  def emitFilterCollect = prop { xs: List[Int] =>
    emit(xs).filter(_ > 2).runList.value ==== xs.filter(_ > 2)
  }

  def repeat1 = prop { n: Int =>
    repeatValue(1).take(n).runList.value ==== List.fill(n)(1)
  }.setGen(Gen.choose(0, 10))

  def repeat2 = prop { n: Int =>
    repeat(one(1)).take(n).runList.value ==== List.fill(n)(1)
  }.setGen(Gen.choose(0, 10))

  def slidingProducer = prop { (xs: List[Int], n: Int) =>
    emit(xs).sliding(n).runList.value.flatten ==== xs
  }.setGen2(Gen.choose(0, 5)).noShrink

  def chunkProducer = prop { (xs: List[Int], n: Int) =>
    emit(xs).chunk(n).runList.value ==== xs
  }.setGen2(Gen.choose(0, 5)).noShrink

  def flattenList1 = prop { (xs: List[Int], n: Int) =>
    emit(xs).sliding(n).flattenList.runList.value ==== xs
  }.setGen2(Gen.choose(0, 5)).noShrink

  def flattenSeq1 = prop { (xs: List[Int], n: Int) =>
    emit(xs).sliding(n).map(_.toSeq: Seq[Int]).flattenSeq.runList.value ==== xs
  }.setGen2(Gen.choose(0, 5)).noShrink

  def flattenProducer1 = prop { (xs: List[Int], n: Int) =>
    emit(List.fill(n)(emit(xs))).flatten.runList.value ==== List.fill(n)(xs).flatten
  }.setGen2(Gen.choose(0, 5)).noShrink

  def map1 = prop { xs: List[Int] =>
    val f = (x: Int) => (x + 1).toString
    emit(xs).map(f).runList.value ==== xs.map(f)
  }.setGen(Gen.listOf(Gen.choose(1, 100)))

  def zip1 = prop { (xs: ProducerInt, ys: ProducerInt) =>
    (xs zip ys).runList.value ==== (xs.runList.value zip ys.runList.value)
  }

  def followed = prop { (xs1: List[Int], xs2: List[Int]) =>
    (emit(xs1) > emit(xs2)).runList.value ==== xs1 ++ xs2
  }

  def stacksafeAppend = {
    val ones = Producer.unfold[Eval, Int, Int](1) { i =>
      if (i >= 10000) None
      else Some((i - 1, 1))
    }

    (one[Eval, Int](1) append ones).take(10).runList.value ==== List.fill(10)(1)
  }

  def unfold1 = prop { xs: List[Int] =>
    var n = 0
    val p =
      Producer.unfold[Eval, Int, Int](n) { i =>
        if (n < xs.size) { val r = Some((i, xs(n) + 1)); n += 1; r }
        else             None
      }

    p.runList.value === xs.map(_ + 1)
  }

  def unfold2 = prop { xs: List[Int] =>
    var n = 0
    val p =
      Producer.unfoldM[Eval, Int, Int](n) { i =>
        Eval.later {
          if (n < xs.size) { val r = Some((i, xs(n) + 1)); n += 1; r }
          else             None
        }
      }

    p.runList.value === xs.map(_ + 1)
  }

  def unfold3 = prop { xs: List[Int] =>
    var n = 0
    val p =
      Producer.unfoldList[Eval, Int, Int](n) { i =>
        if (n < xs.size) { val r = Some((i, NonEmptyList.of(xs(n) + 1))); n += 1; r }
        else             None
      }

    p.runList.value === xs.map(_ + 1)
  }

  def unfold4 = prop { xs: List[Int] =>
    var n = 0
    val p =
      Producer.unfoldListM[Eval, Int, Int](n) { i =>
        Eval.later {
          if (n < xs.size) { val r = Some((i, NonEmptyList.of(xs(n) + 1))); n += 1; r }
          else             None
        }
      }

    p.runList.value === xs.map(_ + 1)
  }

  /**
   * HELPERS
   */

  type ProducerInt = Producer[Eval, Int]
  type ProducerString = Producer[Eval, String]

  def runLike[A](expected: Producer[Eval, A]): Matcher[Producer[Eval, A]] =
    (actual: Producer[Eval, A]) => actual.runList.value ==== expected.runList.value

  implicit def ArbitraryProducerInt: Arbitrary[ProducerInt] =
    Arbitrary(Gen.sized(genProducerInt))

  def genProducerInt(n: Int): Gen[ProducerInt] = {
    if (n <= 0)
      Gen.const(Producer.done[Eval, Int])
    else if (n == 1)
       Gen.oneOf(Gen.choose(1, 10).map(i => one(i)),
                 Gen.choose(1, 10).map(i => emit(List(i))))
    else {
      for {
        i    <- Gen.choose(1, n)
        as   <- Gen.listOfN(n, Gen.choose(1, 10))
        g    <- Gen.oneOf(emit(as), emitSeq(as))
        next <- genProducerInt(n - i)
      } yield g append next
    }
  }

  implicit def ArbitraryKleisliString: Arbitrary[Int => ProducerString] = Arbitrary {
    Gen.oneOf(
      (i: Int) => Producer.empty[Eval, String],
      (i: Int) => one(i.toString),
      (i: Int) => one(i.toString) > one((i + 1).toString),
      (i: Int) => emit(List(i.toString, (i + 1).toString)))
  }

}

