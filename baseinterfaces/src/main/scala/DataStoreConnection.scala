package tela.baseinterfaces

trait DataStoreConnection {
  def closeConnection(): Unit

  def publish(uri: String): Unit

  def insertJSON(data: String): Unit

  def retrieveJSON(uri: String): String

  def retrievePublishedDataAsJSON(user: String, uri: String): String
}
