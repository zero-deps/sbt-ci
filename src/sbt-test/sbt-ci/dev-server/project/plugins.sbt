addSbtPlugin("playtech" % "sbt-ci" % sys.props("project.version"))

scalacOptions ++= Seq("-feature", "-deprecation", "-language:postfixOps")
