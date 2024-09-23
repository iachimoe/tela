name := "datastore"

libraryDependencies ++= Seq(
  "org.eclipse.rdf4j" % "rdf4j-sail-memory" % "5.0.2",
  "org.eclipse.rdf4j" % "rdf4j-sail-lucene" % "5.0.2",
  "org.eclipse.rdf4j" % "rdf4j-rio-jsonld" % "5.0.2",
  "org.eclipse.rdf4j" % "rdf4j-rio-rdfxml" % "5.0.2",
  "org.apache.tika" % "tika-core" % "2.9.2",
  // Excluding tika-parser-cad-module as it pulls in jackson 2.15, when pekko expects 2.14
  "org.apache.tika" % "tika-parsers-standard-package" % "2.9.2" exclude("org.apache.tika", "tika-parser-cad-module"),
  "org.mnode.ical4j" % "ical4j" % "4.0.4",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5"
)
