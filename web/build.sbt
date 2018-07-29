name := "web"

libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play" % "2.6.17",
  "com.github.spullara.mustache.java" % "compiler" % "0.9.5",
  "org.webjars" %% "webjars-play" % "2.6.3",
  "org.webjars.bower" % "scribe" % "3.2.0",
  "org.webjars.bower" % "scribe-plugin-toolbar" % "1.0.0",
  "com.typesafe.play" %% "play-test" % "2.6.17" % Test,
  "com.typesafe.akka" %% "akka-stream-testkit" % "2.5.11" % Test
)
