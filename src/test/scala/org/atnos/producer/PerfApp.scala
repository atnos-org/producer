package org.atnos.producer

import org.atnos._
import producer._
import eff._
import org.atnos.eff.syntax.all._
import Producer._
import org.specs2.time.SimpleTimer

object PerfApp {

  def main(args: Array[String]): Unit = {
    val timer = (new SimpleTimer).start
    (1 to 5).foreach { i =>
      val result =
      producer.io.readLines("src/test/resources/lorem-ipsum.txt", chunkSize = 100)
        .flatMapList(line => line.toLowerCase.split("""\W+""").toList)
        .filter(_.nonEmpty)
        .foldLeft(Map[String, Int]()) { (acc, word) => acc.updated(word, acc.getOrElse(word, 0) + 1) }
        .execSafe.run
      
      println("read "+i+". result: "+result)
    }

    println(timer.stop.totalMillis)
  }

}
