package ci.sbt

import java.io.{File, FileOutputStream}
import java.net.JarURLConnection
import java.util.jar.JarEntry
import java.util.regex.{Pattern, PatternSyntaxException}

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
    lazy val tst    = taskKey[Unit]("cites")
  }
  import ci.sbt.CiPlugin.autoImport._

  override def requires = JvmPlugin
  override def trigger = noTrigger
  override lazy val projectSettings = Seq(
    tst <<= test,
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

  /*
  def aboutProject(s: State): String =
      val e = Project.extract(s)
      val version = e.getOpt(Keys.version) match { case None => ""; case Some(v) => " " + v }
      val current = "The current project is " + Reference.display(e.currentRef) + version + "\n"

  def aboutPlugins(e: Extracted): String =
    {
      def list(b: BuildUnit) = b.plugins.detected.autoPlugins.map(_.value.label) ++ b.plugins.detected.plugins.names
      val allPluginNames = e.structure.units.values.flatMap(u => list(u.unit)).toSeq.distinct
      if (allPluginNames.isEmpty) "" else allPluginNames.mkString("Available Plugins: ", ", ", "")
    }
  *def showSettingLike(command: String, preamble: String, cutoff: Int, keep: AttributeKey[_] => Boolean) =
    Command(command, settingsBrief(command), settingsDetailed(command))(showSettingParser(keep)) {
      case (s: State, (verbosity: Int, selected: Option[String])) =>
        if (selected.isEmpty) System.out.println(preamble)
        val prominentOnly = verbosity <= 1
        val verboseFilter = if (prominentOnly) highPass(cutoff) else topNRanked(25 * verbosity)
        System.out.println(tasksHelp(s, keys => verboseFilter(keys filter keep), selected))
        System.out.println()
        if (prominentOnly) System.out.println(moreAvailableMessage(command, selected.isDefined))
        s
    }
  * */

  def test = Def.task{
    val s = state.value

    val appCon = s.configuration

    val ex = Project.extract(s)
    val cr = ex.currentRef

    val extracted = Project.extract(s)
    import extracted._
    val index = structure.index
    val keys = index.keyIndex.keys(Some(currentRef)).toSeq.map(index.keyMap).distinct

    val commandsAndTasks = keys.filter(_.description.isDefined).sortBy(_.label) flatMap{key=>
      key.description.map{d=> (key.label, d)}
    }

    //CommandUtil
    val HelpPatternFlags = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE

    def fill(s: String, size: Int) = s + " " * math.max(size - s.length, 0)
    def aligned(pre: String, sep: String, in: Seq[(String, String)]): Seq[String] = if (in.isEmpty) Nil else {
      val width = in.map(_._1.length).max
      in.map { case (a, b) => (pre + fill(a, width) + sep + b) }
    }
    def layoutDetails(details: Map[String, String]): String =
      details.map { case (k, v) => k + "\n\n  " + v } mkString ("\n", "\n\n", "\n")

    def searchHelp(selected: String, detailMap: Map[String, String]): Map[String, String] =
    {
      val pattern = Pattern.compile(selected, HelpPatternFlags)
      detailMap flatMap {
        case (k, v) =>
          val contentMatches = Highlight.showMatches(pattern)(v)
          val keyMatches = Highlight.showMatches(pattern)(k)
          val keyString = Highlight.bold(keyMatches getOrElse k)
          val contentString = contentMatches getOrElse v
          if (keyMatches.isDefined || contentMatches.isDefined)
            (keyString, contentString) :: Nil
          else
            Nil
      }
    }

    def detail(selected: String, detailMap: Map[String, String]): String =
      detailMap.get(selected) match {
        case Some(exactDetail) => exactDetail
        case None => try {
          val details = searchHelp(selected, detailMap)
          if (details.isEmpty)
            "No matches for regular expression '" + selected + "'."
          else
            layoutDetails(details)
        } catch {
          case pse: PatternSyntaxException => sys.error("Invalid regular expression (java.util.regex syntax).\n" + pse.getMessage)
        }
      }

    //BuiltinCommands
    def sortByLabel(keys: Seq[AttributeKey[_]]): Seq[AttributeKey[_]] = keys.sortBy(_.label)
    def withDescription(keys: Seq[AttributeKey[_]]): Seq[AttributeKey[_]] = keys.filter(_.description.isDefined)
    def taskStrings(key: AttributeKey[_]): Option[(String, String)] = key.description map { d => (key.label, d) }
    def taskDetail(keys: Seq[AttributeKey[_]]): Seq[(String, String)] =
      sortByLabel(withDescription(keys)) flatMap taskStrings
    def sortByRank(keys: Seq[AttributeKey[_]]): Seq[AttributeKey[_]] = keys.sortBy(_.rank)
    def topNRanked(n: Int) = (keys: Seq[AttributeKey[_]]) => sortByRank(keys).take(n)
    def highPass(rankCutoff: Int) = (keys: Seq[AttributeKey[_]]) => sortByRank(keys).takeWhile(_.rank <= rankCutoff)

    def tasksHelp(s: State, filter: Seq[AttributeKey[_]] => Seq[AttributeKey[_]], arg: Option[String]): String =
    {
      val commandAndDescription = taskDetail(keys)
      arg match {
        case Some(selected) => detail(selected, commandAndDescription.toMap)
        case None           => aligned("  ", "   ", commandAndDescription) mkString ("\n", "\n", "")
      }
    }

    val verboseFilter = highPass(1)
    System.out.println(tasksHelp(s, highPass(1), None))
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
