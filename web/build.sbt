name := "web"

libraryDependencies ++= Seq(
  "org.playframework" %% "play" % "3.0.5",
  "com.github.spullara.mustache.java" % "compiler" % "0.9.14",
  "org.playframework" %% "play-test" % "3.0.5" % Test,
  "org.apache.pekko" %% "pekko-stream-testkit" % "1.0.3" % Test
)
