package ci.sbt

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.nio.{ByteBuffer, MappedByteBuffer}

import akka.http.model.HttpEntity
import akka.http.model.HttpEntity.ChunkStreamPart
import akka.http.model.MediaTypes._
import akka.http.model.StatusCodes._
import akka.stream.scaladsl.Source
import akka.util.ByteString
import sbt.AutoPlugin


trait FileRoute {this:AutoPlugin =>
  val chunkSize = 2048

  def staticRoute(fnd:sbt.PathFinder):FileResponce = fnd.get match {
    case Nil => Left(NotFound)
    case Seq(file, _*) =>
      val ct = file.getName.split('.').lastOption match {
        case Some("png")  => `image/png`
        case Some("jpg")  => `image/jpeg`
        case Some("gif")  => `image/gif`
        case Some("css")  => `text/css`
        case Some("js")   => `application/javascript`
        case Some("md")   => `text/plain`
        case Some("woff") => `application/font-woff`
        case Some("eot")  => `application/vnd.ms-fontobject`
        case Some("ttf")  => `application/x-font-truetype`
        case Some("svg")  => `image/svg+xml`
        case _ => `application/octet-stream`
      }
      Right(HttpEntity.Chunked(ct, bin(file)))
  }

  class BufferedIterator(buffer: ByteBuffer, chunkSize: Int) extends Iterator[ByteString]{
    override def hasNext = buffer.hasRemaining

    override def next():ByteString = {
      val size = chunkSize min buffer.remaining()
      val tmp = buffer.slice()
      tmp.limit(size)
      buffer.position(buffer.position()+size)
      ByteString(tmp)
    }
  }

  def bin(file:File):Source[ChunkStreamPart] =
    Source(new BufferedIterator(nmap(file.toPath), chunkSize)).map(ChunkStreamPart.apply)

  private def nmap(path:java.nio.file.Path):MappedByteBuffer = {
    val channel = FileChannel.open(path, StandardOpenOption.READ)
    val result = channel.map(FileChannel.MapMode.READ_ONLY, 0L, channel.size())
    channel.close()
    result
  }

}
