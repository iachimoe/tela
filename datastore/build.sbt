name := "datastore"

libraryDependencies ++= Seq(
  "org.eclipse.rdf4j" % "rdf4j-sail-memory" % "2.3.2",
  "org.eclipse.rdf4j" % "rdf4j-sail-lucene" % "2.3.2",
  "org.eclipse.rdf4j" % "rdf4j-rio-jsonld" % "2.3.2",
  "org.eclipse.rdf4j" % "rdf4j-rio-rdfxml" % "2.3.2",
  "com.github.jsonld-java" % "jsonld-java" % "0.12.0",
  "org.apache.tika" % "tika-parsers" % "1.18",
  "org.mnode.ical4j" % "ical4j" % "2.0.0", //TODO no longer need our own ical because tika now has built-in ical support?
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.0"
)
