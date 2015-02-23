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
      TaskKey[Unit]("npm-install") <<= (streams, classDirectory in Compile) map { (s, base) =>
        s.log("-> npm install")
        s"npm install --no-optional --prefix $base" !! s.log
      },
      TaskKey[Unit]("chmod-x-gulp") <<= (streams, classDirectory in Compile) map { (s, base) =>
        s"chmod a+x ${base / "node_modules" / ".bin" / "gulp"}" !! s.log
      },
      TaskKey[Unit]("gulp-watch") <<= (streams, classDirectory in Compile) map { (s, base) =>
        s.log.info(s"gulp watch in $base")
        val gulp = base / "node_modules" / "gulp" / "bin" / "gulp.js"
        
        
        
        val watch = (s"sudo $gulp watch" !!)
        
        s.log.info("[fuck]" + watch.trim)
        ()
      }
  ).enablePlugins(CiPlugin)
}