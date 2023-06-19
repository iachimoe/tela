name := "web"

libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play" % "2.8.19",
  "com.github.spullara.mustache.java" % "compiler" % "0.9.10",
  "com.typesafe.play" %% "play-test" % "2.8.19" % Test,
  "com.typesafe.akka" %% "akka-stream-testkit" % "2.6.20" % Test
)
