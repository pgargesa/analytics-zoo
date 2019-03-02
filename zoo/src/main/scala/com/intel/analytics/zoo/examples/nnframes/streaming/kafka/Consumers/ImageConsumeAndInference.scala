package com.intel.analytics.zoo.examples.nnframes.streaming.kafka.Consumers

import com.intel.analytics.bigdl.nn.Module
import com.intel.analytics.bigdl.tensor.TensorNumericMath.TensorNumeric.NumericFloat
import com.intel.analytics.bigdl.utils.LoggerFilter
import com.intel.analytics.zoo.pipeline.nnframes._
import com.intel.analytics.zoo.common.NNContext
import com.intel.analytics.zoo.feature.image._
import com.intel.analytics.zoo.feature.image.ImageSet
import com.intel.analytics.bigdl.transform.vision.image.ImageFeature
import com.intel.analytics.zoo.models.image.imageclassification.{ImageClassifier, LabelOutput}
import com.intel.analytics.zoo.examples.nnframes.streaming.kafka.Deserializers._

import org.apache.spark.streaming.dstream.InputDStream
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.spark.streaming.kafka010.ConsumerStrategies.Subscribe
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.spark.streaming.kafka010.ConsumerStrategies.Subscribe
import org.apache.spark.streaming.kafka010.LocationStrategies.PreferConsistent
import org.apache.spark.streaming.kafka010.{CanCommitOffsets, HasOffsetRanges, KafkaUtils}
import org.apache.spark.streaming.{Seconds, StreamingContext}

import org.apache.spark.sql.Row
import org.apache.spark.sql.functions._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, Row, SQLContext}

import org.apache.spark.streaming.StreamingContext
import org.apache.spark.streaming.Duration
import org.apache.spark.streaming.dstream.SocketInputDStream
//import org.apache.spark.streaming

import org.apache.spark.storage.StorageLevel
import org.apache.spark.api.java.function.Function
import org.apache.spark.util.NextIterator
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext
import org.apache.spark.SparkConf

import org.apache.commons.io.FileUtils

import java.io.InputStream
import java.io.File
import java.io.DataInputStream
import java.io.ByteArrayInputStream
import java.io.BufferedInputStream
import java.io.PrintWriter
import java.io.StringWriter

import java.nio.ByteBuffer
import java.nio.ByteOrder

import java.awt.image.BufferedImage

import java.util.UUID

import scopt.OptionParser

import scala.tools.jline_embedded.internal.InputStreamReader
import scala.reflect.io.Streamable.Bytes

import org.apache.log4j.{Level, Logger}

import org.opencv.core.{CvType, Mat}
import org.opencv.imgcodecs.Imgcodecs
import org.apache.spark.SparkConf
import java.security.Key
import scala.util.Properties
import java.util.Properties
import java.io.FileInputStream
import shapeless.ops.nat.ToInt
import scala.collection.mutable.ListBuffer
import org.apache.spark.streaming.dstream.DStream

import com.intel.analytics.zoo.feature.common.Preprocessing
import org.apache.spark.sql.Row,com.intel.analytics.bigdl.tensor.Tensor

import scala.collection.convert._
import scala.collection.JavaConversions._
import scala.collection.JavaConverters._


class ImageConsumeAndInference(prop: Properties) extends Serializable {
  @transient lazy val logger = Logger.getLogger("meghlogger")
  @transient var sc: SparkContext = _
  
  def doClassify(rdd : RDD[ImageFeature],
                  model: NNClassifierModel[Float],
                  transformer: Preprocessing[Row,Tensor[Float]]) : Unit =  {
    logger.info(s"Start classification")
    val notNullRDDs = rdd.filter(f => (f != null && f.bytes() != null ))   
    val count = notNullRDDs.count()
    logger.info(s"Start classification")
    if (count > 0) {
      logger.info(s"Non-Empty RDD start processing")
      //val data = ImageSet.rdd(rdd.coalesce(nPartition, true))

      val st = System.nanoTime()
      
      if (prop.getProperty("inference.mode") == "local") {
        localInference(notNullRDDs, model, transformer)      
      } else {    
        distributedInference(notNullRDDs, model, transformer, sc) 
      }       
        
      val inferTime = (System.nanoTime() - st) / 1e9
      logger.info("inference finished in " + inferTime)
      logger.info("throughput: " + count / inferTime)
      //resultDF.select("imageName", "prediction").orderBy("imageName").show(10, false)
    }    
  }
  
  private val imageColumnSchema =
    StructType(StructField("image", NNImageSchema.byteSchema, true) :: Nil)
    
  private val TOPIC = Array(prop.getProperty("kafka.topic"))  
  
  /**
   * read images from local file system and run inference locally without sparkcontext
   * master = local[x]
   * only support local file system
   */
  def localInference(
      rdd : RDD[ImageFeature],
      model: NNClassifierModel[Float],
      transformer: Preprocessing[Row,Tensor[Float]]): Unit = {
    
    val getImageName = udf { row: Row => row.getString(0) }
    val data = ImageSet.array(rdd.collect())
    val mappedData = ImageSet.streamread(data, minPartitions = prop.getProperty("rdd.partition").toInt,
      resizeH = 256, resizeW = 256, imageCodec = 1)
    
    val rowData = mappedData.toLocal().array.map { imf => Row(NNImageSchema.imf2Row(imf)) }.toList   
    val imageDF = SQLContext.getOrCreate(sc).createDataFrame(rowData.asJava, imageColumnSchema)
                .withColumn("imageName", getImageName(col("image")))
    //imageDF.cache().collect()
                
    val resultDF = model.transform(imageDF)
    resultDF.collect()
    //resultDF.select("imageName", "prediction").orderBy("imageName").show(10, false)
  }

  /**
   * run inference in cluster mode, with spark overhead.
   * use master = local[x] or yarn
   * support HDFS path
   */
  def distributedInference(
      rdd : RDD[ImageFeature],
      model: NNClassifierModel[Float],
      transformer: Preprocessing[Row,Tensor[Float]],
      sc: SparkContext): Unit = {
    
    
    val getImageName = udf { row: Row => row.getString(0)}
    val data = ImageSet.rdd(rdd)
    val mappedData = ImageSet.streamread(data, minPartitions = prop.getProperty("rdd.partition").toInt,
                      resizeH = 256, resizeW = 256, imageCodec = 1)
    val rowRDD = mappedData.toDistributed().rdd.map { imf => Row(NNImageSchema.imf2Row(imf))}
    val imageDF = SQLContext.getOrCreate(sc).createDataFrame(rowRDD, imageColumnSchema)
                .repartition(prop.getProperty("rdd.partition").toInt)
                .withColumn("imageName", getImageName(col("image")))
    //imageDF.cache().collect()
    
    val resultDF = model.transform(imageDF)
    resultDF.collect()    
    //resultDF.select("imageName", "prediction").orderBy("imageName").show(10, false)
  }

  def stream() = {
    
  
    val conf = new SparkConf().set("spark.streaming.receiver.maxRate", prop.getProperty("spark.streaming.receiver.maxRate"))
                            .set("spark.streaming.kafka.maxRatePerPartition", prop.getProperty("spark.streaming.kafka.maxRatePerPartition"))
                            .set("spark.shuffle.reduceLocality.enabled", prop.getProperty("spark.shuffle.reduceLocality.enabled"))
                            .set("spark.shuffle.blockTransferService", prop.getProperty("spark.shuffle.blockTransferService"))
                            .set("spark.scheduler.minRegisteredResourcesRatio", prop.getProperty("spark.scheduler.minRegisteredResourcesRatio"))
                            .set("spark.speculation", prop.getProperty("spark.speculation"))
                            .setAppName(prop.getProperty("spark.app.name"))
                
    val kafkaConf = Map[String, Object](
      ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG -> prop.getProperty("bootstrap.servers"),
      ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG -> classOf[StringDeserializer],
      ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG -> classOf[CustomObjectDeserializer],
      ConsumerConfig.MAX_POLL_RECORDS_CONFIG -> prop.getProperty("max.poll.records"),
      ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG -> prop.getProperty("enable.auto.commit.config"),
      ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> prop.getProperty("auto.offset.reset"),
      ConsumerConfig.GROUP_ID_CONFIG ->  prop.getProperty("group.id")
    )
  
    sc = NNContext.initNNContext(conf) 
        
    val ssc = new StreamingContext(sc, new Duration(prop.getProperty("streaming.batch.duration").toInt))
        
    
    val streams = KafkaUtils.createDirectStream[String, ImageFeature](
                    ssc
                    , PreferConsistent
                    , Subscribe[String, ImageFeature](TOPIC, kafkaConf)
                  )   
    
    val microbatch = streams.map((stream: ConsumerRecord[String, ImageFeature]) => stream.value()) 
    
    val transformer = RowToImageFeature() -> ImageCenterCrop(224, 224) ->
        ImageChannelNormalize(123, 117, 104) -> ImageMatToTensor() -> ImageFeatureToTensor()

    val model = Module.loadModule[Float](prop.getProperty("model.full.path"))
    val dlmodel = NNClassifierModel(model, transformer)
      .setBatchSize(prop.getProperty("inference.batchsize").toInt)
      .setFeaturesCol("image")
      .setPredictionCol("prediction")
    
    microbatch.foreachRDD(rdd => doClassify(rdd, dlmodel, transformer)) 
    
    ssc.start()  
    ssc.awaitTermination();
   }
}

object ImageConsumeAndInference{
  //Logger.getLogger("org").setLevel(Level.ERROR)
  //Logger.getLogger("akka").setLevel(Level.ERROR)
  //Logger.getLogger("breeze").setLevel(Level.ERROR)
  //Logger.getLogger("com.intel.analytics.zoo").setLevel(Level.INFO)
  val logger = Logger.getLogger(getClass)
  logger.setLevel(Level.ALL)

  case class TopNClassificationParam(propFile: String= "")

  val parser = new OptionParser[TopNClassificationParam]("ImageClassification demo") {
    head("Analytics Zoo ImageClassification demo")
   
    opt[String]("propFile")
      .text("properties files")
      .action((x, c) => c.copy(propFile = x))
      .required() 
  }   

  def main(args: Array[String]): Unit = {
    parser.parse(args, TopNClassificationParam()).foreach { params =>
      val prop = new Properties()
      
      try {        
        prop.load(new FileInputStream(params.propFile))
      } catch { case e: Exception => 
        e.printStackTrace()
        sys.exit(1)
      }
        
      val sparkDriver = new ImageConsumeAndInference(prop) 
      sparkDriver.stream()
    }
  }
}
