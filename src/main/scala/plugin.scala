package sbt.plugins

import java.io.{File, FileOutputStream}
import java.net.JarURLConnection
import java.util.jar.JarEntry

import akka.actor.ActorSystem
import akka.http.Http
import akka.http.model.HttpEntity.ChunkStreamPart
import akka.http.model.MediaTypes.`text/html`
import akka.pattern.ask
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.{ByteString, Timeout}
import com.typesafe.config.ConfigFactory
import sbt.Keys._
import sbt._
import sbt.inc.Analysis

import scala.concurrent.duration._

object CiPlugin extends AutoPlugin {

  object autoImport{
    lazy val boot = taskKey[Unit]("boot")
    lazy val cp = taskKey[Unit]("make CI assets")
    lazy val ci = taskKey[Unit]("start CI server")
    lazy val pull = taskKey[Unit]("git pull")
    lazy val status = taskKey[Unit]("git status")
    lazy val re = taskKey[Analysis]("rebuild")
  }
  import sbt.plugins.CiPlugin.autoImport._

  override def requires = plugins.JvmPlugin
  override def trigger = allRequirements
  override lazy val projectSettings = Seq(
    cp<<= copyAssets,
    ci<<=startCi,
    re<<=reTask)

  // Compile less assets, minify js before include into the target
  // mappings is scoped by the configuration and the specific package task
  // mappings in (Compile, packageBin) += {(baseDirectory.value / "in" / "example.txt") -> "out/example.txt"}
  // not include resources unmanagedResources in Compile := Seq()

  def copyAssets() = Def.task{
    val s:TaskStreams = streams.value
    s.log.info("prepare CI server assets...")
    val cl = getClass.getClassLoader
    val rootDir = target.value
    val url = cl.getResource("assets")
    val jar = url.openConnection.asInstanceOf[JarURLConnection].getJarFile
    import scala.collection.JavaConversions._
    val assets:List[JarEntry] = jar.entries.filter(_.getName.startsWith("assets")).toList
    assets.filter(_.isDirectory).map(e=> new File(rootDir + File.separator + e.getName).mkdir)
    assets.filterNot(_.isDirectory).map(e => {
      val is = jar.getInputStream(e)
      val os = new FileOutputStream(new File(rootDir + File.separator + e.getName))
      while(is.available()>0) os.write(is.read)
      os.close
      is.close
    })
    jar.close
    s.log.info("assets ready")
  }

  def startCi(implicit port:Int = 8080) = Def.task {
    val log:Logger = streams.value.log
    val cl = getClass.getClassLoader
    val root = target.value
    implicit val system = ActorSystem("ci", ConfigFactory.load(cl), cl)
    implicit val askTimeout: Timeout = 500.millis
    import system.dispatcher
    implicit val materializer = FlowMaterializer()

    import akka.http.model.HttpMethods._
    import akka.http.model._

    akka.io.IO(Http) ? Http.Bind(interface = "0.0.0.0", port = port) foreach {
      case Http.ServerBinding(localAddress, connectionStream) =>
        Source(connectionStream) foreach {
          case Http.IncomingConnection(remote, req, resp) =>
            log.info(s"incoming commection from $remote")
            Source(req).map {
              case HttpRequest(GET, Uri.Path("/"), _, entity, _) =>
                val result: Option[(State, Result[inc.Analysis])] = Project.runTask(re, state.value, false)
                result match {
                  case None =>
                  case Some((s, Inc(inc))) => println(inc)
                  case Some((s, Value(v))) => println(v)
                }
                HttpResponse(entity = HttpEntity(`text/html`, "<html><body>Hello world!</body></html>"))

              case HttpRequest(POST, Uri.Path("/index"), _, entity, _) =>
                log.info("POST Accepted " + entity)
                val en = HttpEntity.Chunked(`text/html`, fromFile(".history"))
                HttpResponse(entity = en)
              case HttpRequest(GET, Uri.Path("/crash"), _, _, _) => sys.error("BOOM!")
              case _: HttpRequest => HttpResponse(404, entity = "Unknown resource!")
            }.to(Sink(resp)).run() }}

    scala.Console.readLine()
    system.shutdown()
  }

  private [plugins] def fromFile(name:String):Source[ChunkStreamPart] = {
    // source from file is unneficient?. nio, channels, streams, nice bytearray size
    Source(io.Source.fromFile(target.value / name).getLines()).map(l => ChunkStreamPart(ByteString(l+'\n')))
  }

  def reTask = Def.task[Analysis]{
    val st:State = state.value
    val taskKey = Keys.compile in Compile
    val result: Option[(State, Result[inc.Analysis])] = Project.runTask(taskKey, st, checkCycles = false)
    result match {
      case None => println("Key wasn't defined")
        Analysis.Empty
      case Some((s, Inc(inc))) =>
        println("error detail, inc is of type Incomplete, use Incomplete.show(inc.tpe) to get an error message")
        Analysis.Empty
      case Some((s,Value(v))) =>
        println(s"do something with v: inc.Analysis ${Analysis.summary(v)}")
        v
    }
  }
}
