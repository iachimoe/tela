name := "tela"

version in Global := "0.0"

scalaVersion in Global := "3.3.3"

lazy val commonSettings = List(
  //This doesn't seem to work properly in intellij anymore, hopefully will be fixed in the future
  /*scalacOptions ++= Seq(
    "-Wvalue-discard",
    "-Xfatal-warnings"
  ),
  Test / scalacOptions --= Seq(
    "-Wvalue-discard",
  )*/
)

lazy val tela = (project in file(".")) aggregate(runner, baseinterfaces, xmpp, web, datastore)

lazy val runner = project.settings(commonSettings).dependsOn(web, xmpp, datastore).dependsOn(baseinterfaces % "test->test").enablePlugins(PlayScala)

lazy val baseinterfaces = project.settings(commonSettings)

lazy val xmpp = project.settings(commonSettings).dependsOn(baseinterfaces % "compile->compile;test->test")

lazy val web = project.settings(commonSettings).dependsOn(baseinterfaces % "compile->compile;test->test")

lazy val datastore = project.settings(commonSettings).dependsOn(baseinterfaces % "compile->compile;test->test")
