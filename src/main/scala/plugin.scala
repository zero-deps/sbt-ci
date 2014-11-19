package synrc.ci

import java.io.File
import java.lang.{Runtime => JRuntime}
import java.util.concurrent.atomic.AtomicReference

import sbt.Keys._
import sbt._

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
      //val logger = new SysoutLogger(logTag)
      streams.log.info(s"Starting application ${project.project} in the background ...")

      val appProcess= AppProcess(project/*, logger*/) {
          forkRun(options, theMainClass, cp.map(_.data), args ++ startConfig.startArgs, /*logger,*/ startConfig.jvmArgs)
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
            s"Application ${formatApp(appProcess)} is currently running"
          case None =>
            s"Application ${project.project} is currently NOT running"
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

    def formatApp(process: AppProcess): String = process.projectName
  }

  object autoImport{
    lazy val container  = config("container") hide
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
    lazy val webInfClasses = TaskKey[Boolean]("web-inf-classes")
  }

  override def requires = plugins.JvmPlugin
  override def trigger = allRequirements

  import synrc.ci.CiPlugin.Actions._
  import synrc.ci.CiPlugin.autoImport._

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
    (  packagedArtifact in (Compile, packageBin)
      , mappings in (Compile, packageBin)
      , webInfClasses in webapp
      , webappSrc in webapp
      , webappDest in webapp
      , fullClasspath in Runtime
      ) map {
      case (  (art, file)
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

  lazy val webappSettings: Seq[Setting[_]] =
    Seq(
      webappSrc      <<= (sourceDirectory in Compile) map { _ / "webapp" }
      , webappDest     <<= (target in Compile) map { _ / "webapp" }
      , prepareWebapp  <<= prepareWebappTask
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
    reForkOptions <<= (
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

  override val globalSettings:Seq[Def.Setting[_]] = Seq(
    onLoad in Global := (onLoad in Global).value andThen { state =>
      GlobalState.update(_.copy())
      state
    },
    onUnload in Global := (onUnload in Global).value andThen {state =>
      stopApps(state.log)
      state
    },
    reStartArgs in Global := Seq.empty
  )

  private def portArg(port: Int): Seq[String] =
    if (port > 0) Seq("--port", port.toString) else Nil

  def tomcat(port: Int  = -1,args: Seq[String]   = Nil): Seq[Setting[_]] = {
    val runnerArgs = Seq("webapp.runner.launch.Main") ++ portArg(port) ++ args
    val atomicRef: AtomicReference[Option[Process]] = new AtomicReference(None)

   Seq(ivyConfigurations += container,
     libraryDependencies ++= Seq(("com.github.jsimone" % "webapp-runner" % "7.0.34.1" % "container").intransitive)) ++
     warSettings ++ webappSettings ++
      inConfig(container) {
        Seq(start         <<= startTask(atomicRef) dependsOn (prepareWebapp in webapp),
          stop            <<= stopTask(atomicRef),
          launchCmd       <<= (webappDest in webapp) map { d => runnerArgs :+ d.getPath },
          options := reForkOptions.value,
          onLoad in Global <<= onLoadSetting(atomicRef),
          javaOptions      <<= javaOptions in Compile
        )
      }
  }

  def forkRun(config: ForkOptions, mainClass: String, classpath: Seq[File], options: Seq[String], /*log: Logger, */extraJvmArgs: Seq[String]): Process = {
    val scalaOptions = "-classpath" :: Path.makeString(classpath) :: mainClass :: options.toList
    val newOptions =
      config.copy(
        outputStrategy = config.outputStrategy,
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
}
