name := "xmpp"

libraryDependencies += "org.igniterealtime.smack" % "smack-extensions" % "4.1.9"

libraryDependencies += "org.igniterealtime.smack" % "smack-tcp" % "4.1.9"

// This is needed for Java 7 or higher
libraryDependencies += "org.igniterealtime.smack" % "smack-java7" % "4.1.9"

libraryDependencies += "org.scala-lang.modules" %% "scala-xml" % "1.0.4"

libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging" % "3.5.0" exclude("org.scala-lang", "scala-reflect")
