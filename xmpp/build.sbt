name := "xmpp"

libraryDependencies ++= Seq(
  "org.igniterealtime.smack" % "smack-extensions" % "4.3.4",
  "org.igniterealtime.smack" % "smack-tcp" % "4.3.4",
  "org.igniterealtime.smack" % "smack-java7" % "4.3.4", // This is needed for Java 7 or higher
  "org.scala-lang.modules" %% "scala-xml" % "1.2.0",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2"
)
