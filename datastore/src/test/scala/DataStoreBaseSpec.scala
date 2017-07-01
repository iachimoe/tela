package tela.datastore

import java.net.URI

import org.apache.tika.metadata.{TikaCoreProperties, TikaMetadataKeys}
import tela.baseinterfaces.{BaseSpec, ComplexObject, SimpleObject}
import tela.baseinterfaces.DataStoreConnection.{FileFormatPredicateKey, HashPredicateKey}

trait DataStoreBaseSpec extends BaseSpec {
  protected val TestDataRoot = "datastore/src/test/data"
  protected val TestTikaConfigFile = TestDataRoot + "/tika.xml"
  protected val TestIcalWithEventFileName = "testIcalFile.ics"
  protected val TestIcalFileWithEvent = TestDataRoot + "/" + TestIcalWithEventFileName
  protected val TestIcalFileWithoutEvent = TestDataRoot + "/testIcalFileWithoutEvent.ics"
  protected val TestIcalFileWithEmptyEvent = TestDataRoot + "/testIcalFileWithEmptyEvent.ics"
  protected val HashOfTestIcalFile = "d89bda3b9b6cb525c3b9d1f446254cd76abdcc02"

  protected val GenericMediaFileType = new URI("http://schema.org/MediaObject")
  protected val HashPredicate = new URI("http://schema.org/alternateName")
  protected val FileFormatPredicate = new URI("http://schema.org/encodingFormat")
  protected val PlainTextContentType = "text/plain"

  protected val GenericFileDataMap = ComplexObject(GenericMediaFileType, Map(
    HashPredicate -> SimpleObject(List(HashPredicateKey)),
    FileFormatPredicate -> SimpleObject(List(FileFormatPredicateKey))))

  protected val MP3ContentType = "audio/mpeg"
  protected val MP3ObjectType = new URI("http://schema.org/AudioObject")

  private val PersonType = new URI("http://schema.org/Person")
  protected val MP3FileDataMap = ComplexObject(MP3ObjectType, Map(
    new URI("http://schema.org/genre") -> SimpleObject(List("xmpDM:genre")),
    new URI("http://schema.org/name") -> SimpleObject(List("dc:title")),
    new URI("http://schema.org/author") -> ComplexObject(PersonType, Map(new URI("http://schema.org/name") -> SimpleObject(List("xmpDM:artist")))))
  )

  protected val ICalDataMap = ComplexObject(GenericMediaFileType, Map(
    new URI("http://schema.org/name") -> SimpleObject(List(TikaCoreProperties.TITLE.getName))
  ))

  protected val PlainTextDataMap = ComplexObject(GenericMediaFileType, Map(
    new URI("http://schema.org/name") -> SimpleObject(List(TikaMetadataKeys.RESOURCE_NAME_KEY))
  ))
}
