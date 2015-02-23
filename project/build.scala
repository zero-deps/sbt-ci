package ci.sbt

import sbt.Keys._
import sbt._

object CiBuild extends Build{
  import sbt.ScriptedPlugin._


  lazy val root = Project(
    id="sbt-ci",
    base = file(".")
  ).settings(
    organization := "playtech",
    name := "sbt-ci",
    version := "0.1",
    scalaVersion := "2.10.4",
    sbtPlugin := true,
    exportJars := true,

    /* override non-snapshot version in publishing */
    isSnapshot := true,
    publishMavenStyle := false,
    publishArtifact in Test := false,
    publishArtifact in (Compile, packageDoc) := false,
    publishArtifact in (Compile, packageSrc) := false,

    excludeFilter~={(ef)=> ef -- ".bin" },
    mappings in (Compile,packageBin) ~= { _.filter(includeInPackage) },

    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor" % "2.3.9",
      "com.typesafe.akka" %% "akka-stream-experimental" % "1.0-M3",
      "com.typesafe.akka" %% "akka-http-core-experimental" % "1.0-M3",
      "com.typesafe.akka" %% "akka-http-experimental" % "1.0-M3",
      // "com.fasterxml.jackson.core" % "jackson-databind" % "2.4.3",
      "io.spray" %% "spray-json" % "1.3.1"
      /*"org.scalaz" %% "scalaz-core" % "7.1.0"*/),
    resolvers ++= Seq(
      "Akka"  at "http://repo.akka.io/releases/",
      "spray" at "http://http://repo.spray.io",
      Classpaths.typesafeReleases,
      Classpaths.typesafeSnapshots),
    shellPrompt := { s => Project.extract(s).currentProject.id + " > " })
  .settings(scriptedSettings ++ Seq(
    scriptedBufferLog := false,
    scriptedLaunchOpts <+= version apply { v => "-Dproject.version=" + v }
  ): _*)

  import scala.util.matching.Regex
  implicit class RegexContext(sc: StringContext) {
    def r = new Regex(sc.parts.mkString, sc.parts.tail.map(_ => "x"): _*)
  }

  private def includeInPackage(se:(File,String)):Boolean = se._2 match {
    case r"""(?:node_modules/.bin/.*)""" => false
    case r"""(?:node_modules/.*)""" => false
    case _ => true
  }

}
