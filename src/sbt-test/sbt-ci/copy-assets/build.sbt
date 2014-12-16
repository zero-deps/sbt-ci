enablePlugins(CiPlugin)

name := "copy-test"

version := "0.1.0"

TaskKey[Unit]("check-assets") := {
  val s = streams.value
  s.log.info("AAAAAAAAAAAAAAA")
  assert(Seq(1,2) contains 1, "1 in " + Seq(1,2))
  ()
}

TaskKey[Seq[File]]("genresource") <<= (unmanagedResourceDirectories in Compile) map { (dirs) =>
  val file = dirs.head / "foo.txt"
  IO.write(file, "bye")
  Seq(file)
}

TaskKey[Seq[File]]("genresource2") <<= (unmanagedResourceDirectories in Compile) map { (dirs) =>
  val file = dirs.head / "bar.txt"
  IO.write(file, "bye")
  Seq(file)
}

TaskKey[Unit]("checkfoo") <<= (crossTarget) map { (crossTarget) =>
  val process = sbt.Process("echo", Seq(crossTarget.getName))
  val out = (process!!)
  println("output: " + out)
  ()
}