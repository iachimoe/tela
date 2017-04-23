name := "datastore"

libraryDependencies += "org.eclipse.rdf4j" % "rdf4j-sail-memory" % "2.2"

libraryDependencies += "org.eclipse.rdf4j" % "rdf4j-sail-lucene" % "2.2"

libraryDependencies += "org.eclipse.rdf4j" % "rdf4j-rio-jsonld" % "2.2"

libraryDependencies += "org.eclipse.rdf4j" % "rdf4j-rio-rdfxml" % "2.2"

libraryDependencies += "com.github.jsonld-java" % "jsonld-java" % "0.10.0"

//TODO no longer need our own ical?
libraryDependencies += "org.apache.tika" % "tika-parsers" % "1.14"

libraryDependencies += "org.mnode.ical4j" % "ical4j" % "2.0.0"

libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging" % "3.5.0" exclude("org.scala-lang", "scala-reflect")

libraryDependencies += "junit" % "junit" % "4.12" % Test

libraryDependencies += "org.scalatest" %% "scalatest" % "2.2.6" % Test

libraryDependencies += "org.mockito" % "mockito-core" % "2.6.4" % Test

libraryDependencies += "com.novocode" % "junit-interface" % "0.11" % Test
