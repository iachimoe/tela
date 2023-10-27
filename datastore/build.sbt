name := "datastore"

libraryDependencies ++= Seq(
  "org.eclipse.rdf4j" % "rdf4j-sail-memory" % "4.3.7",
  "org.eclipse.rdf4j" % "rdf4j-sail-lucene" % "4.3.7",
  "org.eclipse.rdf4j" % "rdf4j-rio-jsonld" % "4.3.7",
  "org.eclipse.rdf4j" % "rdf4j-rio-rdfxml" % "4.3.7",
  "org.apache.tika" % "tika-core" % "2.9.1",
  // Excluding tika-parser-cad-module as it pulls in jackson 2.15, when akka expects 2.14
  "org.apache.tika" % "tika-parsers-standard-package" % "2.9.1" exclude("org.apache.tika", "tika-parser-cad-module"),
  "org.mnode.ical4j" % "ical4j" % "3.2.13",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5"
)
