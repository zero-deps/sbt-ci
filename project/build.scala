package sbt.plugins

import sbt._
import sbt.Keys._

object CiBuild extends Build{
  lazy val root = Project(
    id="sbt-ci",
    base = file(".")
  ).settings(
    organization := "synrc",
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
      "com.typesafe.akka" %% "akka-http-core-experimental" % "0.11",
      "com.typesafe.akka" %% "akka-stream-experimental" % "0.11"),
    resolvers ++= Seq(
      "Akka"     at "http://repo.akka.io/releases/",
      "Typesafe" at "http://repo.typesafe.com/typesafe/releases/")
  )
}
