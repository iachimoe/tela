name := "xmpp"

libraryDependencies += "org.igniterealtime.smack" % "smack-extensions" % "4.1.8"

libraryDependencies += "org.igniterealtime.smack" % "smack-tcp" % "4.1.8"

libraryDependencies += "org.igniterealtime.smack" % "smack-java7" % "4.1.8"

libraryDependencies += "org.scala-lang.modules" %% "scala-xml" % "1.0.5"

libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging" % "3.1.0" exclude("org.scala-lang", "scala-reflect")

libraryDependencies += "junit" % "junit" % "4.12" % Test

libraryDependencies += "org.scalatest" %% "scalatest" % "2.2.6" % Test

libraryDependencies += "org.mockito" % "mockito-core" % "2.2.1" % Test

libraryDependencies += "com.novocode" % "junit-interface" % "0.11" % Test
