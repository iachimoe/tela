name := "datastore"

libraryDependencies += "org.eclipse.rdf4j" % "rdf4j-sail-memory" % "2.2.1"

libraryDependencies += "org.eclipse.rdf4j" % "rdf4j-sail-lucene" % "2.2.1"

libraryDependencies += "org.eclipse.rdf4j" % "rdf4j-rio-jsonld" % "2.2.1"

libraryDependencies += "org.eclipse.rdf4j" % "rdf4j-rio-rdfxml" % "2.2.1"

libraryDependencies += "com.github.jsonld-java" % "jsonld-java" % "0.10.0"

//TODO no longer need our own ical?
libraryDependencies += "org.apache.tika" % "tika-parsers" % "1.15"

libraryDependencies += "org.mnode.ical4j" % "ical4j" % "2.0.0"

libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging" % "3.5.0" exclude("org.scala-lang", "scala-reflect")
