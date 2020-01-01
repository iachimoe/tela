name := "tela"

version in Global := "0.0"

scalaVersion in Global := "2.13.1"

lazy val tela = (project in file(".")) aggregate(runner, baseinterfaces, xmpp, web, datastore)

lazy val runner = project.dependsOn(web, xmpp, datastore).dependsOn(baseinterfaces % "test->test").enablePlugins(PlayScala)

lazy val baseinterfaces = project

lazy val xmpp = project.dependsOn(baseinterfaces % "compile->compile;test->test")

lazy val web = project.dependsOn(baseinterfaces % "compile->compile;test->test")

lazy val datastore = project.dependsOn(baseinterfaces % "compile->compile;test->test")

import com.typesafe.sbt.packager.docker._

dockerCommands := Seq(
  Cmd("FROM", "openjdk:8-jre-alpine"),
  Cmd("WORKDIR", "/opt/docker"),
  Cmd("ENTRYPOINT", s"""["/opt/docker/bin/runner"]""")
)
