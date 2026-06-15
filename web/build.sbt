name := "web"

libraryDependencies ++= Seq(
  "org.playframework" %% "play" % "3.0.11",
  "com.github.spullara.mustache.java" % "compiler" % "0.9.14",
  // explicitly pulling in a more recent version of pekko, as this is supported by play
  // and the ancient version of jackson was getting increasingly problematic
  "org.apache.pekko" %% "pekko-http-core" % "1.3.0",
  "org.apache.pekko" %% "pekko-actor" % "1.6.0",
  "org.apache.pekko" %% "pekko-actor-typed" % "1.6.0",
  "org.apache.pekko" %% "pekko-stream" % "1.6.0",
  "org.apache.pekko" %% "pekko-slf4j" % "1.6.0",
  "org.apache.pekko" %% "pekko-serialization-jackson" % "1.6.0",
  "org.apache.pekko" %% "pekko-stream-testkit" % "1.6.0" % Test,
  "org.playframework" %% "play-test" % "3.0.11" % Test
)
