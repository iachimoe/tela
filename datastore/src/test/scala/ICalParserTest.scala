package tela.datastore

import java.io.{BufferedInputStream, FileInputStream}

import org.apache.tika.metadata.{Metadata, TikaCoreProperties}
import org.apache.tika.parser.ParseContext
import org.apache.tika.sax.BodyContentHandler
import org.junit.Assert._
import org.junit.Test
import org.scalatest.junit.AssertionsForJUnit
import tela.datastore.TestData._

class ICalParserTest extends AssertionsForJUnit {
  @Test def event(): Unit = {
    val metadata = getMetadataFromIcalFile(TestIcalFileWithEvent)

    assertEquals("DDD London #3 - Strategic and Collaborative Domain-Driven Design", metadata.get(TikaCoreProperties.TITLE))
    assertEquals("Domain-Driven Design London / DDD London\nTuesday, July 5 at 6:30 PM", metadata.get(TikaCoreProperties.DESCRIPTION))
    assertEquals("51.52", metadata.get(TikaCoreProperties.LATITUDE))
    assertEquals("-0.10", metadata.get(TikaCoreProperties.LONGITUDE))
    assertEquals("2016-07-05T18:30:00", metadata.get(TikaCoreProperties.METADATA_DATE))
  }

  @Test def icalFileWithoutEvent(): Unit = {
    val metadata = getMetadataFromIcalFile(TestIcalFileWithoutEvent)
    assertEquals(0, metadata.size())
  }

  @Test def icalFileWithEmptyEvent(): Unit = {
    val metadata = getMetadataFromIcalFile(TestIcalFileWithEmptyEvent)
    assertEquals(0, metadata.size())
  }

  private def getMetadataFromIcalFile(filename: String): Metadata = {
    val parser = new ICalParser()
    val metadata = new Metadata()
    val handler = new BodyContentHandler(-1)
    val context = new ParseContext()

    val stream = new BufferedInputStream(new FileInputStream(filename))
    try {
      parser.parse(stream, handler, metadata, context)
      metadata
    } finally {
      stream.close()
    }
  }
}
