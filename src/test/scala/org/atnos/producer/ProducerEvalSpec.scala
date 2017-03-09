package org.atnos.producer

import org.specs2.Specification
import producers._
import cats._
import cats.implicits._
import org.atnos.producer._

class ProducerEvalSpec extends Specification { def is = s2"""

  Use Eval as the default MonadDefer monad $monadDeferDefault
  repeating a value is safe $repeat1

"""

  def monadDeferDefault = {
    emit(List(1, 2, 3)).runList.value ==== List(1, 2, 3)
  }

  def repeat1 = {
    repeatValue[Eval, Int](1).take(1000).runList.value ==== List.fill(1000)(1)
  }


}
