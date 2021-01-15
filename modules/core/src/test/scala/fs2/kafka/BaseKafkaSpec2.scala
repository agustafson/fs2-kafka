package fs2.kafka

import cats.effect.{IO, Sync}
import fs2.kafka.internal.converters.collection._
import java.util.UUID

import com.dimafeng.testcontainers.{ForAllTestContainer, KafkaContainer}

import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.consumer.{KafkaConsumer => KConsumer}
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.ByteArrayDeserializer

import scala.concurrent.duration._
import org.apache.kafka.clients.admin.NewTopic
import scala.util.Try
import org.apache.kafka.clients.admin.AdminClient

import net.manub.embeddedkafka.{
  EmbeddedKafkaConfig,
  KafkaUnavailableException,
  duration2JavaDuration
}
import org.apache.kafka.clients.consumer.{
  ConsumerConfig,
  KafkaConsumer,
  OffsetAndMetadata,
  OffsetResetStrategy
}
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.{KafkaException, TopicPartition}
import scala.collection.mutable.ListBuffer
import java.util.concurrent.TimeoutException

abstract class BaseKafkaSpec2 extends BaseAsyncSpec with ForAllTestContainer {

  val zkSessionTimeoutMs = 10000
  val zkConnectionTimeoutMs = 10000
  protected val topicCreationTimeout: FiniteDuration = 2.seconds
  protected val topicDeletionTimeout: FiniteDuration = 2.seconds
  protected val adminClientCloseTimeout: FiniteDuration = 2.seconds
  final val transactionTimeoutInterval: FiniteDuration = 1.second

  override val container = new KafkaContainer(Some("6.0.1"))
    .configure { container =>
      container
        .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
        .withEnv(
          "KAFKA_TRANSACTION_ABORT_TIMED_OUT_TRANSACTION_CLEANUP_INTERVAL_MS",
          transactionTimeoutInterval.toMillis.toString)
          .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
        
      ()
    }

  protected val consumerPollingTimeout: FiniteDuration = 1.second

  implicit final val stringSerializer: KafkaSerializer[String] =
    new org.apache.kafka.common.serialization.StringSerializer

  implicit final val stringDeserializer: KafkaDeserializer[String] =
    new org.apache.kafka.common.serialization.StringDeserializer

  def createCustomTopic(
    topic: String,
    topicConfig: Map[String, String] = Map.empty,
    partitions: Int = 1,
    replicationFactor: Int = 1
  ): Try[Unit] = {
    val newTopic = new NewTopic(topic, partitions, replicationFactor.toShort)
      .configs(topicConfig.asJava)

    withAdminClient { adminClient =>
      adminClient
        .createTopics(Seq(newTopic).asJava)
        .all
        .get(topicCreationTimeout.length, topicCreationTimeout.unit)
    }.map(_ => ())
  }

  /**
    * Creates an `AdminClient`, then executes the body passed as a parameter.
    *
    * @param body   the function to execute
    * @param config an implicit [[EmbeddedKafkaConfig]]
    */
  protected def withAdminClient[T](
    body: AdminClient => T
  ): Try[T] = {
    val adminClient = AdminClient.create(
      Map[String, Object](
        AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG -> container.bootstrapServers, //s"localhost:${config.kafkaPort}",
        AdminClientConfig.CLIENT_ID_CONFIG -> "embedded-kafka-admin-client",
        AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG -> zkSessionTimeoutMs.toString,
        AdminClientConfig.CONNECTIONS_MAX_IDLE_MS_CONFIG -> zkConnectionTimeoutMs.toString
      ).asJava
    )

    val res = Try(body(adminClient))
    adminClient.close(java.time.Duration.ofMillis(adminClientCloseTimeout.toMillis))

    res
  }

  final def adminClientSettings(
    config: EmbeddedKafkaConfig
  ): AdminClientSettings[IO] =
    AdminClientSettings[IO]
      .withProperties(adminClientProperties(config))

  final def consumerSettings[F[_]](
    config: EmbeddedKafkaConfig
  )(implicit F: Sync[F]): ConsumerSettings[F, String, String] =
    ConsumerSettings[F, String, String]
      .withProperties(consumerProperties(config))
      .withRecordMetadata(_.timestamp.toString)

  final def producerSettings[F[_]](implicit F: Sync[F]): ProducerSettings[F, String, String] =
    ProducerSettings[F, String, String].withProperties(defaultProducerConf)

  final def adminClientProperties(config: EmbeddedKafkaConfig): Map[String, String] =
    Map(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG -> s"localhost:${config.kafkaPort}")

  final def consumerProperties(config: EmbeddedKafkaConfig): Map[String, String] =
    Map(
      ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG -> s"localhost:${config.kafkaPort}",
      ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> "earliest",
      ConsumerConfig.GROUP_ID_CONFIG -> "group"
    )

  final def producerProperties(config: EmbeddedKafkaConfig): Map[String, String] =
    Map(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG -> s"localhost:${config.kafkaPort}")

  // final def withKafka[A](props: Map[String, String], f: (EmbeddedKafkaConfig, String) => A): A =
  //   withRunningKafkaOnFoundPort(
  //     EmbeddedKafkaConfig(
  //       customBrokerProperties = Map(
  //         "transaction.state.log.replication.factor" -> "1",
  //         "transaction.abort.timed.out.transaction.cleanup.interval.ms" -> transactionTimeoutInterval.toMillis.toString
  //       ) ++ props
  //     )
  //   )(f(_, nextTopicName()))

  final def withKafka[A](f: String => A): A =
    f(nextTopicName())

  final def withKafkaConsumer(
    nativeSettings: Map[String, AnyRef]
  ): WithKafkaConsumer =
    new WithKafkaConsumer(nativeSettings)

  final class WithKafkaConsumer(
    nativeSettings: Map[String, AnyRef]
  ) {
    def apply[A](f: KConsumer[Array[Byte], Array[Byte]] => A): A = {
      val consumer: KConsumer[Array[Byte], Array[Byte]] =
        new KConsumer[Array[Byte], Array[Byte]](
          nativeSettings.asJava,
          new ByteArrayDeserializer,
          new ByteArrayDeserializer
        )

      try f(consumer)
      finally consumer.close()
    }
  }

  private[this] def defaultConsumerConfig =
    Map[String, Object](
      ConsumerConfig.GROUP_ID_CONFIG -> "embedded-kafka-spec",
      ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG -> container.bootstrapServers,
      ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> OffsetResetStrategy.EARLIEST.toString.toLowerCase,
      ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG -> false.toString
    )

  private[this] def nextTopicName(): String =
    s"topic-${UUID.randomUUID()}"

  def consumeFirstKeyedMessageFrom[K, V](
    topic: String,
    autoCommit: Boolean = false,
    customProperties: Map[String, Object] = Map.empty
  )(
    implicit
    keyDeserializer: Deserializer[K],
    valueDeserializer: Deserializer[V]
  ): (K, V) =
    consumeNumberKeyedMessagesFrom[K, V](topic, 1, autoCommit, customProperties = customProperties)(
      keyDeserializer,
      valueDeserializer
    ).head

  def consumeNumberKeyedMessagesFrom[K, V](
    topic: String,
    number: Int,
    autoCommit: Boolean = false,
    customProperties: Map[String, Object] = Map.empty
  )(
    implicit
    keyDeserializer: Deserializer[K],
    valueDeserializer: Deserializer[V]
  ): List[(K, V)] =
    consumeNumberKeyedMessagesFromTopics(
      Set(topic),
      number,
      autoCommit,
      customProperties = customProperties
    )(
      keyDeserializer,
      valueDeserializer
    )(topic)

  def consumeNumberKeyedMessagesFromTopics[K, V](
    topics: Set[String],
    number: Int,
    autoCommit: Boolean = false,
    timeout: Duration = 5.seconds,
    resetTimeoutOnEachMessage: Boolean = true,
    customProperties: Map[String, Object] = Map.empty
  )(
    implicit
    keyDeserializer: Deserializer[K],
    valueDeserializer: Deserializer[V]
  ): Map[String, List[(K, V)]] = {
    val consumerProperties = defaultConsumerConfig ++ customProperties ++ Map[String, Object](
      ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG -> autoCommit.toString
    )

    var timeoutNanoTime = System.nanoTime + timeout.toNanos
    val consumer = new KafkaConsumer[K, V](
      consumerProperties.asJava,
      keyDeserializer,
      valueDeserializer
    )

    val messages = Try {
      val messagesBuffers = topics.map(_ -> ListBuffer.empty[(K, V)]).toMap
      var messagesRead = 0
      consumer.subscribe(topics.asJava)
      topics.foreach(consumer.partitionsFor)

      while (messagesRead < number && System.nanoTime < timeoutNanoTime) {
        val recordIter =
          consumer.poll(duration2JavaDuration(consumerPollingTimeout)).iterator
        if (resetTimeoutOnEachMessage && recordIter.hasNext) {
          timeoutNanoTime = System.nanoTime + timeout.toNanos
        }
        while (recordIter.hasNext && messagesRead < number) {
          val record = recordIter.next
          messagesBuffers(record.topic) += (record.key -> record.value)
          val tp = new TopicPartition(record.topic, record.partition)
          val om = new OffsetAndMetadata(record.offset + 1)
          consumer.commitSync(Map(tp -> om).asJava)
          messagesRead += 1
        }
      }
      if (messagesRead < number) {
        throw new TimeoutException(
          s"Unable to retrieve $number message(s) from Kafka in $timeout"
        )
      }
      messagesBuffers.view.mapValues(_.toList).toMap
    }

    consumer.close()
    messages.recover {
      case ex: KafkaException => throw new KafkaUnavailableException(ex)
    }.get
  }

  private def defaultProducerConf =
    Map[String, String](
      ProducerConfig.BOOTSTRAP_SERVERS_CONFIG -> container.bootstrapServers,
      ProducerConfig.MAX_BLOCK_MS_CONFIG -> 10000.toString,
      ProducerConfig.RETRY_BACKOFF_MS_CONFIG -> 1000.toString
    )
}
