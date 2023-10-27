name := "web"

libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play" % "2.9.0",
  "com.github.spullara.mustache.java" % "compiler" % "0.9.11",
  "com.typesafe.play" %% "play-test" % "2.9.0" % Test,
  "com.typesafe.akka" %% "akka-stream-testkit" % "2.6.21" % Test
)
