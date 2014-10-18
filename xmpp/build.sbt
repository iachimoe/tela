name := "xmpp"

resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"

libraryDependencies += "org.igniterealtime.smack" % "smack-extensions" % "4.1.0-alpha4-SNAPSHOT"

libraryDependencies += "org.igniterealtime.smack" % "smack-tcp" % "4.1.0-alpha4-SNAPSHOT"

libraryDependencies += "org.igniterealtime.smack" % "smack-java7" % "4.1.0-alpha4-SNAPSHOT"

libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging" % "latest.integration"

libraryDependencies += "junit" % "junit" % "latest.integration" % Test

libraryDependencies += "org.scalatest" % "scalatest_2.11" % "latest.integration" % Test

libraryDependencies += "org.mockito" % "mockito-core" % "latest.integration" % Test

libraryDependencies += "com.novocode" % "junit-interface" % "0.5" % Test

