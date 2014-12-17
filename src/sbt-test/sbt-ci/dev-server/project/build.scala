import sbt._
import sbt.Keys._

object DevBuild extends Build{
  import ci.sbt.CiPlugin
  lazy val root = Project(
    id="dev-server",
    base = file(".")
  ).settings(
    organization := "playtech",
    name := "dev-server",
    version := "0.1",
    scalaVersion := "2.10.4",
    excludeFilter~={_ -- ".bin" }
  ).settings(
      TaskKey[Unit]("gulp-default") <<= (crossTarget, target, classDirectory in Compile) map { (crossTarget, target, classes) =>
        println(s"$classes")
        val x = "npm install --prefix target/scala-2.10/classes" #|| "ls" !!

        println(x)
        //Seq("cd", classes.toString)!!


        //val pr = s"(cd ${classes.toString} &&  npm install)" !!

        //println("npm"  + pr)

//        val process = s"${(classes / "node_modules" / "gulp" / "bin" / "gulp.js").toString}" !!

//        println("output: " + process)
      }
  ).enablePlugins(CiPlugin)
}