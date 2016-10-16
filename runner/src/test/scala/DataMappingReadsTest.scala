package tela.runner

import java.net.URI

import org.junit.Assert._
import org.junit.Test
import org.scalatest.junit.AssertionsForJUnit
import play.api.libs.json.Json
import tela.baseinterfaces.{ComplexObject, DataStoreConnection, SimpleObject}
import tela.runner.DataMappingReads._

class DataMappingReadsTest extends AssertionsForJUnit {
  @Test def mp3(): Unit = {
    val expected = ComplexObject(new URI("http://schema.org/AudioObject"), Map(
      new URI("http://schema.org/genre") -> SimpleObject(List("xmpDM:genre")),
      new URI("http://schema.org/name") -> SimpleObject(List("dc:title")),
      new URI("http://schema.org/author") -> ComplexObject(new URI("http://schema.org/Person"), Map(new URI("http://schema.org/name") -> SimpleObject(List("xmpDM:artist"))))))

    val song = Json.obj(DataStoreConnection.MediaItemTypeKey -> "http://schema.org/AudioObject",
      "children" -> Json.obj("http://schema.org/genre" -> Json.obj("properties" -> Json.arr("xmpDM:genre"), "dataType" -> "Text"),
        "http://schema.org/name" -> Json.obj("properties" -> Json.arr("dc:title"), "dataType" -> "Text"),
        "http://schema.org/author" -> Json.obj(DataStoreConnection.MediaItemTypeKey -> "http://schema.org/Person", "children" -> Json.obj("http://schema.org/name" -> Json.obj("properties" -> Json.arr("xmpDM:artist"), "dataType" -> "Text"))))
    )

    assertEquals(expected, song.as[ComplexObject])
  }
}
