package org.atnos.producer

import org.atnos._, producer._, eff._
import org.atnos.eff.syntax.all._

object PerfApp {

  def main(args: Array[String]): Unit = {
    (1 to 5).foreach { i =>
      val result =
      producer.io.readLines("src/test/resources/lorem-ipsum.txt", chunkSize = 1000)
        .flatMapList(line => line.toLowerCase.split("""\W+""").toList)
        .filter(_.nonEmpty)
        .fold[Map[String, Int], Map[String, Int]](Eff.pure(Map.empty[String, Int]), { (acc, word) =>
        acc.updated(word, acc.getOrElse(word, 0) + 1)
      }, m => Eff.pure(m))
        .execSafe.run
      println("read "+i+". result: "+result)
    }
  }

}
