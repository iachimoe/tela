name := "tela"

version in Global := "0.0"

scalaVersion in Global := "3.3.1"

lazy val commonSettings = List(
  scalacOptions ++= Seq(
    "-Wvalue-discard",
    "-Xfatal-warnings"
  ),
  scalacOptions in Test --= Seq( //TODO Why won't / syntax work here?
    "-Wvalue-discard",
  )
)

lazy val tela = (project in file(".")) aggregate(runner, baseinterfaces, xmpp, web, datastore)

lazy val runner = project.settings(commonSettings).dependsOn(web, xmpp, datastore).dependsOn(baseinterfaces % "test->test").enablePlugins(PlayScala)

lazy val baseinterfaces = project.settings(commonSettings)

lazy val xmpp = project.settings(commonSettings).dependsOn(baseinterfaces % "compile->compile;test->test")

lazy val web = project.settings(commonSettings).dependsOn(baseinterfaces % "compile->compile;test->test")

lazy val datastore = project.settings(commonSettings).dependsOn(baseinterfaces % "compile->compile;test->test")
