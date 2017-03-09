package org.atnos.producer

import org.specs2.Specification
import producers._
import org.atnos.origami._
import org.atnos.origami.fold._
import org.atnos.origami.folds._
import org.atnos.eff.all._
import org.atnos.eff._
import org.atnos.eff.syntax.all._
import cats.implicits._
import cats._

import scala.collection.mutable.ListBuffer

class FoldSpec extends Specification { def is = s2"""

 folding must be interleaved with producing $interleaving

"""

  def interleaving = {
    type S = Fx1[Safe]
    type ES[A] = Eff[S, A]

    val messages = new ListBuffer[String]

    val f: Fold[ES, Int, Unit] =
      fromSink[ES, Int](i => protect(messages.append(s"sink $i")))

    val result: Eff[S, Unit] =
      emit[ES, Int]((1 to 3).toList).
        chunk(1).
        map(i => { messages.append(s"evaluated $i"); i }).
        to(f)

    result.execSafe.run

    messages.toList ==== List("evaluated 1", "sink 1",
                              "evaluated 2", "sink 2",
                              "evaluated 3", "sink 3")
  }

}
