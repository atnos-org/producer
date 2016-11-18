package org.atnos.producer

import java.io._

import org.atnos.eff.Eff
import org.atnos.eff.all._

package object io {

  def readLines[R :_Safe](path: String, encoding: String = "UTF-8", size: Int = 4096, chunkSize: Int = 100): Producer[R, String] =
    bracket(openFile[R](path, encoding, size))(readerLines)(closeFile).chunk(chunkSize)

  def writeLines[R :_Safe](path: String, encoding: String = "UTF-8")(producer: Producer[R, String]): Eff[R, Unit] =
    producer.fold[Unit, BufferedWriter](protect[R, BufferedWriter](new BufferedWriter(new OutputStreamWriter(new FileOutputStream(path), encoding))),
      (writer, line) => { writer.write(line+"\n"); writer },
      writer => protect(writer.close))

  def openFile[R :_Safe](path: String, encoding: String, size: Int = 4096): Eff[R, BufferedReader] =
    protect[R, BufferedReader](new BufferedReader(new InputStreamReader(new FileInputStream(path), encoding), size))

  def readerLines[R :_Safe]: BufferedReader => Producer[R, String] =
    (reader: BufferedReader) =>
      Producer.unfold[R, BufferedReader, String](reader) { r =>
        val line = r.readLine
        if (line == null) None
        else Some((r, line))
      }

  def closeFile[R :_Safe] = (reader: BufferedReader) =>
    protect[R, Unit](reader.close)

}
