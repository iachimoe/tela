import java.net.URI

import DataMappingReads._
import org.scalatest.matchers.should.Matchers._
import play.api.libs.json.Json
import tela.baseinterfaces.{BaseSpec, ComplexObject, DataStoreConnection, SimpleObject}

class DataMappingReadsSpec extends BaseSpec {
  "DataMappingReads" should "deserialize JSON into a ComplexObject" in {
    val expected = ComplexObject(new URI("http://schema.org/AudioObject"), Map(
      new URI("http://schema.org/genre") -> SimpleObject(Vector("xmpDM:genre")),
      new URI("http://schema.org/name") -> SimpleObject(Vector("dc:title")),
      new URI("http://schema.org/author") -> ComplexObject(new URI("http://schema.org/Person"), Map(new URI("http://schema.org/name") -> SimpleObject(Vector("xmpDM:artist"))))))

    val song = Json.obj(DataStoreConnection.MediaItemTypeKey -> "http://schema.org/AudioObject",
      "children" -> Json.obj("http://schema.org/genre" -> Json.obj("properties" -> Json.arr("xmpDM:genre"), "dataType" -> "Text"),
        "http://schema.org/name" -> Json.obj("properties" -> Json.arr("dc:title"), "dataType" -> "Text"),
        "http://schema.org/author" -> Json.obj(DataStoreConnection.MediaItemTypeKey -> "http://schema.org/Person", "children" -> Json.obj("http://schema.org/name" -> Json.obj("properties" -> Json.arr("xmpDM:artist"), "dataType" -> "Text"))))
    )

    song.as[ComplexObject] should === (expected)
  }
}
