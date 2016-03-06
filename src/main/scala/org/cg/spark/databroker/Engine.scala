package org.cg.spark.databroker

import org.apache.spark.Logging
import spark.jobserver.SparkJob
import org.cg.monadic.transformer.TransformationPipelineContext
import org.apache.spark.SparkContext
import com.typesafe.config.Config
import spark.jobserver.SparkJobValidation
import spark.jobserver.SparkJobInvalid
import spark.jobserver.SparkJobValid
import org.apache.hadoop.fs.FsUrlStreamHandlerFactory
import java.net.URL
import scala.util.Try
import org.apache.spark.SparkConf
import com.typesafe.config.{ Config, ConfigFactory }
import spark.jobserver.SparkStreamingJob
import org.apache.spark.streaming.StreamingContext
import org.apache.spark.streaming.Seconds
import org.apache.spark.streaming.kafka.KafkaUtils
import kafka.serializer.StringDecoder
import scala.reflect.api.Universe
import scala.reflect.runtime.universe
import scala.reflect.ManifestFactory
import kafka.serializer.Decoder
import scala.reflect.ClassTag

/**
 * @author Yanlin Wang (wangyanlin@gmail.com)
 *
 */

class Engine[EVENT <: Any: ClassTag, DECODER <: StreamingCoder[EVENT]: ClassTag] extends Logging with SparkStreamingJob {

  //configuration nodes
  final val BROKER_CONFIG = "broker"
  final val HADOOP_CONFIG = "hadoop-config"

  //Broker configuration
  final val CFG_BROKER = "bootstrap.servers"
  final val CFG_TOPICS = "topics"
  final val CFG_BATCH_INTERVAL = "batch.interval"
  final val CFG_PIPELINE_CLZ = "pipeline.class"

  val configHelper: TransformationPipelineContext = new TransformationPipelineContext

  def loadConfig(node: String): Config = configHelper.loadConfig(node)

  override def validate(sc: StreamingContext, config: Config): SparkJobValidation = {
    Try(config.getString("input.string"))
      .map(x => SparkJobValid)
      .getOrElse(SparkJobInvalid("No input.string config param"))
  }

  //job server entry
  override def runJob(ssc: StreamingContext, config: Config): Any = {
    val args: Array[String] = config.getString("input.string").split(" ").toArray
    internalRun(args, ssc, config)
  }

  // internalRun
  def internalRun(args: Array[String], ssc: StreamingContext, config: Config): Any = {
    // get params
    import scala.collection.JavaConverters._
    val brokers = config.getString(CFG_BROKER)
    val topicsSet = config.getStringList(CFG_TOPICS).asScala.toSet
    val pipelineClzName = config.getString(CFG_PIPELINE_CLZ)
    val clz = Class.forName(pipelineClzName)
    val pipeLine = clz.newInstance()

    // Create direct kafka stream with brokers and topics
    val kafkaParams = Map[String, String]("metadata.broker.list" -> brokers)
    val messages = KafkaUtils.createDirectStream[String, EVENT, StringDecoder, DECODER](
      ssc, kafkaParams, topicsSet)
    pipeLine match {
      case p: StreamingPipeline[String, EVENT] => p.handle(ssc, messages, config)
      case _                                   => throw new ClassCastException
    }
  }

  //main entry
  def main(args: Array[String]) {
    val className = this.getClass.getName
    //process cmd params
    if (args.length < 1) {
      System.err.println(s"""
        |Usage: $className <configurationUrl> 
        |  <configurationUrl> the URL path of the config file 
        |  <disableCheckPoint> optional disable the check point
        |  
        """.stripMargin)
      System.exit(1)
    }
    val configURLString = args(0)

    //load config
    URL.setURLStreamHandlerFactory(new FsUrlStreamHandlerFactory());
    val configURL = new URL(configURLString)
    configHelper.initIfUndefined(configURL)
    val config = configHelper.config
    val streamCfg = loadConfig(BROKER_CONFIG)
    val hadoopCfg = Try[Config](loadConfig(HADOOP_CONFIG))
      .getOrElse(ConfigFactory.empty())
    // get params
    import scala.collection.JavaConverters._
    val batchInterval = Option(streamCfg.getLong(CFG_BATCH_INTERVAL)).getOrElse(5L)

    val conf = new SparkConf().setAppName("DataBrokerEngine")
    val sc = new SparkContext(conf)
    hadoopCfg.entrySet().iterator().asScala.foreach(e => sc.hadoopConfiguration.setStrings(e.getKey, e.getValue.render()));
    val ssc = new StreamingContext(sc, Seconds(batchInterval))

    internalRun(args, ssc, streamCfg)

    ssc.start()
    ssc.awaitTermination()
  }

}