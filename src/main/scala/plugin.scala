package synrc.ci

import java.util.concurrent.atomic.AtomicReference
import java.util.jar.Manifest

import sbt.Keys._
import sbt._
import java.lang.{Runtime => JRuntime}
import java.io.File
import scala.Console._

import scala.annotation.tailrec
import scala.collection.immutable.Queue

object CiPlugin extends AutoPlugin {

  object Utilities {
    def colorLogger(state: State): Logger = colorLogger(state.log)

    def colorLogger(logger: Logger): Logger = new Logger {
      def trace(t: => Throwable) { logger.trace(t) }
      def success(message: => String) { success(message) }
      def log(level: Level.Value, message: => String): Unit =
        logger.log(level, colorize(logger.ansiCodesSupported, message))
    }

    val simpleColors =
      Seq(
        "RED" -> RED,
        "GREEN" -> GREEN,
        "YELLOW" -> YELLOW,
        "BLUE" -> BLUE,
        "MAGENTA" -> MAGENTA,
        "CYAN" -> CYAN,
        "WHITE" -> WHITE
      )
    val rgbColors = (0 to 255) map rgb
    val ansiTagMapping: Seq[(String, String)] =
      (
        Seq(
          "BOLD" -> BOLD,
          "RESET" -> RESET
        ) ++
          simpleColors ++
          simpleColors.map(reversed) ++
          simpleColors.map(underlined) ++
          rgbColors
        ).map(delimited("[", "]"))

    def reversed(color: (String, String)): (String, String) =
      ("~"+color._1) -> (color._2+REVERSED)
    def underlined(color: (String, String)): (String, String) =
      ("_"+color._1) -> (color._2+UNDERLINED)
    def delimited(before: String, after: String)(mapping: (String, String)): (String, String) =
      (before+mapping._1+after, mapping._2)
    def rgb(idx: Int): (String, String) = ("RGB"+idx, "\033[38;5;"+idx+"m")

    def replaceAll(message: String, replacer: String => String) =
      ansiTagMapping.foldLeft(message)((msg, tag) => msg.replaceAll(java.util.regex.Pattern.quote(tag._1), replacer(tag._2)))

    def colorize(ansiCodesSupported: Boolean, message: String): String =
      replaceAll(message, if (ansiCodesSupported) identity else _ => "")
  }

  import Utilities._

  class SysoutLogger(appName: String, color: String, ansiCodesSupported: Boolean = false) extends Logger {

    def trace(t: => Throwable) {
      t.printStackTrace()
      println(t)
    }

    def success(message: => String) {
      println(Utilities.colorize(ansiCodesSupported, "%s%s[RESET] success: " format (color, appName)) + message)
    }

    def log(level: Level.Value, message: => String):Unit= println((level match {
      case Level.Info => ""
      case Level.Error => "[ERROR]"
      case x => x.toString }) + message)
  }

  /**
   * Manages global state. This is not a full-blown STM so be cautious not to lose
   * state when doing several updates depending on another.
   */
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

  case class RevolverState(processes: Map[ProjectRef, AppProcess], colorPool: Queue[String]) {
    def addProcess(project: ProjectRef, process: AppProcess): RevolverState = copy(processes = processes + (project -> process))
    private[this] def removeProcess(project: ProjectRef): RevolverState = copy(processes = processes - project)
    def removeProcessAndColor(project: ProjectRef): RevolverState =
      getProcess(project) match {
        case Some(process) => removeProcess(project).offerColor(process.consoleColor)
        case None => this
      }

    def exists(project: ProjectRef): Boolean = processes.contains(project)
    def runningProjects: Seq[ProjectRef] = processes.keys.toSeq
    def getProcess(project: ProjectRef): Option[AppProcess] = processes.get(project)

    def takeColor: (RevolverState, String) =
      if (colorPool.nonEmpty) {
        val (color, nextPool) = colorPool.dequeue
        (copy(colorPool = nextPool), color)
      } else (this, "")

    def offerColor(color: String): RevolverState =
      if (color.nonEmpty) copy(colorPool = colorPool.enqueue(color))
      else this
  }

  object RevolverState {
    def initial = RevolverState(Map.empty, Queue.empty)
  }

  /**
   * A token which we put into the SBT state to hold the Process of an application running in the background.
   */
  case class AppProcess(projectRef: ProjectRef, consoleColor: String, log: Logger)(process: Process) {
    val shutdownHook = createShutdownHook("... killing ...")

    def createShutdownHook(msg: => String) =
      new Thread(new Runnable {
        def run() {
          if (isRunning) {
            log.info(msg)
            process.destroy()
          }
        }
      })

    @volatile var finishState: Option[Int] = None

    val watchThread = {
      val thread = new Thread(new Runnable {
        def run() {
          val code = process.exitValue()
          finishState = Some(code)
          log.info("... finished with exit code %d" format code)
          unregisterShutdownHook()
          Actions.unregisterAppProcess(projectRef)
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

  object Actions {
    import Utilities._

    def restartApp(streams: TaskStreams, logTag: String, project: ProjectRef, option: ForkOptions, mainClass: Option[String],
                   cp: Classpath, args: Seq[String], startConfig: ExtraCmdLineOptions): AppProcess = {
      stopAppWithStreams(streams, project)
      startApp(streams, logTag, project, option, mainClass, cp, args, startConfig)
    }

    def startApp(streams: TaskStreams, logTag: String, project: ProjectRef, options: ForkOptions, mainClass: Option[String],
                 cp: Classpath, args: Seq[String], startConfig: ExtraCmdLineOptions): AppProcess = {
      assert(!revolverState.getProcess(project).exists(_.isRunning))

      // fail early
      val theMainClass = mainClass.get
      val color = updateStateAndGet(_.takeColor)
      val logger = new SysoutLogger(logTag, color, streams.log.ansiCodesSupported)
      colorLogger(streams.log).info("[YELLOW]Starting application %s in the background ..." format formatAppName(project.project, color))

      val appProcess=
        AppProcess(project, color, logger) {
          forkRun(options, theMainClass, cp.map(_.data), args ++ startConfig.startArgs, logger, startConfig.jvmArgs)
        }
      registerAppProcess(project, appProcess)
      appProcess
    }

    def stopAppWithStreams(streams: TaskStreams, project: ProjectRef) = stopApp(colorLogger(streams.log), project)

    def stopApp(log: Logger, project: ProjectRef): Unit = {
      revolverState.getProcess(project) match {
        case Some(appProcess) =>
          if (appProcess.isRunning) {
            log.info("Stopping application %s (by killing the forked JVM) ..." format formatApp(appProcess))

            appProcess.stop()
          }
        case None =>
          log.info("Application %s not yet started" format formatAppName(project.project, ""))
      }
      unregisterAppProcess(project)
    }

    def stopApps(log: Logger): Unit =
      revolverState.runningProjects.foreach(stopApp(log, _))

    def showStatus(streams: TaskStreams, project: ProjectRef): Unit =
      colorLogger(streams.log).info {
        revolverState.getProcess(project).find(_.isRunning) match {
          case Some(appProcess) =>
            "[GREEN]Application %s is currently running" format formatApp(appProcess, color = "[GREEN]")
          case None =>
            "[YELLOW]Application %s is currently NOT running" format formatAppName(project.project, "[BOLD]")
        }
      }

    def updateState(f: RevolverState => RevolverState): Unit = GlobalState.update(f)
    def updateStateAndGet[T](f: RevolverState => (RevolverState, T)): T = GlobalState.updateAndGet(f)
    def revolverState: RevolverState = GlobalState.get()

    def registerAppProcess(project: ProjectRef, process: AppProcess) =
      updateState { state =>
        // before we overwrite the process entry we have to make sure the old
        // project is really closed to avoid the unlikely (impossible?) race condition that we
        // have started two processes concurrently but only register the second one
        val oldProcess = state.getProcess(project)
        if (oldProcess.exists(_.isRunning)) oldProcess.get.stop()

        state.addProcess(project, process)
      }

    def unregisterAppProcess(project: ProjectRef) = updateState(_.removeProcessAndColor(project))

    case class ExtraCmdLineOptions(jvmArgs: Seq[String], startArgs: Seq[String])

    import sbt.complete.Parsers._
    import sbt.complete.Parser._
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

    def formatApp(process: AppProcess, color: String = "[YELLOW]"): String =
      formatAppName(process.projectName, process.consoleColor, color)
    def formatAppName(projectName: String, projectColor: String, color: String = "[YELLOW]"): String =
      "[RESET]%s%s[RESET]%s" format (projectColor, projectName, color)
  }

  object autoImport{
    lazy val container  = config("container").hide
    lazy val start      = taskKey[Process]("start container")
    lazy val stop       = taskKey[Unit]("stop container")
    lazy val launchCmd  = taskKey[Seq[String]]("launch-cmd")
    lazy val options    = taskKey[ForkOptions]("options")

    val reStart = inputKey[AppProcess]("Starts the application in a forked JVM. ")

    val reStop = TaskKey[Unit]("re-stop", "Stops the application if it is currently running in the background")

    val reStatus = TaskKey[Unit]("re-status", "Shows information about the application that is potentially running")

    val reStartArgs = SettingKey[Seq[String]]("re-start-args",
      "The arguments to be passed to the applications main method when being started")

    val reForkOptions = TaskKey[ForkOptions]("re-fork-options", "The options needed for the start task for forking")

    val reColors = SettingKey[Seq[String]]("re-colors", "Colors used for tagging output from different processes")

    // we need this strange setup to make sure we can define a project specific default
    // we cannot put the project specific one `in Global` as it isn't Global
    val reLogTagUnscoped = SettingKey[String]("re-log-tag", "The tag used in front of log messages for this project")
    // users should set this one, while we put the default into the unscoped variant
    val reLogTag = reLogTagUnscoped in reStart

    lazy val packageWar = TaskKey[File]("package")

    lazy val webapp        = config("webapp").hide
    lazy val webappSrc     = TaskKey[File]("src")
    lazy val webappDest    = TaskKey[File]("dest")
    lazy val prepareWebapp = TaskKey[Seq[(sbt.File, String)]]("prepare")
    lazy val postProcess   = TaskKey[java.io.File => Unit]("post-process")
    lazy val webInfClasses = TaskKey[Boolean]("web-inf-classes")
  }

  override def requires = plugins.JvmPlugin
  override def trigger = allRequirements

  import Actions._
  import autoImport._

  lazy val warSettings: Seq[Setting[_]] =
    Defaults.packageTaskSettings(packageWar, prepareWebappTask) ++
      Seq(
        artifact in packageWar <<= moduleName(n => Artifact(n, "war", "war"))
        , Keys.`package` in Compile <<= packageWar
        , packageOptions in packageWar <<= packageOptions in (Compile, packageBin)
      ) ++
      webappSettings ++
      addArtifact(artifact in (Compile, packageWar), packageWar in Compile)

  lazy val prepareWebappTask: Def.Initialize[Task[Seq[(File, String)]]] =
    (  postProcess in webapp
      , packagedArtifact in (Compile, packageBin)
      , mappings in (Compile, packageBin)
      , webInfClasses in webapp
      , webappSrc in webapp
      , webappDest in webapp
      , fullClasspath in Runtime
      ) map {
      case (  postProcess
      , (art, file)
      , mappings
      , webInfClasses
      , webappSrc
      , webappDest
      , fullClasspath
        ) =>

        IO.copyDirectory(webappSrc, webappDest)

        val webInfDir = webappDest / "WEB-INF"
        val webappLibDir = webInfDir / "lib"

        // copy this project's classes, either directly to WEB-INF/classes
        // or as a .jar file in WEB-INF/lib
        if (webInfClasses) {
          mappings foreach {
            case (src, name) =>
              if (!src.isDirectory) {
                val dest =  webInfDir / "classes" / name
                IO.copyFile(src, dest)
              }
          }
        } else {
          IO.copyFile(file, webappLibDir / file.getName)
        }

        // create .jar files for depended-on projects in WEB-INF/lib
        for {
          cpItem    <- fullClasspath.toList
          dir        = cpItem.data
          if dir.isDirectory
          artEntry  <- cpItem.metadata.entries find { e => e.key.label == "artifact" }
          cpArt      = artEntry.value.asInstanceOf[Artifact]
          if cpArt != art//(cpItem.metadata.entries exists { _.value == art })
          files      = (dir ** "*").getPaths flatMap { p =>
            val file = new File(p)
            if (!file.isDirectory)
              IO.relativize(dir, file) map { p => (file, p) }
            else
              None
          }
          jarFile    = cpArt.name + ".jar"
          _          = IO.jar(files, webappLibDir / jarFile, new Manifest)
        } yield ()

        // copy this project's library dependency .jar files to WEB-INF/lib
        for {
          cpItem <- fullClasspath.toList
          file    = cpItem.data
          if !file.isDirectory
          name    = file.getName
          if name.endsWith(".jar")
        } yield IO.copyFile(file, webappLibDir / name)

        postProcess(webappDest)

        (webappDest ** "*") pair (relativeTo(webappDest) | flat)
    }

  lazy val webappSettings: Seq[Setting[_]] =
    Seq(
      webappSrc      <<= (sourceDirectory in Compile) map { _ / "webapp" }
      , webappDest     <<= (target in Compile) map { _ / "webapp" }
      , prepareWebapp  <<= prepareWebappTask
      , postProcess     := { _ => () }
      , webInfClasses   := false
      , watchSources <++= (webappSrc in webapp) map { d => (d ** "*").get }
    )


  override lazy val projectSettings = super.projectSettings ++ Seq(
    mainClass in reStart <<= mainClass in run in Compile,
    fullClasspath in reStart <<= fullClasspath in Runtime,

    reStart <<= InputTask(startArgsParser) { args =>
      (streams, reLogTag, thisProjectRef, reForkOptions, mainClass in reStart, fullClasspath in reStart, reStartArgs, args)
        .map(restartApp)
        .dependsOn(products in Compile)
    },

    reStop <<= (streams, thisProjectRef).map(stopAppWithStreams),
    reStatus <<= (streams, thisProjectRef) map showStatus,
    reLogTagUnscoped <<= thisProjectRef(_.project),

    // bundles the various parameters for forking
    reForkOptions <<= (taskTemporaryDirectory, scalaInstance, baseDirectory in reStart, javaOptions in reStart, outputStrategy,
      javaHome) map ( (tmp, si, base, jvmOptions, strategy, javaHomeDir) =>
      ForkOptions(
        javaHomeDir,
        strategy,
        si.jars,
        workingDirectory = Some(base),
        runJVMOptions = jvmOptions,
        connectInput = false
      )
      )
  ) ++ tomcat(port=8082)

  override val globalSettings:Seq[Def.Setting[_]] = Seq(
    onLoad in Global := (onLoad in Global).value andThen { state =>
      val colorTags = (reColors in reStart).value.map(_.toUpperCase formatted "[%s]")
      GlobalState.update(_.copy(colorPool = collection.immutable.Queue(colorTags: _*)))
      state
    },
    onUnload in Global := (onUnload in Global).value andThen {state =>
      stopApps(colorLogger(state))
      state
    },
    reColors in Global in reStart := basicColors,
    reStartArgs in Global := Seq.empty
  )

  def noColors: Seq[String] = Nil
  def basicColors = Seq("BLUE", "MAGENTA", "CYAN", "YELLOW", "GREEN")
  def basicColorsAndUnderlined = basicColors ++ basicColors.map("_"+_)


  private def portArg(port: Int): Seq[String] =
    if (port > 0) Seq("--port", port.toString) else Nil

  private val tomcatRunner: ModuleID =
    ("com.github.jsimone" % "webapp-runner" % "7.0.34.1" % "container").intransitive

  def tomcat(libs: Seq[ModuleID] = Seq(tomcatRunner),
             main: String        = "webapp.runner.launch.Main",
             port: Int           = -1,
             args: Seq[String]   = Nil,
             options: ForkOptions = new ForkOptions): Seq[Setting[_]] = {
    val runnerArgs = Seq(main) ++ portArg(port) ++ args
    runnerContainer(libs, runnerArgs, options) ++ warSettings ++ webappSettings
  }

  def forkRun(config: ForkOptions, mainClass: String, classpath: Seq[File], options: Seq[String], log: Logger, extraJvmArgs: Seq[String]): Process = {
    log.info(options.mkString("Starting " + mainClass + ".main(", ", ", ")"))
    val scalaOptions = "-classpath" :: Path.makeString(classpath) :: mainClass :: options.toList
    val newOptions =
      config.copy(
        outputStrategy = Some(config.outputStrategy getOrElse LoggedOutput(log)),
        runJVMOptions = config.runJVMOptions ++ extraJvmArgs)

    Fork.scala.fork(newOptions, scalaOptions)
  }



  private def shutdown(l: Logger, atomicRef: AtomicReference[Option[Process]]): Unit = {
    val oldProcess = atomicRef.getAndSet(None)
    oldProcess.foreach(stopProcess(l))
  }

  private def stopProcess(l: Logger)(p: Process): Unit = {
    l.info("waiting for server to shut down...")
    p.destroy
    p.exitValue
  }

  private def startup(
                       l: Logger, libs: Seq[File], args: Seq[String], forkOptions: ForkOptions
                       ): Process = {
    l.info("starting server ...")
    val cp = libs mkString File.pathSeparator
    Fork.java.fork(forkOptions, Seq("-cp", cp) ++ args)
  }

  def startTask(atomicRef: AtomicReference[Option[Process]]): Def.Initialize[Task[Process]] =
    (  launchCmd in container
      , javaOptions in container
      , classpathTypes in container
      , update in container
      , options in container
      , streams
      ) map {
      (  launchCmd
         , javaOptions
         , classpathTypes
         , updateReport
         , forkOptions
         , streams
        ) =>
        shutdown(streams.log, atomicRef)

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

  def stopTask(atomicRef: AtomicReference[Option[Process]]): Def.Initialize[Task[Unit]] = Def.task {
    shutdown(streams.value.log, atomicRef)
  }

  def onLoadSetting(atomicRef: AtomicReference[Option[Process]]): Def.Initialize[State => State] = Def.setting {
    (onLoad in Global).value compose { state: State =>
      state.addExitHook(shutdown(state.log, atomicRef))
    }
  }

  def containerSettings(
                         launchCmdTask: Def.Initialize[Task[Seq[String]]],
                         forkOptions: ForkOptions
                         ): Seq[Setting[_]] = {
    val atomicRef: AtomicReference[Option[Process]] = new AtomicReference(None)

    inConfig(container) {
      Seq(start            <<= startTask(atomicRef) dependsOn (prepareWebapp in webapp)
        , stop             <<= stopTask(atomicRef)
        , launchCmd        <<= launchCmdTask
        , options           := forkOptions
        , onLoad in Global <<= onLoadSetting(atomicRef)
        , javaOptions      <<= javaOptions in Compile
      )
    } ++ Seq(ivyConfigurations += container)
  }

  def runnerContainer(
                       libs: Seq[ModuleID], args: Seq[String], forkOptions: ForkOptions = new ForkOptions
                       ): Seq[Setting[_]] =
    Seq(libraryDependencies ++= libs) ++
      containerSettings((webappDest in webapp) map { d => args :+ d.getPath }, forkOptions)

}
