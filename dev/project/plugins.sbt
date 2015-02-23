lazy val root = Project("plugins",file(".")).dependsOn(ci)

lazy val ci = file("..").getAbsoluteFile.toURI
