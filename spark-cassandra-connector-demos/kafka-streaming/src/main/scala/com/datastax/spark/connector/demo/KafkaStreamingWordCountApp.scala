package com.datastax.spark.connector.demo

import com.datastax.spark.connector.util.Logging

import scala.sys.process._
import scala.util.Try
import kafka.serializer.StringDecoder
import org.apache.spark.{SparkContext, SparkConf}
import org.apache.spark.storage.StorageLevel
import org.apache.spark.SparkContext._
import org.apache.spark.streaming._
import org.apache.spark.streaming.StreamingContext._
import org.apache.spark.streaming.kafka._
import com.datastax.spark.connector.cql.CassandraConnector
import com.datastax.spark.connector.embedded._
import com.datastax.spark.connector._
import com.datastax.spark.connector.streaming._


/**
 * Simple Kafka Spark Streaming demo which
 * 1. Starts an embedded ZooKeeper server
 * 2. Starts an embedded Kafka server
 * 3. Creates a new topic in the Kafka broker
 * 4. Generates messages and publishes to the Kafka broker
 * 5. Creates a Spark Streaming Kafka input stream which
 *    pulls messages from a Kafka Broker,
 *    runs basic Spark computations on the streaming data,
 *    and writes results to Cassandra
 * 6. Asserts expectations are met
 * 7. Shuts down Spark, Kafka and ZooKeeper
 */
object KafkaStreamingWordCountApp extends App with Logging with Assertions {

  val words = "./spark-cassandra-connector-demos/kafka-streaming/src/main/resources/data/words"

  val topic = "demo.wordcount.topic"

  /** Starts the Kafka broker. */
  lazy val kafka = new EmbeddedKafka()

  val conf = new SparkConf(true)
    .setMaster("local[10]")
    .setAppName(getClass.getSimpleName)
    .set("spark.executor.memory", "1g")
    .set("spark.cores.max", "1")
    .set("spark.cassandra.connection.host", "127.0.0.1")

  /** Creates the keyspace and table in Cassandra. */
  CassandraConnector(conf).withSessionDo { session =>
    session.execute(s"DROP KEYSPACE IF EXISTS demo")
    session.execute(s"CREATE KEYSPACE IF NOT EXISTS demo WITH REPLICATION = {'class': 'SimpleStrategy', 'replication_factor': 1 }")
    session.execute(s"CREATE TABLE IF NOT EXISTS demo.wordcount (word TEXT PRIMARY KEY, count COUNTER)")
    session.execute(s"TRUNCATE demo.wordcount")
  }

  kafka.createTopic(topic)

  val producer = new KafkaProducer[String,String](kafka.kafkaConfig)

  val toKafka = (line: String) => producer.send(topic, "demo.wordcount.group", line.toLowerCase)

  val sc = new SparkContext(conf)

  /* The write to kafka from spark, read from kafka in the stream and write to cassandra would happen
  from separate components in a production env. This is a simple demo to show the code for integration.
   */
  sc.textFile(words)
    .flatMap(_.split("\\s+"))
    .toLocalIterator.foreach(toKafka)

  producer.close()

  /** Creates the Spark Streaming context. */
  val ssc =  new StreamingContext(sc, Seconds(1))

  /** Creates an input stream that pulls messages from a Kafka Broker. */
  val stream = KafkaUtils.createStream[String, String, StringDecoder, StringDecoder](
    ssc, kafka.kafkaParams, Map(topic -> 1), StorageLevel.MEMORY_ONLY)

  stream.map(_._2).countByValue().saveToCassandra("demo", "wordcount")

  ssc.start()
  ssc.awaitTermination()
  //validate()
  //shutdown()

  def shutdown(): Unit = {
    log.info("Shutting down.")
    ssc.stop(stopSparkContext = true, stopGracefully = false)
    kafka.shutdown()
  }

  def validate(): Unit = {
    val rdd = ssc.cassandraTable("demo", "wordcount")
    import scala.concurrent.duration._
    awaitCond(rdd.toLocalIterator.size > 100, 5.seconds)
    log.info("Assertions successful.")
  }
}
