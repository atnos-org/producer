package org.atnos.producer

import java.io._

import cats.data.NonEmptyList
import org.atnos.eff.Eff
import org.atnos.eff.all._

package object io {

  def readLines[R :_Safe](path: String, encoding: String = "UTF-8", size: Int = 4096, chunkSize: Int = 100): Producer[R, String] =
    bracket(openFile[R](path, encoding, size))(readerLines(chunkSize))(closeFile)

  def readLinesFromStream[R :_Safe](path: InputStream, encoding: String = "UTF-8", size: Int = 4096, chunkSize: Int = 100): Producer[R, String] =
    bracket(openStream[R](path, encoding, size))(readerLines(chunkSize))(closeFile)

  def writeLines[R :_Safe](path: String, encoding: String = "UTF-8")(producer: Producer[R, String]): Eff[R, Unit] =
    producer.fold[Unit, BufferedWriter](protect[R, BufferedWriter](new BufferedWriter(new OutputStreamWriter(new FileOutputStream(path), encoding))),
      (writer, line) => { writer.write(line+"\n"); writer },
      writer => protect(writer.close))

  def openFile[R :_Safe](path: String, encoding: String, size: Int = 4096): Eff[R, BufferedReader] =
    protect[R, BufferedReader](new BufferedReader(new InputStreamReader(new FileInputStream(path), encoding), size))

  def openStream[R :_Safe](stream: InputStream, encoding: String, size: Int = 4096): Eff[R, BufferedReader] =
    protect[R, BufferedReader](new BufferedReader(new InputStreamReader(stream, encoding), size))

  def readerLines[R :_Safe]: BufferedReader => Producer[R, String] =
    readerLines[R](100)

  def readerLines[R :_Safe](chunkSize: Int): BufferedReader => Producer[R, String] =
    (reader: BufferedReader) =>
      Producer.unfoldList[R, BufferedReader, String](reader) { r =>
        val lines = new collection.mutable.ListBuffer[String]
        var continue = true
        var line: String = null

        while (continue) {
          if (lines.size < chunkSize) {
            line = r.readLine
            if (line != null) lines.append(line)
            else continue = false
          } else continue = false
        }

        if (lines.isEmpty) None
        else Some((r, NonEmptyList.fromListUnsafe(lines.toList)))
      }

  def closeFile[R :_Safe] = (reader: BufferedReader) =>
    protect[R, Unit](reader.close)

}
