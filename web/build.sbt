name := "web"

libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play" % "2.8.1",
  "com.github.spullara.mustache.java" % "compiler" % "0.9.6",
  "org.webjars" %% "webjars-play" % "2.8.0",
  "org.webjars.bower" % "scribe" % "3.2.0",
  "org.webjars.bower" % "scribe-plugin-toolbar" % "1.0.0",
  "com.typesafe.play" %% "play-test" % "2.8.1" % Test,
  "com.typesafe.akka" %% "akka-stream-testkit" % "2.6.3" % Test
)
