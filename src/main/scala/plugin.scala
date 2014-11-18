package syrc.ci

import sbt._
import Keys._
import java.lang.{Runtime => JRuntime}
import java.io.File
import syrc.ci.CiPlugin.SbtCompatImpl

import scala.Console._
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.immutable.Queue

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

class SysoutLogger(appName: String, color: String, ansiCodesSupported: Boolean = false) extends Logger {

  def trace(t: => Throwable) {
    t.printStackTrace()
    println(t)
  }

  def success(message: => String) {
    println(Utilities.colorize(ansiCodesSupported, "%s%s[RESET] success: " format (color, appName)) + message)
  }

  def log(level: Level.Value, message: => String) {
    val levelStr = level match {
      case Level.Info => ""
      case Level.Error => "[ERROR]"
      case x@_ => "log"
    }
    println(Utilities.colorize(ansiCodesSupported, "%s%s[RESET]%s " format (color, appName, levelStr)) + message)
  }
}

object SysoutLogger extends SysoutLogger("app", "[BOLD]", false)

trait SbtCompat {
  /**
   * Changes javaOptions by using transformator function
   * (javaOptions, jrebelJarPath) => newJavaOptions
   */
  def changeJavaOptions(f: (Seq[String], String) => Seq[String]): Setting[_] =
    changeJavaOptionsWithExtra(sbt.Keys.baseDirectory /* just an ignored dummy */)((jvmArgs, path, _) => f(jvmArgs, path))

  def changeJavaOptionsWithExtra[T](extra: SettingKey[T])(f: (Seq[String], String, T) => Seq[String]): Setting[_]

  def forkRun(config: ForkOptions, mainClass: String, classpath: Seq[File], options: Seq[String], log: Logger, extraJvmArgs: Seq[String]): Process
}

object SbtCompat {
  def impl: SbtCompat = SbtCompatImpl
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
        SbtCompat.impl.forkRun(options, theMainClass, cp.map(_.data), args ++ startConfig.startArgs, logger, startConfig.jvmArgs)
      }
    registerAppProcess(project, appProcess)
    appProcess
  }

  def stopAppWithStreams(streams: TaskStreams, project: ProjectRef) = stopApp(colorLogger(streams.log), project)

  def stopApp(log: Logger, project: ProjectRef): Unit = {
    revolverState.getProcess(project) match {
      case Some(appProcess) =>
        if (appProcess.isRunning) {
          log.info("[YELLOW]Stopping application %s (by killing the forked JVM) ..." format formatApp(appProcess))

          appProcess.stop()
        }
      case None =>
        log.info("[YELLOW]Application %s not yet started" format formatAppName(project.project, "[BOLD]"))
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

  def createJRebelAgentOption(log: Logger, path: String): Option[String] = {
    if (!path.trim.isEmpty) {
      val file = new File(path)
      if (!file.exists || !file.isFile) {
        val file2 = new File(file, "jrebel.jar")
        if (!file2.exists || !file2.isFile) {
          log.warn("jrebel.jar: " + path + " not found")
          None
        } else Some("-javaagent:" + file2.getAbsolutePath)
      } else Some("-javaagent:" + path)
    } else None
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

  def registerShutdownHook() {
    JRuntime.getRuntime.addShutdownHook(shutdownHook)
  }

  def unregisterShutdownHook() {
    JRuntime.getRuntime.removeShutdownHook(shutdownHook)
  }

  def isRunning: Boolean =
    finishState.isEmpty
}

case class DebugSettings(port: Int = 5005, suspend: Boolean = false) {
  def toCmdLineArg: String =
    "-agentlib:jdwp=transport=dt_socket,server=y,suspend=%s,address=%d".format(b2str(suspend), port)

  private def b2str(b: Boolean) = if (b) "y" else "n"
}

object CiPlugin extends AutoPlugin {
  import Actions._
  import Utilities._

  object autoImport{
    val reStart = InputKey[AppProcess]("re-start", "Starts the application in a forked JVM (in the background). " +
      "If it is already running the application is first stopped and then restarted.")

    val reStop = TaskKey[Unit]("re-stop", "Stops the application if it is currently running in the background")

    val reStatus = TaskKey[Unit]("re-status", "Shows information about the application that is potentially running")

    val reStartArgs = SettingKey[Seq[String]]("re-start-args",
      "The arguments to be passed to the applications main method when being started")

    val reForkOptions = TaskKey[ForkOptions]("re-fork-options", "The options needed for the start task for forking")

    val reJRebelJar = SettingKey[String]("re-jrebel-jar", "The path to the JRebel JAR. Automatically initialized to " +
      "value of the `JREBEL_PATH` environment variable.")

    val reColors = SettingKey[Seq[String]]("re-colors", "Colors used for tagging output from different processes")

    // we need this strange setup to make sure we can define a project specific default
    // we cannot put the project specific one `in Global` as it isn't Global
    val reLogTagUnscoped = SettingKey[String]("re-log-tag", "The tag used in front of log messages for this project")
    // users should set this one, while we put the default into the unscoped variant
    val reLogTag = reLogTagUnscoped in reStart

    val debugSettings = SettingKey[Option[DebugSettings]]("debug-settings", "Settings for enabling remote JDWP debugging.")

  }

  import autoImport._

  object SbtCompatImpl extends SbtCompat{
    def changeJavaOptionsWithExtra[T](extra: SettingKey[T])(f: (Seq[String], String, T) => Seq[String]): Setting[_] =
      javaOptions in reStart <<= (javaOptions, reJRebelJar, extra) map f

    def forkRun(config: ForkOptions, mainClass: String, classpath: Seq[File], options: Seq[String], log: Logger, extraJvmArgs: Seq[String]): Process = {
      log.info(options.mkString("Starting " + mainClass + ".main(", ", ", ")"))
      val scalaOptions = "-classpath" :: Path.makeString(classpath) :: mainClass :: options.toList
      val newOptions =
        config.copy(
          outputStrategy = Some(config.outputStrategy getOrElse LoggedOutput(log)),
          runJVMOptions = config.runJVMOptions ++ extraJvmArgs)

      Fork.scala.fork(newOptions, scalaOptions)
    }
  }

  //override def requires = plugin
  override def trigger = allRequirements
  // override lazy val buildSettings - ThisBuild level
  // override lazy val globalSettings - in Global
  override lazy val projectSettings = Seq(
    commands+=helloCommand,

    mainClass in reStart <<= mainClass in run in Compile,

    fullClasspath in reStart <<= fullClasspath in Runtime,

    reColors in Global in reStart := basicColors,

    reStart <<= InputTask(startArgsParser) { args =>
      (streams, reLogTag, thisProjectRef, reForkOptions, mainClass in reStart, fullClasspath in reStart, reStartArgs, args)
        .map(restartApp)
        .dependsOn(products in Compile)
    },

    reStop <<= (streams, thisProjectRef).map(stopAppWithStreams),

    reStatus <<= (streams, thisProjectRef) map showStatus,

    // default: no arguments to the app
    reStartArgs in Global := Seq.empty,

    // initialize with env variable
    reJRebelJar in Global := Option(System.getenv("JREBEL_PATH")).getOrElse(""),

    debugSettings in Global := None,

    reLogTagUnscoped <<= thisProjectRef(_.project),

    // bake JRebel activation into java options for the forked JVM
    SbtCompat.impl.changeJavaOptionsWithExtra(debugSettings in reStart) { (jvmOptions, jrJar, debug) =>
      jvmOptions ++ createJRebelAgentOption(SysoutLogger, jrJar).toSeq ++
        debug.map(_.toCmdLineArg).toSeq
    },

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
      ),

    // stop a possibly running application if the project is reloaded and the state is reset
    onUnload in Global ~= { onUnload => state =>
      stopApps(colorLogger(state))
      onUnload(state)
    },

    onLoad in Global <<= (onLoad in Global, reColors in reStart) { (onLoad, colors) => state =>
      val colorTags = colors.map(_.toUpperCase formatted "[%s]")
      GlobalState.update(_.copy(colorPool = collection.immutable.Queue(colorTags: _*)))
      onLoad(state)
    }
  )

  lazy val helloCommand = Command.command("hello"){ (state:State) =>
    println("Hi")
    state
  }

  def enableDebugging(port: Int = 5005, suspend: Boolean = false) =
    debugSettings in reStart := Some(DebugSettings(port, suspend))

  def noColors: Seq[String] = Nil
  def basicColors = Seq("BLUE", "MAGENTA", "CYAN", "YELLOW", "GREEN")
  def basicColorsAndUnderlined = basicColors ++ basicColors.map("_"+_)

}
