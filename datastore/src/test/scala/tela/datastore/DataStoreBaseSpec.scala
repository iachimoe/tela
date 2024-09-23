package tela.datastore

import java.net.URI
import java.nio.file.Paths
import org.apache.tika.metadata.TikaCoreProperties
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.util.Values.iri
import tela.baseinterfaces.DataStoreConnection.{FileFormatPredicateKey, FileNamePredicateKey, HashPredicateKey, TextContentPredicateKey}
import tela.baseinterfaces.{BaseSpec, ComplexObject, DataType, SimpleObject}

import java.util.UUID

trait DataStoreBaseSpec extends BaseSpec {
  protected def asIRI(uri: URI): IRI = iri(uri.toString)
  protected def urnFromUuid(uuid: UUID): URI = new URI(DataStoreConnectionImpl.URNBaseForUUIDs + uuid)

  protected val TestDataRoot = Paths.get("datastore/src/test/data")
  protected val TestTikaConfigFile = TestDataRoot.resolve("tika.xml")

  protected val TestHtmlFileName = Paths.get("testHTMLFile.html")
  protected val TestHtmlFile = TestDataRoot.resolve(TestHtmlFileName)
  protected val HashOfTestHtmlFile = "85b94c9ff01e60d63fa7005bf2c9625f4a437024"

  protected val TestTextFileName = Paths.get("testTextFile.txt")
  protected val TestTextFile = TestDataRoot.resolve(TestTextFileName)
  protected val HashOfTestTextFile = "09fac8dbfd27bd9b4d23a00eb648aa751789536d"

  protected val TestMP3FileName = Paths.get("testMP3.mp3")
  protected val TestMP3 = TestDataRoot.resolve(TestMP3FileName)
  protected val HashOfTestMP3 = "a82e3d27ec0184d28b0de85a70242e9985213143"

  protected val TestZipFileName = Paths.get("testZipFileWithTextAndMP3.zip")
  protected val TestZipFile = TestDataRoot.resolve(TestZipFileName)
  protected val HashOfTestZipFile = "9ce5edc3254f892196a905e86f39398ffa183727"

  protected val TestUUID = UUID.fromString("00000000-0000-0000-c000-000000000046")
  protected val URNWithTestUUID = DataStoreConnectionImpl.URNBaseForUUIDs + TestUUID
  protected val URNWithTestUUIDAsURI = new URI(URNWithTestUUID)

  protected val TestIcalWithEventFileName = Paths.get("testIcalFile.ics")
  protected val TestIcalFileWithEvent = TestDataRoot.resolve(TestIcalWithEventFileName)
  protected val TestIcalFileWithoutEvent = TestDataRoot.resolve("testIcalFileWithoutEvent.ics")
  protected val TestIcalFileWithEmptyEvent = TestDataRoot.resolve("testIcalFileWithEmptyEvent.ics")
  protected val HashOfTestIcalFile = "d89bda3b9b6cb525c3b9d1f446254cd76abdcc02"

  protected val GenericMediaFileType = new URI("http://schema.org/MediaObject")
  protected val HashPredicate = new URI("http://schema.org/identifier")
  protected val FileFormatPredicate = new URI("http://schema.org/encodingFormat")
  protected val FileNamePredicate = new URI("http://schema.org/alternateName")
  protected val TextContentPredicate = new URI("http://schema.org/description")
  protected val LastModifiedPredicate = new URI("http://schema.org/lastModified")
  protected val PlainTextContentType = "text/plain"
  protected val ZipFileContentType = "application/zip"

  protected val GenericFileDataMap = ComplexObject(GenericMediaFileType, Map(
    HashPredicate -> SimpleObject(Vector(HashPredicateKey)),
    FileNamePredicate -> SimpleObject(Vector(FileNamePredicateKey)),
    FileFormatPredicate -> SimpleObject(Vector(FileFormatPredicateKey)),
    TextContentPredicate -> SimpleObject(Vector(TextContentPredicateKey)),
    LastModifiedPredicate -> SimpleObject(Vector(TikaCoreProperties.MODIFIED.getName), DataType.DateTime)))

  protected val MP3ContentType = "audio/mpeg"
  protected val MP3ObjectType = new URI("http://schema.org/AudioObject")

  private val PersonType = new URI("http://schema.org/Person")
  protected val MP3FileDataMap = ComplexObject(MP3ObjectType, Map(
    new URI("http://schema.org/genre") -> SimpleObject(Vector("xmpDM:genre")),
    new URI("http://schema.org/name") -> SimpleObject(Vector("dc:title")),
    new URI("http://schema.org/author") -> ComplexObject(PersonType, Map(new URI("http://schema.org/name") -> SimpleObject(Vector("xmpDM:artist")))))
  )

  protected val ICalDataMap = ComplexObject(GenericMediaFileType, Map(
    new URI("http://schema.org/name") -> SimpleObject(Vector(TikaCoreProperties.TITLE.getName))
  ))

  protected val PlainTextDataMap = ComplexObject(GenericMediaFileType, Map(
    new URI("http://schema.org/name") -> SimpleObject(Vector(TikaCoreProperties.RESOURCE_NAME_KEY))
  ))
}
