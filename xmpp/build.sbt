name := "xmpp"

libraryDependencies ++= Seq(
  "org.igniterealtime.smack" % "smack-extensions" % "4.4.5",
  "org.igniterealtime.smack" % "smack-tcp" % "4.4.5",
  "org.igniterealtime.smack" % "smack-java8" % "4.4.5", // This is needed for Java 8 or higher
  "org.scala-lang.modules" %% "scala-xml" % "1.3.0",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.4"
)
