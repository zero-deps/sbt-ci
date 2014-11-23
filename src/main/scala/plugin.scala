package sbt.plugins

import java.io.{FileOutputStream, File}
import java.net.JarURLConnection
import java.nio.file.Files
import java.util.jar.JarEntry

import akka.actor.ActorSystem
import akka.http.Http
import akka.io.{IO => AkkaIO}
import akka.pattern.ask
import akka.stream.scaladsl.Flow
import akka.stream.{FlowMaterializer, MaterializerSettings}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import sbt.Keys._
import sbt._
import sbt.inc.Analysis

import scala.concurrent.duration._

object CiPlugin extends AutoPlugin {

  object autoImport{
    lazy val boot = taskKey[Unit]("boot")
    lazy val cpAssets = taskKey[Unit]("make CI assets")
    lazy val ci = taskKey[Unit]("start CI server")
    lazy val pull = taskKey[Unit]("git pull")
    lazy val status = taskKey[Unit]("git status")
    lazy val re = taskKey[Analysis]("rebuild")
  }
  import sbt.plugins.CiPlugin.autoImport._

  override def requires = plugins.JvmPlugin
  override def trigger = allRequirements
  override lazy val projectSettings = Seq(
    cpAssets<<= copyAssets,
    ci<<=startCi,
    re<<=reTask,
    commands ++=Seq(printState))

  // Compile less assets, minify js before include into the target
  // mappings is scoped by the configuration and the specific package task
  // mappings in (Compile, packageBin) += {
  //   (baseDirectory.value / "in" / "example.txt") -> "out/example.txt"
  // }
  // IO.write()
  // IO.copyFile()

  // not include resources
  //unmanagedResources in Compile := Seq()

  // onLoad -> compose

  def copyAssets() = Def.task{
    val s:TaskStreams = streams.value
    s.log.info("prepare CI server assets...")
    val cl = getClass.getClassLoader
    val rootDir = target.value
    val url = cl.getResource("assets")
    val jar = url.openConnection.asInstanceOf[JarURLConnection].getJarFile
    import scala.collection.JavaConversions._
    val assets:List[JarEntry]  = jar.entries.filter(_.getName.startsWith("assets")).toList
    assets.filter(_.isDirectory).map(e=> new File(rootDir + File.separator + e.getName).mkdir)
    assets.filterNot(_.isDirectory).map(e => {
      val is = jar.getInputStream(e)
      val os = new FileOutputStream(new File(rootDir + File.separator + e.getName))
      while(is.available()>0) os.write(is.read)
      os.close
      is.close
    })
    jar.close
    s.log.info("... assets ready")
  }

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

    println(s"current p: ${p}")
    println(s"extract p: ${mappings}")
    println(s"loader p: ${e.currentLoader}")
    println(s"full classpath ${fullClasspath}")

    val bind = (AkkaIO(Http) ? Http.Bind(interface = "0.0.0.0", port = port)).mapTo[Http.ServerBinding]

    import akka.http.model.HttpMethods._
    import akka.http.model._

    val requestHandler: HttpRequest => HttpResponse = {
      case HttpRequest(GET, Uri.Path("/"), _, _, _) =>
        val result: Option[(State,Result[inc.Analysis])] = Project.runTask(re, state.value, false)
        result match {
          case None =>
          case Some((s, Inc(inc))) =>
            println(inc)
          case Some((s, Value(v))) =>
            println(v)
        }
        HttpResponse(entity = HttpEntity(MediaTypes.`text/html`, "<html><body>Hello world!</body></html>"))
      case HttpRequest(GET, Uri.Path("/ping"), _, _, _)  => HttpResponse(entity = "PONG!")
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
        printState
        v
    }
  }

  def printState = Command.command("printState") { state =>
    import state._
    println(definedCommands.size + " registered commands")
    println("commands to run: " + show(remainingCommands))
    println("original arguments: " + show(configuration.arguments))
    println("base directory: " + configuration.baseDirectory)
    println("sbt version: " + configuration.provider.id.version)
    println("Scala version (for sbt): " + configuration.provider.scalaProvider.version)

    val extracted = Project.extract(state)
    import extracted._
    println("Current build: " + currentRef.build)
    println("Current project: " + currentRef.project)
    println("Original setting count: " + session.original.size)
    println("Session setting count: " + session.append.size)

    state
  }

  def show[T](s: Seq[T]) = s.map("'" + _ + "'").mkString("[", ", ", "]")

}
