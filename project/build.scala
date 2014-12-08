package synrc.sbt

import sbt._
import sbt.Keys._

object CiBuild extends Build{
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
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor" % "2.3.7",
      "com.typesafe.akka" %% "akka-contrib" % "2.3.7",
      "com.typesafe.akka" %% "akka-remote" % "2.3.7",
      "com.typesafe.akka" %% "akka-persistence-experimental" % "2.3.7",
      "com.typesafe.akka" %% "akka-http-core-experimental" % "1.0-M1",
      "com.typesafe.akka" %% "akka-http-java-experimental" % "1.0-M1", // routing
      "com.typesafe.akka" %% "akka-parsing-experimental" % "1.0-M1",
      "com.typesafe.akka" %% "akka-stream-experimental" % "1.0-M1",
      "com.fasterxml.jackson.core" % "jackson-databind" % "2.4.3",
      "io.spray" %% "spray-json" % "1.3.1",
      "org.scalaz" %% "scalaz-core" % "7.1.0"),
    resolvers ++= Seq(
      "Akka"  at "http://repo.akka.io/releases/",
      "spray" at "http://http://repo.spray.io",
      Classpaths.typesafeReleases,
      Classpaths.typesafeSnapshots))
}
