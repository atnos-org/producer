package org.atnos.producer

import java.io._

import org.specs2.Specification
import io._
import org.atnos.eff._
import all._
import cats.Eval
import org.atnos.eff.syntax.all._
import producers._
import org.atnos.origami._

class Fs2ReadmeSpec extends Specification { def is = s2"""

  This specification shows how to implement the example in the FS2 readme
```
def fahrenheitToCelsius(f: Double): Double =
  (f - 32.0) * (5.0/9.0)

val converter: Task[Unit] =
  io.file.readAll[Task](Paths.get("testdata/fahrenheit.txt"), 4096)
     .through(text.utf8Decode)
    .through(text.lines)
    .filter(s => !s.trim.isEmpty && !s.startsWith("//"))
    .map(line => fahrenheitToCelsius(line.toDouble).toString)
    .intersperse("\n")
    .through(text.utf8Encode)
    .through(io.file.writeAll(Paths.get("testdata/celsius.txt")))
    .run
```

    example $readme

"""

  def readme = {
    def program[R: _Safe] =
      readLines[R](resource("fahrenheit.txt"))
        .filter(s => !s.trim.isEmpty && !s.startsWith("//"))
        .map(_.split(" ")(0))
        .map(line => fahrenheitToCelsius(line.toDouble).toString)
        .intersperse("\n")
        .observe(writeLines("target/celsius.txt"))

    type S = Fx.fx1[Safe]
    program[S].runLast.runSafe
    ok
  }

  def fahrenheitToCelsius(f: Double): Double =
    (f - 32.0) * (5.0 / 9.0)

  def resource(path: String) =
    getClass.getClassLoader.getResource(path).toURI.toURL.getFile

  def writeLines[R :_Safe](path: String, encoding: String = "UTF-8"): FoldFx[R, String, Unit] = new FoldFx[R, String, Unit] {
    type S = BufferedWriter

    implicit val monad = EffMonad[R]

    def start: Eff[R, S] =
      protect[R, BufferedWriter](new BufferedWriter(new OutputStreamWriter(new FileOutputStream(path), encoding)))

    def fold = (s: S, line: String) => protect { s.write(line); s }

    def end(s: S): Eff[R, Unit] =
      protect[R, Unit](s.close)
  }
}

