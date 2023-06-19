name := "datastore"

libraryDependencies ++= Seq(
  "org.eclipse.rdf4j" % "rdf4j-sail-memory" % "4.0.4",
  "org.eclipse.rdf4j" % "rdf4j-sail-lucene" % "4.0.4",
  "org.eclipse.rdf4j" % "rdf4j-rio-jsonld" % "4.0.4",
  "org.eclipse.rdf4j" % "rdf4j-rio-rdfxml" % "4.0.4",
  "org.apache.tika" % "tika-core" % "2.5.0",
  "org.apache.tika" % "tika-parsers-standard-package" % "2.5.0",
  "org.mnode.ical4j" % "ical4j" % "3.2.10",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",

  //TODO The below should be temporary, it relates to a clash between logback versions of
  //tika and play, see https://github.com/playframework/playframework/issues/11499
  "ch.qos.logback" % "logback-classic" % "1.3.5"
)
