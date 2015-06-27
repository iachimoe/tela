name := "web"

libraryDependencies += "org.mashupbots.socko" %% "socko-webserver" % "0.6.0"

libraryDependencies += "com.github.spullara.mustache.java" % "compiler" % "0.9.0"

libraryDependencies += "com.typesafe.play" %% "play-json" % "2.3.9"

libraryDependencies += "junit" % "junit" % "4.12" % Test

libraryDependencies += "org.scalatest" %% "scalatest" % "2.2.4" % Test

libraryDependencies += "org.mockito" % "mockito-core" % "2.0.23-beta" % Test

libraryDependencies += "com.novocode" % "junit-interface" % "0.11" % Test

libraryDependencies += "com.typesafe.akka" %% "akka-testkit" % "2.3.6" % Test
