name := "web"

libraryDependencies += "com.typesafe.play" %% "play" % "2.5.15"

libraryDependencies += "com.github.spullara.mustache.java" % "compiler" % "0.9.4"

libraryDependencies += "org.webjars" %% "webjars-play" % "2.5.0-4"

libraryDependencies += "org.webjars.bower" % "scribe" % "3.2.0"

libraryDependencies += "org.webjars.bower" % "scribe-plugin-toolbar" % "1.0.0"

libraryDependencies += "com.typesafe.play" %% "play-test" % "2.5.15" % Test

libraryDependencies += "com.typesafe.akka" %% "akka-stream-testkit" % "2.4.18" % Test
