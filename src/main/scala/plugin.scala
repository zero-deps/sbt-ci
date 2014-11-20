package synrc.ci

import java.io.File
import java.lang.{Runtime => JRuntime}
import java.util.concurrent.atomic.AtomicReference

import akka.actor.ActorSystem
import akka.http.Http
import akka.pattern.ask
import akka.io.{IO => AIO}
import akka.stream.{FlowMaterializer, MaterializerSettings}
import akka.stream.scaladsl.Flow
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import sbt.Keys._
import sbt._
import synrc.ci.AkkaHttp.Routes

import scala.concurrent.duration._
import scala.annotation.tailrec

object CiPlugin extends AutoPlugin {
  object GlobalState {
    private[this] val state = new AtomicReference(RevolverState.initial)

    @tailrec def update(f: RevolverState => RevolverState): RevolverState = {
      val originalState = state.get()
      val newState = f(originalState)
      if (!state.compareAndSet(originalState, newState)) update(f)
      else newState
    }
    @tailrec def updateAndGet[T](f: RevolverState => (RevolverState, T)): T = {
      val originalState = state.get()
      val (newState, value) = f(originalState)
      if (!state.compareAndSet(originalState, newState)) updateAndGet(f)
      else value
    }

    def get(): RevolverState = state.get()
  }

  case class RevolverState(processes: Map[ProjectRef, AppProcess]) {
    def addProcess(project: ProjectRef, process: AppProcess): RevolverState = copy(processes = processes + (project -> process))
    def removeProcess(project: ProjectRef): RevolverState = copy(processes = processes - project)
    def exists(project: ProjectRef): Boolean = processes.contains(project)
    def runningProjects: Seq[ProjectRef] = processes.keys.toSeq
    def getProcess(project: ProjectRef): Option[AppProcess] = processes.get(project)
  }

  object RevolverState {
    def initial = RevolverState(Map.empty)
  }

  case class AppProcess(projectRef: ProjectRef)(process: Process) {
    val shutdownHook = new Thread(new Runnable {
        def run() = { if (isRunning) process.destroy() }
      })

    @volatile var finishState: Option[Int] = None

    val watchThread = {
      val thread = new Thread(new Runnable {
        def run() {
          val code = process.exitValue()
          finishState = Some(code)
          unregisterShutdownHook()
          unregisterAppProcess(projectRef)
        }
      })
      thread.start()
      thread
    }
    def projectName: String = projectRef.project

    registerShutdownHook()

    def stop() {
      unregisterShutdownHook()
      process.destroy()
      process.exitValue()
    }

    def registerShutdownHook() { JRuntime.getRuntime.addShutdownHook(shutdownHook) }
    def unregisterShutdownHook() { JRuntime.getRuntime.removeShutdownHook(shutdownHook)}
    def isRunning: Boolean = finishState.isEmpty
  }

  object autoImport{
    lazy val container  = config("container").hide
    lazy val webapp     = config("webapp").hide

    lazy val start      = taskKey[Process]("start container")
    lazy val stop       = taskKey[Unit]("stop container")
    lazy val launchCmd  = taskKey[Seq[String]]("launch-cmd")
    lazy val options    = taskKey[ForkOptions]("options")
    lazy val reStart    = inputKey[AppProcess]("Starts the application in a forked JVM. ")
    lazy val reStop     = taskKey[Unit]("Stops the application")
    lazy val webappSrc  = taskKey[File]("src")
    lazy val webappDest = taskKey[File]("dest")
    lazy val prepareWebapp = taskKey[Seq[(sbt.File, String)]]("prepare")
    lazy val akka       = taskKey[Unit]("akka")
  }

  override def requires = plugins.JvmPlugin
  override def trigger = allRequirements

  import synrc.ci.CiPlugin.autoImport._

  lazy val startAsTask: Def.Initialize[Task[Unit]] = Def.task {
    println("AA")
    val cl = getClass.getClassLoader
    implicit val system = ActorSystem("ci", ConfigFactory.load(cl), cl)
//    implicit val system = ActorSystem("ci",
//      ConfigFactory.parseString("""
//        |akka.debug.receive = on
//        |akka.loglevel = "INFO"
//        |akka.stdout-loglevel = "WARNING"
//        |akka.logging-filter = "akka.event.DefaultLoggingFilter"
//        |akka.logger-startup-timeout = 5s
//        |akka.loggers = ["akka.event.Logging$DefaultLogger"]
//        |akka.log-config-on-start = on
//        |akka.log-dead-letters = 10
//        |akka.log-dead-letters-during-shutdown = on
//        |akka.daemonic = off
//        |akka.version = "2.3.7"
//        |akka.home = ""
//        |akka.extensions = []
//        |akka.jvm-exit-on-fatal-error = off
//        |akka.actor.debug {
//        | receive = off
//        | autoreceive = off
//        | lifecycle = off
//        | fsm = off
//        | event-stream = off
//        | unhandled = off
//        | router-misconfiguration = off
//        |}
//        |akka.scheduler {
//        | tick-duration = 10ms
//        | ticks-per-wheel = 512
//        | implementation = akka.actor.LightArrayRevolverScheduler
//        | shutdown-timeout = 5s
//        |}
//        |akka.actor.provider = "akka.actor.LocalActorRefProvider"
//        |akka.actor.guardian-supervisor-strategy = "akka.actor.DefaultSupervisorStrategy"
//        |akka.actor.creation-timeout = 20s
//        |akka.actor.reaper-interval = 5s
//        |akka.actor.serialize-messages = off
//        |akka.actor.serialize-creators = off
//        |akka.actor.unstarted-push-timeout = 10s
//        |akka.actor.typed.timeout = 5s
//        |akka.actor.deployment.default.dispatcher = ""
//        |akka.actor.deployment.default.mailbox = ""
//        |akka.actor.deployment.default.router = "from-code"
//        |akka.actor.deployment.default.nr-of-instances = 1
//        |akka.actor.deployment.default.within = 5 seconds
//        |akka.actor.deployment.default.virtual-nodes-factor = 10
//        |akka.actor.deployment.default.routees.paths = []
//        |akka.actor.deployment.default-dispatcher.type = "Dispatcher"
//        |akka.actor.deployment.default-dispatcher.executor = "default-executor"
//        |akka.actor.deployment.default-dispatcher.default-executor.fallback = "fork-join-executor"
//        |akka.actor.deployment.default-dispatcher.fork-join-executor.parallelism-min = 8
//        |akka.actor.deployment.default-dispatcher.fork-join-executor.parallelism-factor = 3.0
//        |akka.actor.deployment.default-dispatcher.fork-join-executor.parallelism-max = 64
//        |akka.actor.deployment.default-dispatcher.thread-pool-executor.keep-alive-time = 60s
//        |
//        """.stripMargin))
    implicit val askTimeout: Timeout = 500.millis
    implicit val materializer = FlowMaterializer(MaterializerSettings(system))
    import system.dispatcher
    
    val bindingFuture = (AIO(Http) ? Http.Bind(interface = "0.0.0.0", port = 8080)).mapTo[Http.ServerBinding]

    bindingFuture foreach {
      case Http.ServerBinding(localAddress, connectionStream) ⇒
        Flow(connectionStream).foreach {
          case Http.IncomingConnection(remoteAddress, requestProducer, responseConsumer) ⇒
            println("Accepted new connection from " + remoteAddress)
            Flow(requestProducer).map(Routes.requestHandler).produceTo(responseConsumer)
        }
    }
    scala.Console.readLine()
    system.shutdown()
    println("!!!")
    //streams.value.log.info("akka started?")
/*
    implicit val materializer = FlowMaterializer(MaterializerSettings(system))
    import system.dispatcher

    implicit val askTimeout: Timeout = 500.millis

    val bindingFuture = (AIO(Http) ? Http.Bind(interface = "0.0.0.0", port = 8080)).mapTo[Http.ServerBinding]

    bindingFuture foreach {
      case Http.ServerBinding(localAddress, connectionStream) ⇒
        Flow(connectionStream).foreach {
          case Http.IncomingConnection(remoteAddress, requestProducer, responseConsumer) ⇒
            //          println("Accepted new connection from " + remoteAddress)
            Flow(requestProducer).map(Routes.requestHandler).produceTo(responseConsumer)
        }//.consume(materializer)
    }

    println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
    scala.Console.readLine()
    system.shutdown()
*/
  }

  lazy val prepareWebappTask: Def.Initialize[Task[Seq[(File, String)]]] =
    ( packagedArtifact in (Compile, packageBin),
      mappings in (Compile, packageBin),
      webappSrc in webapp,
      webappDest in webapp,
      fullClasspath in Runtime) map {
      case ((art, file),mappings, webappSrc, webappDest, fullClasspath) =>
        IO.copyDirectory(webappSrc, webappDest)
        val webappLibDir = webappDest / "WEB-INF" / "lib"

        IO.copyFile(file, webappLibDir / file.getName)

        // copy this project's library dependency .jar files to WEB-INF/lib
        for {
          cpItem <- fullClasspath.toList
          file    = cpItem.data
          if !file.isDirectory
          name    = file.getName
          if name.endsWith(".jar")
        } yield IO.copyFile(file, webappLibDir / name)

        (webappDest ** "*") pair (relativeTo(webappDest) | flat)
    }

  lazy val webappSettings: Seq[Setting[_]] = Seq(
      webappSrc      <<= (sourceDirectory in Compile) map { _ / "webapp" },
      webappDest     <<= (target in Compile) map { _ / "webapp" },
      prepareWebapp  <<= prepareWebappTask,
      watchSources <++= (webappSrc in webapp) map { d => (d ** "*").get }
  )



  override lazy val projectSettings = super.projectSettings ++ Seq(
    mainClass in reStart <<= mainClass in run in Compile,
    //mainClass in reStart <<= Def.task {Some("synrc.ci.AkkaHttp")},
    fullClasspath in reStart <<= fullClasspath in Runtime,

    //fork := true,
    akka <<= startAsTask,

    reStart <<= InputTask(startArgsParser) { args =>
      (thisProjectRef, options, mainClass in reStart, fullClasspath in reStart, args)
        .map(restartApp)
        .dependsOn(products in Compile)
    },
    reStop <<= thisProjectRef map stopApp,
    // bundles the various parameters for forking
    options <<= (
      taskTemporaryDirectory,
      scalaInstance,
      baseDirectory in reStart, javaOptions in reStart, outputStrategy,javaHome) map (
      (tmp, si, base, jvmOptions, strategy, javaHomeDir) =>
      ForkOptions(
        javaHomeDir,
        strategy,
        si.jars,
        workingDirectory = Some(base),
        runJVMOptions = jvmOptions,
        connectInput = false )
      )
  ) ++ tomcat(port=8082)

  def tomcat(port: Int = 8080,args: Seq[String]   = Nil): Seq[Setting[_]] = {
    //val runnerArgs = Seq("webapp.runner.launch.Main") ++ Seq("--port", port.toString) ++ args
    val runnerArgs = Seq("synrc.ci.AkkaHttp") ++ args
    val atomicRef: AtomicReference[Option[Process]] = new AtomicReference(None)

    Seq(ivyConfigurations += container//,
      //libraryDependencies ++= Seq(("com.github.jsimone" % "webapp-runner" % "7.0.34.1" % "container").intransitive)) ++
      ) ++
      webappSettings ++
      inConfig(container) {
        Seq(start         <<= startTask(atomicRef), //dependsOn (prepareWebapp in webapp),
          stop            <<= Def.task {shutdown(atomicRef)},
          launchCmd       <<= (webappDest in webapp) map { d => runnerArgs :+ d.getPath },
          javaOptions      <<= javaOptions in Compile)
      }
  }

  def forkRun(config: ForkOptions, mainClass: String, classpath: Seq[File], options: Seq[String], extraJvmArgs: Seq[String]): Process = {
    val scalaOptions = "-classpath" :: Path.makeString(classpath) :: mainClass :: options.toList
    val newOptions = config.copy(
        outputStrategy = config.outputStrategy,
        runJVMOptions = config.runJVMOptions ++ extraJvmArgs)

    Fork.scala.fork(newOptions, scalaOptions)
  }

  private def shutdown(atomicRef: AtomicReference[Option[Process]]): Unit = {
    val oldProcess = atomicRef.getAndSet(None)
    oldProcess.foreach(p=> {
      p.destroy()
      p.exitValue()
    })
  }
  private def startup(l: Logger, libs: Seq[File], args: Seq[String], forkOptions: ForkOptions): Process = {
    l.info("starting server ...")
    val cp = libs mkString File.pathSeparator
    Fork.java.fork(forkOptions, Seq("-cp", cp) ++ args)
  }

  def startTask(atomicRef: AtomicReference[Option[Process]]): Def.Initialize[Task[Process]] =
    (  launchCmd in container,
      javaOptions in container,
      classpathTypes in container,
      update in container,
      options in container,
      streams) map {
      (  launchCmd, javaOptions, classpathTypes, updateReport,forkOptions, streams ) =>
        shutdown(atomicRef)

        val libs: Seq[File] =
          Classpaths.managedJars(container, classpathTypes, updateReport).map(_.data)

        launchCmd match {
          case Nil =>
            sys.error("no launch command specified")
          case args =>
            val p = startup(streams.log, libs, javaOptions ++ args, forkOptions)
            atomicRef.set(Option(p))
            p
        }
    }

  def restartApp(project: ProjectRef, option: ForkOptions, mainClass: Option[String],
                 cp: Classpath, startConfig: ExtraCmdLineOptions): AppProcess = {
    stopApp(project)
    startApp(project, option, mainClass, cp, startConfig)
  }

  def startApp(project: ProjectRef, options: ForkOptions, mainClass: Option[String],
               cp: Classpath, startConfig: ExtraCmdLineOptions): AppProcess = {
    assert(!revolverState.getProcess(project).exists(_.isRunning))

    // fail early
    val theMainClass = mainClass.get

    val appProcess = AppProcess(project) {
      forkRun(options, theMainClass, cp.map(_.data), startConfig.startArgs, startConfig.jvmArgs)
    }
    registerAppProcess(project, appProcess)
    appProcess
  }

  def stopApp(project: ProjectRef): Unit = {
    revolverState.getProcess(project) match {
      case Some(appProcess) => if (appProcess.isRunning) appProcess.stop()
      case None =>
    }
    unregisterAppProcess(project)
  }

  def stopApps(): Unit = revolverState.runningProjects.foreach(stopApp)

  def updateState(f: RevolverState => RevolverState): Unit = GlobalState.update(f)
  def updateStateAndGet[T](f: RevolverState => (RevolverState, T)): T = GlobalState.updateAndGet(f)
  def revolverState: RevolverState = GlobalState.get()

  def registerAppProcess(project: ProjectRef, process: AppProcess) =
    updateState { state =>
      val oldProcess = state.getProcess(project)
      if (oldProcess.exists(_.isRunning)) oldProcess.get.stop()
      state.addProcess(project, process)
    }

  def unregisterAppProcess(project: ProjectRef) = updateState(_.removeProcess(project))

  case class ExtraCmdLineOptions(jvmArgs: Seq[String], startArgs: Seq[String])

  import sbt.complete.Parser._
  import sbt.complete.Parsers._
  val spaceDelimitedWithoutDashes =
    (token(Space) ~> (token(NotSpace, "<args>") - "---")).* <~ SpaceClass.*
  /*
   * A parser which parses additional options to the start task of the form
   * <arg1> <arg2> ... <argN> --- <jvmArg1> <jvmArg2> ... <jvmArgN>
   */
  val startArgsParser: State => complete.Parser[ExtraCmdLineOptions] = { (state: State) =>
    (spaceDelimitedWithoutDashes ~ (SpaceClass.* ~ "---" ~ SpaceClass.* ~> spaceDelimited("<jvm-args>")).?) map {
      case (a, b) =>
        ExtraCmdLineOptions(b.getOrElse(Nil), a)
    }
  }

}
