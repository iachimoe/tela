name := "xmpp"

libraryDependencies ++= Seq(
  "org.igniterealtime.smack" % "smack-extensions" % "4.4.8",
  "org.igniterealtime.smack" % "smack-tcp" % "4.4.8",
  "org.igniterealtime.smack" % "smack-java8" % "4.4.8", // This is needed for Java 8 or higher
  "org.scala-lang.modules" %% "scala-xml" % "2.3.0",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5"
)
