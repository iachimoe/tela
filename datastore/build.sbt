name := "datastore"

libraryDependencies ++= Seq(
  "org.eclipse.rdf4j" % "rdf4j-sail-memory" % "3.2.0",
  "org.eclipse.rdf4j" % "rdf4j-sail-lucene" % "3.2.0",
  "org.eclipse.rdf4j" % "rdf4j-rio-jsonld" % "3.2.0",
  "org.eclipse.rdf4j" % "rdf4j-rio-rdfxml" % "3.2.0",
  "org.apache.tika" % "tika-parsers" % "1.24.1",
  "org.mnode.ical4j" % "ical4j" % "2.0.0", //TODO no longer need our own ical because tika now has built-in ical support?
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2"
)
