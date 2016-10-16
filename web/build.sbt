name := "web"

libraryDependencies += "org.mashupbots.socko" %% "socko-webserver" % "0.6.0" exclude("org.scala-lang", "scala-reflect")

libraryDependencies += "com.github.spullara.mustache.java" % "compiler" % "0.9.3"

libraryDependencies += "com.typesafe.play" %% "play-json" % "2.3.9" exclude("org.scala-lang", "scala-reflect")

libraryDependencies += "org.webjars" % "webjars-locator-core" % "0.31"

libraryDependencies += "org.webjars.bower" % "requirejs" % "2.2.0"

libraryDependencies += "org.webjars.bower" % "scribe" % "3.2.0"

libraryDependencies += "org.webjars.bower" % "scribe-plugin-toolbar" % "1.0.0"

libraryDependencies += "junit" % "junit" % "4.12" % Test

libraryDependencies += "org.scalatest" %% "scalatest" % "2.2.6" % Test

libraryDependencies += "org.mockito" % "mockito-core" % "2.2.1" % Test

libraryDependencies += "com.novocode" % "junit-interface" % "0.11" % Test

libraryDependencies += "com.typesafe.akka" %% "akka-testkit" % "2.3.6" % Test
