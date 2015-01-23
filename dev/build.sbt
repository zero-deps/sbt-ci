name := "dev"

version := "0.1"

shellPrompt := { s => Project.extract(s).currentProject.id + " > " }

enablePlugins(ci.sbt.CiPlugin)
