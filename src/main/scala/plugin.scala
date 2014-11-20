package sbt.plugins

import akka.actor.ActorSystem
import akka.http.Http
import akka.io.{IO => HttpIO}
import akka.pattern.ask
import akka.stream.scaladsl.Flow
import akka.stream.{FlowMaterializer, MaterializerSettings}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import sbt.Keys._
import sbt._

import scala.concurrent.duration._

object CiPlugin extends AutoPlugin {

  object autoImport{
    lazy val ci = taskKey[Unit]("start CI server")
  }
  import sbt.plugins.CiPlugin.autoImport._

  override def requires = plugins.JvmPlugin
  override def trigger = allRequirements
  override lazy val projectSettings = Seq(ci <<= startCi)

  // Compile less assets, minify js before include into the target
  // mappings is scoped by the configuration and the specific package task
  // mappings in (Compile, packageBin) += {
  //   (baseDirectory.value / "in" / "example.txt") -> "out/example.txt"
  // }
  // IO.write()
  // IO.copyFile()

  // not include resources
  //unmanagedResources in Compile := Seq()

  def startCi(implicit port:Int = 8080) = Def.task {
    val log:Logger = streams.value.log
    val cl = getClass.getClassLoader

    val tcl = Thread.currentThread().getContextClassLoader

    implicit val system = ActorSystem("ci", ConfigFactory.load(cl), cl)
    implicit val askTimeout: Timeout = 500.millis
    implicit val materializer = FlowMaterializer(MaterializerSettings(system))
    import system.dispatcher

    println(s" cl $cl tcl $tcl sys ${system.getClass.getClassLoader}")

    val p = Project.current(state.value)
    val e = Project.extract(state.value)

//    thisProject.value.autoPlugins.map{p=>
//      println(s"plugin-$p")
//      p.buildSettings.map{s=> println(s)}
//      p.projectSettings.map{s=> println(s)}
//
//    }
    println(cl.getResource("test.properties"))
    println(cl.getResource("assets/prod.css"))
    val assets:URL =cl.getResource("assets")
    println(assets)

//    val u:JarURLConnection = assets.openConnection.asInstanceOf[JarURLConnection]
//    println(u.getJarFile.entries())
    println(s"current p: ${p}")
    println(s"extract p: ${mappings}")
    println(s"loader p: ${e.currentLoader}")
    println(s"full classpath ${fullClasspath}")

    val bind = (HttpIO(Http) ? Http.Bind(interface = "0.0.0.0", port = port)).mapTo[Http.ServerBinding]

    import akka.http.model.HttpMethods._
    import akka.http.model._

    val requestHandler: HttpRequest => HttpResponse = {
      case HttpRequest(GET, Uri.Path("/"), _, _, _) =>
        HttpResponse(entity = HttpEntity(MediaTypes.`text/html`, "<html><body>Hello world!</body></html>"))
      case HttpRequest(GET, Uri.Path("/ping"), _, _, _)  =>

        HttpResponse(entity = "PONG!")
      case HttpRequest(GET, Uri.Path("/crash"), _, _, _) => sys.error("BOOM!")
      case _: HttpRequest                                => HttpResponse(404, entity = "Unknown resource!")
    }

    bind foreach {
      case Http.ServerBinding(localAddress, connectionStream) =>
        Flow(connectionStream).foreach {
          case Http.IncomingConnection(remoteAddress, requestProducer, responseConsumer) =>
            log.info(s"incoming request from $remoteAddress")
            Flow(requestProducer).map(requestHandler).produceTo(responseConsumer)}}

    scala.Console.readLine()
    system.shutdown()
  }

}
