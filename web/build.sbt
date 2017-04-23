name := "web"

libraryDependencies += "com.typesafe.play" %% "play" % "2.5.14"

libraryDependencies += "com.github.spullara.mustache.java" % "compiler" % "0.9.4"

libraryDependencies += "org.webjars" %% "webjars-play" % "2.5.0-4"

libraryDependencies += "org.webjars.bower" % "scribe" % "3.2.0"

libraryDependencies += "org.webjars.bower" % "scribe-plugin-toolbar" % "1.0.0"

libraryDependencies += "junit" % "junit" % "4.12" % Test

libraryDependencies += "org.scalatest" %% "scalatest" % "2.2.6" % Test

//This pulls in dependencies on old versions of play and netty...not nice
libraryDependencies += "org.scalatestplus.play" %% "scalatestplus-play" % "1.5.1" % Test

libraryDependencies += "org.mockito" % "mockito-core" % "2.6.4" % Test

libraryDependencies += "com.novocode" % "junit-interface" % "0.11" % Test

libraryDependencies += "com.typesafe.akka" %% "akka-stream-testkit" % "2.4.17" % Test
