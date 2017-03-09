package org.atnos.producer

import java.io._

import cats.data.NonEmptyList
import org.atnos.eff.Eff
import org.atnos.eff.all._

package object io {

  def readBytes[R :_Safe](path: String, size: Int = 4096, chunkSize: Int = 100): Producer[Eff[R, ?], Array[Byte]] =
    bracket(openDataInputStream[R](path, size))(readerBytes(size, chunkSize))(closeDataInputStream)

  def readLines[R :_Safe](path: String, encoding: String = "UTF-8", size: Int = 4096, chunkSize: Int = 100): Producer[Eff[R, ?], String] =
    bracket(openBufferedReader[R](path, encoding, size))(readerLines(chunkSize))(closeBufferedReader)

  def readLinesFromStream[R :_Safe](path: InputStream, encoding: String = "UTF-8", size: Int = 4096, chunkSize: Int = 100): Producer[Eff[R, ?], String] =
    bracket(openBufferedReaderFromInputStream[R](path, encoding, size))(readerLines(chunkSize))(closeBufferedReader)

  def writeLines[R :_Safe](path: String, encoding: String = "UTF-8")(producer: Producer[Eff[R, ?], String]): Eff[R, Unit] =
    producer.fold[Unit, BufferedWriter](protect[R, BufferedWriter](new BufferedWriter(new OutputStreamWriter(new FileOutputStream(path), encoding))),
      (writer: BufferedWriter, line: String) => protect { writer.write(line+"\n"); writer },
      (writer: BufferedWriter) => protect(writer.close))

  def openBufferedReader[R :_Safe](path: String, encoding: String, size: Int = 4096): Eff[R, BufferedReader] =
    protect[R, BufferedReader](new BufferedReader(new InputStreamReader(new FileInputStream(path), encoding), size))

  def openBufferedReaderFromInputStream[R :_Safe](stream: InputStream, encoding: String, size: Int = 4096): Eff[R, BufferedReader] =
    protect[R, BufferedReader](new BufferedReader(new InputStreamReader(stream, encoding), size))

  def openDataInputStream[R :_Safe](path: String, size: Int = 4096): Eff[R, DataInputStream] =
    protect[R, DataInputStream](new DataInputStream(new FileInputStream(path)))

  def readerLines[R :_Safe]: BufferedReader => Producer[Eff[R, ?], String] =
    readerLines[R](100)

  def readerLines[R :_Safe](chunkSize: Int): BufferedReader => Producer[Eff[R, ?], String] =
    (reader: BufferedReader) =>
      Producer.unfoldList[Eff[R, ?], BufferedReader, String](reader) { r =>
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

  def readerBytes[R :_Safe](size: Int, chunkSize: Int): DataInputStream => Producer[Eff[R, ?], Array[Byte]] =
    (stream: DataInputStream) => {
      val array = Array.ofDim[Byte](size)
      Producer.unfoldList[Eff[R, ?], DataInputStream, Array[Byte]](stream) { s =>
        val arrays = new collection.mutable.ListBuffer[Array[Byte]]
        var continue = true

        while (continue) {
          if (arrays.size < chunkSize) {
            val result = s.read(array)
            if (result > 0) arrays.append(array.clone)
            else continue = false
          } else continue = false
        }

        if (arrays.isEmpty) None
        else Some((s, NonEmptyList.fromListUnsafe(arrays.toList)))
      }
    }

  def closeBufferedReader[R :_Safe] = (reader: BufferedReader) =>
    protect[R, Unit](reader.close)

  def closeDataInputStream[R :_Safe] = (stream: DataInputStream) =>
    protect[R, Unit](stream.close)

}
