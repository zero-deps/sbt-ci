package syrc.ci

import java.io.File
import java.lang.{Runtime => JRuntime}
import java.util.concurrent.atomic.AtomicReference

import sbt.Keys._
import sbt._

import scala.Console._
import scala.annotation.tailrec
import scala.collection.immutable.Queue

class SysoutLogger(appName: String) extends Logger {
  def trace(t: => Throwable) {
    t.printStackTrace()
    println(t)
  }
  def success(message: => String) = println(s"$appName success: $message")
  def log(level: Level.Value, message: => String) = println(s"[${level.toString}] $appName $message")
}

case class RevolverState(processes: Map[ProjectRef, AppProcess], colorPool: Queue[String]) {
  def addProcess(project: ProjectRef, process: AppProcess): RevolverState = copy(processes = processes + (project -> process))
  private[this] def removeProcess(project: ProjectRef): RevolverState = copy(processes = processes - project)
  
  def removeProcessAndColor(project: ProjectRef): RevolverState =
    getProcess(project) match {
      case Some(process) => removeProcess(project)
      case None => this
    }

  def exists(project: ProjectRef): Boolean = processes.contains(project)
  def runningProjects: Seq[ProjectRef] = processes.keys.toSeq
  def getProcess(project: ProjectRef): Option[AppProcess] = processes.get(project)
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
    if (!state.compareAndSet(originalState, newState)) update(f) else newState
  }
  @tailrec def updateAndGet[T](f: RevolverState => (RevolverState, T)): T = {
    val originalState = state.get()
    val (newState, value) = f(originalState)
    if (!state.compareAndSet(originalState, newState)) updateAndGet(f) else value
  }

  def get(): RevolverState = state.get()
}
object Actions {
  def forkRun(config: ForkOptions, mainClass: String, classpath: Seq[File], options: Seq[String], log: Logger,
              extraJvmArgs: Seq[String]): Process = {
    log.info(options.mkString("Starting " + mainClass + ".main(", ", ", ")"))
    val scalaOptions = "-classpath" :: Path.makeString(classpath) :: mainClass :: options.toList
    val newOptions =
      config.copy(
        outputStrategy = Some(config.outputStrategy getOrElse LoggedOutput(log)),
        runJVMOptions = config.runJVMOptions ++ extraJvmArgs)

    Fork.scala.fork(newOptions, scalaOptions)
  }

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
    val logger = new SysoutLogger(logTag)
    streams.log.info("Starting application %s in the background ..." format project.project)

    val appProcess=
      AppProcess(project, logger) {
        forkRun(options, theMainClass, cp.map(_.data), args ++ startConfig.startArgs, logger, startConfig.jvmArgs)
      }
    registerAppProcess(project, appProcess)
    appProcess
  }

  def stopAppWithStreams(streams: TaskStreams, project: ProjectRef) = stopApp(streams.log, project)

  def stopApp(log: Logger, project: ProjectRef): Unit = {
    revolverState.getProcess(project) match {
      case Some(appProcess) =>
        if (appProcess.isRunning) {
          log.info("Stopping application %s (by killing the forked JVM) ..." format appProcess.projectName)

          appProcess.stop()
        }
      case None =>
        log.info("Application %s not yet started" format project.project)
    }
    unregisterAppProcess(project)
  }

  def stopApps(log: Logger): Unit =
    revolverState.runningProjects.foreach(stopApp(log, _))

  def showStatus(streams: TaskStreams, project: ProjectRef): Unit =
    streams.log.info {
      revolverState.getProcess(project).find(_.isRunning) match {
        case Some(appProcess) =>
          "Application %s is currently running" format appProcess.projectName
        case None =>
          "Application %s is currently NOT running" format project.project
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

  import sbt.complete.Parser._
  import sbt.complete.Parsers._

  val spaceDelimitedWithoutDashes = (token(Space) ~> (token(NotSpace, "<args>") - "---")).* <~ SpaceClass.*
  /*
   * A parser which parses additional options to the start task of the form
   * <arg1> <arg2> ... <argN> --- <jvmArg1> <jvmArg2> ... <jvmArgN>
   */
  val startArgsParser: State => complete.Parser[ExtraCmdLineOptions] = { (state: State) =>
    (spaceDelimitedWithoutDashes ~ (SpaceClass.* ~ "---" ~ SpaceClass.* ~> spaceDelimited("<jvm-args>")).?) map {
      case (a, b) => ExtraCmdLineOptions(b.getOrElse(Nil), a)
    }
  }
}

case class AppProcess(projectRef: ProjectRef, log: Logger)(process: Process) {
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

object CiPlugin extends AutoPlugin {
  import syrc.ci.Actions._

  object autoImport {
    val reStart = InputKey[AppProcess]("re-start", "Starts the application in a forked JVM (in the background). " +
      "If it is already running the application is first stopped and then restarted.")
    val reStop = TaskKey[Unit]("re-stop", "Stops the application if it is currently running in the background")
    val reStatus = TaskKey[Unit]("re-status", "Shows information about the application that is potentially running")
    val reStartArgs = SettingKey[Seq[String]]("re-start-args", "The arguments to be passed to the applications main method when being started")
    val reForkOptions = TaskKey[ForkOptions]("re-fork-options", "The options needed for the start task for forking")
    val reLogTagUnscoped = SettingKey[String]("re-log-tag", "The tag used in front of log messages for this project")
    val reLogTag = reLogTagUnscoped in reStart
  }

  import autoImport._

  //override def requires = plugin
  override def trigger = allRequirements
  // override lazy val buildSettings - ThisBuild level
  // override lazy val globalSettings - in Global
  override lazy val projectSettings = Seq(
    commands+=helloCommand,

    mainClass in reStart <<= mainClass in run in Compile,

    fullClasspath in reStart <<= fullClasspath in Runtime,

    reStart <<= InputTask(startArgsParser) { args =>
      (streams, reLogTag, thisProjectRef, reForkOptions, mainClass in reStart, fullClasspath in reStart, reStartArgs, args)
        .map(restartApp)
        .dependsOn(products in Compile)
    },

    reStop <<= (streams, thisProjectRef).map(stopAppWithStreams),

    reStatus <<= (streams, thisProjectRef) map showStatus,

    // default: no arguments to the app
    reStartArgs in Global := Seq.empty,

    reLogTagUnscoped <<= thisProjectRef(_.project),

    // bundles the various parameters for forking
    reForkOptions <<= (taskTemporaryDirectory, scalaInstance, baseDirectory in reStart,
      javaOptions in reStart, outputStrategy, javaHome) map ( (tmp, si, base, jvmOptions, strategy, javaHomeDir) =>
      ForkOptions(
        javaHomeDir,
        strategy,
        si.jars,
        workingDirectory = Some(base),
        runJVMOptions = jvmOptions,
        connectInput = false
      )),

    // stop a possibly running application if the project is reloaded and the state is reset
    onUnload in Global ~= { onUnload => state => stopApps(state.log)
      onUnload(state)
    }
  )

  lazy val helloCommand = Command.command("hello"){ (state:State) =>
    println("Hi")
    state
  }
}
