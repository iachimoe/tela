package tela.datastore

import java.io.InputStream
import java.time.format.DateTimeFormatter
import java.time.{ZoneId, ZonedDateTime}
import java.util
import java.util.Collections
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.component.CalendarComponent
import net.fortuna.ical4j.model.property.{DtStart, Geo}
import net.fortuna.ical4j.model.{Component, Property}
import org.apache.tika.metadata.{Metadata, TikaCoreProperties}
import org.apache.tika.mime.MediaType
import org.apache.tika.parser.{AbstractParser, ParseContext}
import org.xml.sax.ContentHandler

import scala.jdk.OptionConverters._
import scala.reflect.ClassTag

//TODO This is a very rudimentary parser that is unlikely to stand up very well in the real world
//Hopefully Tika includes an ical parser soon
class ICalParser extends AbstractParser {
  private val dateFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

  override def getSupportedTypes(context: ParseContext): util.Set[MediaType] = Collections.singleton(MediaType.parse(ICalContentType))

  override def parse(stream: InputStream, handler: ContentHandler, metadata: Metadata, context: ParseContext): Unit = {
    getVEventFromCalendar(stream).foreach(component => {
      property[Property](Property.SUMMARY, component).foreach(property => metadata.set(TikaCoreProperties.TITLE, property.getValue))
      property[Property](Property.DESCRIPTION, component).foreach(property => metadata.set(TikaCoreProperties.DESCRIPTION, property.getValue))
      property[Geo](Property.GEO, component).foreach(property => {
        metadata.set(TikaCoreProperties.LATITUDE, property.getLatitude.toString)
        metadata.set(TikaCoreProperties.LONGITUDE, property.getLongitude.toString)
      })
      property[DtStart[ZonedDateTime]](Property.DTSTART, component).foreach(property => {
        val formattedDate = ZonedDateTime.ofInstant(property.getDate.toInstant, ZoneId.of("UTC")).format(dateFormatter)
        metadata.set(TikaCoreProperties.METADATA_DATE, formattedDate)
      })
    })
  }

  private def getVEventFromCalendar(stream: InputStream): Option[CalendarComponent] =
    new CalendarBuilder().build(stream).getComponent[CalendarComponent](Component.VEVENT).toScala

  private def property[T <: Property : ClassTag](property: String, component: Component): Option[T] = {
    val pf: PartialFunction[Property, T] = { case result: T => result }
    component.getProperty(property).toScala.collect[T](pf)
  }
}
