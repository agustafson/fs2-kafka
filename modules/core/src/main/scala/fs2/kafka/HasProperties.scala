package fs2.kafka

trait HasProperties[T] {
  def properties: Map[String, String]

  /**
    * Includes a property with the specified `key` and `value`.
    */
  def withProperty(key: String, value: String): T

  /**
    * Includes the specified keys and values as properties. The
    * keys should be part of the `AdminClientConfig` keys, and
    * the values should be valid choices for the keys.
    */
  def withProperties(properties: (String, String)*): T

  /**
    * Includes the specified keys and values as properties. The
    * keys should be part of the `AdminClientConfig` keys, and
    * the values should be valid choices for the keys.
    */
  def withProperties(properties: Map[String, String]): T
}

trait PropertiesUpdater[T] { self: HasProperties[T] =>
  protected def updateProperties(f: Map[String, String] => Map[String, String]): T

  /**
    * Includes a property with the specified `key` and `value`.
    */
  def withProperty(key: String, value: String): T =
    updateProperties(_.updated(key, value))

  /**
    * Includes the specified keys and values as properties. The
    * keys should be part of the `AdminClientConfig` keys, and
    * the values should be valid choices for the keys.
    */
  def withProperties(properties: (String, String)*): T =
    updateProperties(_ ++ properties.toMap)

  /**
    * Includes the specified keys and values as properties. The
    * keys should be part of the `AdminClientConfig` keys, and
    * the values should be valid choices for the keys.
    */
  def withProperties(properties: Map[String, String]): T =
    updateProperties(_ ++ properties)
}
