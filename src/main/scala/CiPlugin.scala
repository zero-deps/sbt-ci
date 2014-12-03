package ci.sbt

import java.io.{File, FileOutputStream}
import java.net.JarURLConnection
import java.util.jar.JarEntry

import akka.actor.ActorSystem
import akka.http.Http
import akka.http.model.MediaTypes._
import akka.http.model.StatusCodes.{NoContent, NotImplemented}
import akka.pattern.ask
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import sbt.Keys._
import sbt._
import sbt.inc.Analysis
import sbt.plugins._

import scala.concurrent.duration._

object CiPlugin extends AutoPlugin  with FileRoute
                                    with LandingPage {
  var actSystem: Option[ActorSystem] = None

  object autoImport{
    lazy val start  = taskKey[Unit]("start CI server")
    lazy val stop   = taskKey[Unit]("stop CI server")
    lazy val cp     = taskKey[Unit]("make CI assets")
    lazy val pull   = taskKey[Unit]("git pull")
    lazy val status = taskKey[Unit]("git status")
    lazy val re     = taskKey[Analysis]("rebuild")
  }
  import ci.sbt.CiPlugin.autoImport._

  override def requires = JvmPlugin
  override def trigger = noTrigger
  override lazy val projectSettings = Seq(
    cp <<= copyAssets,
    start <<= startCi.dependsOn(cp),
    stop  <<= stopCi,
    onUnload in Global ~= (unloadSystem compose _),
    re<<=runTask(compile in Compile))

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
      val name = rootDir + File.separator + e.getName
      val is = jar.getInputStream(e)
      val os = new FileOutputStream(new File(name))
      s.log.info(s"\t> copying $name...")
      while(is.available()>0) os.write(is.read)
      os.close
      is.close
    })
    jar.close
    s.log.info("assets ready!")
  }

  def startCi(implicit port:Int = 8080) = Def.task {
    implicit val log:Logger = streams.value.log
    val cl = getClass.getClassLoader
    val root = target.value

    implicit val system = ActorSystem("ci", ConfigFactory.load(cl), cl)

    implicit val askTimeout: Timeout = 500.millis
    import system.dispatcher
    implicit val materializer = FlowMaterializer()

    actSystem = Some(system)

    import akka.http.model.HttpMethods._
    import akka.http.model._


    akka.io.IO(Http) ? Http.Bind(interface = "0.0.0.0", port = port) foreach {
      case Http.ServerBinding(localAddress, connectionStream) => Source(connectionStream) foreach {
        case Http.IncomingConnection(remote, req, resp) => Source(req).map {
          case HttpRequest(POST, u, _, obj, _) =>
            log.info(s"$obj")
            //runTask(compile in Compile)
            HttpResponse(NoContent)
          case HttpRequest(GET, Path(Root / "assets" / ext / file), _, _, _) =>
            staticRoute((target.value / "assets" / ext) ** file) match {
              case Left(code) => HttpResponse(code)
              case Right(e) => HttpResponse(entity=e)
            }
          case HttpRequest(GET, Path(Root / scope / task / file), _, _, _) =>
            val finder:sbt.PathFinder = (target.value / "streams" / scope / task / "$global" / "streams") ** file

            HttpResponse(entity = HttpEntity.Chunked(`text/plain`,
              bin(finder.get.headOption.getOrElse(target.value / ".history"))))

          case HttpRequest(GET,_,_,r,_) => index
          case _: HttpRequest => HttpResponse(NotImplemented)}.to(Sink(resp)).run() }}
  }

  def stopCi():Def.Initialize[Task[Unit]] = Def.task{unloadSystem(state.value)}

  val unloadSystem = (s: State) => {
    actSystem.foreach(_.shutdown)
    actSystem = None
    s
  }

  def runTask(taskKey:TaskKey[Analysis]) = Def.task[Analysis] {
    Project.runTask(taskKey, state.value, checkCycles = false) match {
      case None => Analysis.Empty
      case Some((s, Inc(inc))) =>
        println(s"inc.Incomplete ${Incomplete.show(inc.tpe)}")
        Analysis.Empty
      case Some((s,Value(v))) =>
        println(s"inc.Analysis ${Analysis.summary(v)}")
        v
    }
  }

}
