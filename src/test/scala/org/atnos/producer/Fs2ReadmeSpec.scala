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

import scala.collection.mutable.ListBuffer

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
    def program[R: _Safe :_eval] =
      readLines[R](resource("fahrenheit.txt"))
        .filter(s => !s.trim.isEmpty && !s.startsWith("//"))
        .map { line => {println("splitting "+ line); line.split(" ")(0)} }
        .map(line => fahrenheitToCelsius(line.toDouble).toString)
        .intersperse("\n")
        .observe(writeLines("target/celsius.txt"))

    type S = Fx.fx2[Safe, Eval]
    program[S].runLast.runSafe.runEval.run._1.pp //eftMap(_.printStackTrace)
    ok
  }

  def fahrenheitToCelsius(f: Double): Double =
    (f - 32.0) * (5.0 / 9.0)

  def resource(path: String) =
    getClass.getClassLoader.getResource(path).toURI.toURL.getFile
}

object io {

  def openFile[R :_safe](path: String, encoding: String, size: Int = 4096): Eff[R, BufferedReader] =
    protect[R, BufferedReader] {
      println("open reader")
      new BufferedReader(new InputStreamReader(new FileInputStream(path), encoding), size)
    }

  def readerLines[R :_safe :_eval]: BufferedReader => Producer[R, String] =
    (reader: BufferedReader) => {
      val list = new ListBuffer[String]
      var line = reader.readLine()

      while (line != null) {
        list.append(line)
        line = reader.readLine()
      }
      list.toList.foldLeft(done[R, String])((res, cur) => res append one(cur))
//
//      def go: Producer[R, String] = {
//        producers.eval(protect[R, Producer[R, String]] {
//          println("reading one line")
//          val line = reader.readLine()
//          println("line is "+line)
//          if (line == null) done[R, String]
//          else one(line) append go
//        }).flatten
//      }
//      go
    }

  def closeFile[R :_safe :_eval] = (reader: BufferedReader) =>
    protect[R, Unit] {
      println("closing reader")
      //reader.close
    }

  def readLines[R :_Safe :_eval](path: String, encoding: String = "UTF-8", size: Int = 4096): Producer[R, String] =
    bracket(openFile[R](path, encoding, size))(readerLines)(closeFile)

  def writeLines[R :_safe :_eval](path: String, encoding: String = "UTF-8"): Fold[R, String, Unit] = new Fold[R, String, Unit] {
    type S = BufferedWriter

    def start: Eff[R, S] =
      protect[R, BufferedWriter](new BufferedWriter(new OutputStreamWriter(new FileOutputStream(path), encoding)))

    def fold = (s: S, line: String) => { println("writing line" + line); s.write(line); s }

    def end(s: S): Eff[R, Unit] =
      protect[R, Unit](s.close)
  }

  implicit class ProducerFolds[R :_eval, A](p: Producer[R, A]) {
    def to[B](f: Fold[R, A, B]): Eff[R, B] =
      p.fold[B, f.S](f.start, f.fold, f.end)

    def observe(f: Fold[R, A, Unit]): Producer[R, A] =
      producers.observe(p)(f.start, f.fold, f.end)
  }
}
