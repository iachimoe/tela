name := "web"

libraryDependencies += "org.mashupbots.socko" % "socko-webserver_2.11" % "latest.integration"

libraryDependencies += "com.github.spullara.mustache.java" % "compiler" % "latest.integration"

libraryDependencies += "com.typesafe.play" % "play-json_2.11" % "latest.integration"

libraryDependencies += "junit" % "junit" % "latest.integration" % Test

libraryDependencies += "org.scalatest" % "scalatest_2.11" % "2.2.3-SNAP2" % Test

libraryDependencies += "org.mockito" % "mockito-core" % "latest.integration" % Test

libraryDependencies += "com.novocode" % "junit-interface" % "0.5" % Test

libraryDependencies += "com.typesafe.akka" % "akka-testkit_2.11" % "2.3.2" % Test
