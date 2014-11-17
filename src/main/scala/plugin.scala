package syrc.ci

import sbt._
import Keys._

object CiPlugin extends AutoPlugin{
  override def trigger = allRequirements
  override lazy val projectSettings = Seq(commands+=helloCommand)

  lazy val helloCommand = Command.command("hello"){ (state:State) =>
    println("Hi")
    state
  }
}