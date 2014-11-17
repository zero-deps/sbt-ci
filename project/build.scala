import sbt._
import sbt.Keys._

object SbtCiBuild extends Build{
  lazy val root = Project(
    id="sbt-ci",
    base = file("."),
    settings = Seq()
  ).settings(
    organization := "synrc",
    name := "sbt-ci",
    version := "0.1",
    scalaVersion := "2.10.4",
    sbtPlugin := true,
    libraryDependencies ++= Seq()
  )
}
