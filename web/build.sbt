name := "web"

libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play" % "2.8.13",
  "com.github.spullara.mustache.java" % "compiler" % "0.9.10",
  "org.webjars" %% "webjars-play" % "2.8.13",
  "org.webjars.npm" % "bootstrap" % "5.1.3",
  "org.webjars" % "font-awesome" % "6.1.0",
  "org.webjars.bower" % "scribe" % "3.2.0",
  "org.webjars.bower" % "scribe-plugin-toolbar" % "1.0.0",
  "com.typesafe.play" %% "play-test" % "2.8.13" % Test,
  "com.typesafe.akka" %% "akka-stream-testkit" % "2.6.18" % Test
)
