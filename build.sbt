name := "tela"

version in Global := "0.0"

scalaVersion in Global := "2.11.7"

sbtVersion in Global := "0.13.13"

lazy val tela = (project in file(".")) aggregate(runner, baseinterfaces, xmpp, web, datastore)

lazy val runner = project.dependsOn(web, xmpp, datastore).dependsOn(baseinterfaces % "test->test").enablePlugins(PlayScala)

lazy val baseinterfaces = project

lazy val xmpp = project.dependsOn(baseinterfaces % "compile->compile;test->test")

lazy val web = project.dependsOn(baseinterfaces % "compile->compile;test->test")

lazy val datastore = project.dependsOn(baseinterfaces % "compile->compile;test->test")
