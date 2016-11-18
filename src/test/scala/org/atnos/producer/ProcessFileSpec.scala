package org.atnos.producer

import org.specs2.Specification
import io._
import org.atnos.eff._
import all._
import org.atnos.eff.syntax.all._
import org.atnos.origami._
import Producerx._

class ProcessFileSpec extends Specification { def is = s2"""

 read a big file and count the number of words $readme

"""

  def readme = {
    def program[R: _Safe] =
      readLines[R](resource("file.txt")).to(countWords)

    program[S].execSafe.run must beRight(10000)
  }

  def countWords[R]: Fold[R, String, Int] =
    fold.fromFoldLeft[R, String, Int](0)((n, l) => n + l.split(" ").size)

  type S = Fx.fx1[Safe]

  def resource(path: String) =
    getClass.getClassLoader.getResource(path).toURI.toURL.getFile
}

