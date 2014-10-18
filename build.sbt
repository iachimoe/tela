name := "tela"

//TODO: Get this working

//resolvers += "Secure version of maven central" at "https://repo1.maven.org/maven2/"

//externalResolvers := Resolver.withDefaultResolvers(resolvers.value, mavenCentral = false)

version in Global := "0.0"

scalaVersion in Global := "2.11.1"

lazy val tela = (project in file(".")) aggregate(runner, baseinterfaces, xmpp, web, datastore)

lazy val runner = project.dependsOn(web, xmpp, datastore)

lazy val baseinterfaces = project

lazy val xmpp = project.dependsOn(baseinterfaces)

lazy val web = project.dependsOn(baseinterfaces)

lazy val datastore = project.dependsOn(baseinterfaces)

