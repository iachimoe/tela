package org.jivesoftware.smack

import org.jivesoftware.smack.packet.Stanza

object StanzaCollectorHelper {
  def createStanzaCollector(connection: XMPPConnection): StanzaCollector =
    new StanzaCollector(connection, StanzaCollector.newConfiguration())

  def callProcessStanza(collector: StanzaCollector, packet: Stanza): Unit = {
    collector.processStanza(packet)
  }
}
