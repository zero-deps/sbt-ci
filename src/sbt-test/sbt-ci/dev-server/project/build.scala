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
    includeFilter in unmanagedResources ~= {_ && "*/.bin/*"}
  ).settings(
      TaskKey[Unit]("check-assets") := {
        val s = streams.value
        s.log.info("AAAAAAAAAAAAAAA")
        assert(Seq(1,2) contains 1, "1 in " + Seq(1,2))
        ()
      },
      TaskKey[Seq[File]]("genresource") <<= (unmanagedResourceDirectories in Compile) map { (dirs) =>
        val file = dirs.head / "foo.txt"
        IO.write(file, "bye")
        Seq(file)
      },
      TaskKey[Unit]("gulp-default") <<= (crossTarget, target, classDirectory in Compile) map { (crossTarget, target, classes) =>
        val process = s"${(classes / "node_modules" / ".bin" / "gulp").toString} -v" !!

        println("output: " + process)
      }
  ).enablePlugins(CiPlugin)
}