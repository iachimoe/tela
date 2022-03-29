package tela.datastore

import java.net.URI
import java.nio.file.Paths

import org.apache.tika.metadata.TikaCoreProperties
import tela.baseinterfaces.DataStoreConnection.{FileFormatPredicateKey, HashPredicateKey}
import tela.baseinterfaces.{BaseSpec, ComplexObject, SimpleObject}

trait DataStoreBaseSpec extends BaseSpec {
  protected val TestDataRoot = Paths.get("datastore/src/test/data")
  protected val TestTikaConfigFile = TestDataRoot.resolve("tika.xml")
  protected val TestIcalWithEventFileName = Paths.get("testIcalFile.ics")
  protected val TestIcalFileWithEvent = TestDataRoot.resolve(TestIcalWithEventFileName)
  protected val TestIcalFileWithoutEvent = TestDataRoot.resolve("testIcalFileWithoutEvent.ics")
  protected val TestIcalFileWithEmptyEvent = TestDataRoot.resolve("testIcalFileWithEmptyEvent.ics")
  protected val HashOfTestIcalFile = "d89bda3b9b6cb525c3b9d1f446254cd76abdcc02"

  protected val GenericMediaFileType = new URI("http://schema.org/MediaObject")
  protected val HashPredicate = new URI("http://schema.org/alternateName")
  protected val FileFormatPredicate = new URI("http://schema.org/encodingFormat")
  protected val PlainTextContentType = "text/plain"

  protected val GenericFileDataMap = ComplexObject(GenericMediaFileType, Map(
    HashPredicate -> SimpleObject(Vector(HashPredicateKey)),
    FileFormatPredicate -> SimpleObject(Vector(FileFormatPredicateKey))))

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
