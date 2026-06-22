name := "datastore"

// Since moving from the memory to the nativerdf store, the tests got a bit flaky
// and forcing non-parallel execution seems to improve this (though I thought they were non-parallel by default)
Test / parallelExecution := false

libraryDependencies ++= Seq(
  "org.eclipse.rdf4j" % "rdf4j-sail-nativerdf" % "5.3.1",
  "org.eclipse.rdf4j" % "rdf4j-sail-lucene" % "5.3.1",
  "org.eclipse.rdf4j" % "rdf4j-rio-jsonld" % "5.3.1",
  "org.eclipse.rdf4j" % "rdf4j-rio-rdfxml" % "5.3.1",
  "org.apache.tika" % "tika-core" % "3.3.1",
  "org.apache.tika" % "tika-parsers-standard-package" % "3.3.1",
  "org.mnode.ical4j" % "ical4j" % "4.2.5",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.6"
)
