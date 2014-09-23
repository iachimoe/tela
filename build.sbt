name := "tela"

version in Global := "0.0"

scalaVersion in Global := "2.11.1"

lazy val tela = (project in file(".")) aggregate(runner, baseinterfaces, xmpp, web)

lazy val runner = project.dependsOn(web, xmpp)

lazy val baseinterfaces = project

lazy val xmpp = project.dependsOn(baseinterfaces)

lazy val web = project.dependsOn(baseinterfaces)

