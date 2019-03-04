package com.intel.analytics.zoo.examples.imageclassification.streaming

import com.intel.analytics.bigdl.Module
import com.intel.analytics.bigdl.nn.Module
import com.intel.analytics.zoo.common.NNContext
import com.intel.analytics.zoo.feature.image._
import com.intel.analytics.zoo.pipeline.inference.InferenceModel
import com.intel.analytics.bigdl.tensor.TensorNumericMath.TensorNumeric.NumericFloat
import com.intel.analytics.bigdl.transform.vision.image.ImageFeature
import com.intel.analytics.zoo.feature.common.Preprocessing
import org.apache.spark.SparkContext

import com.intel.analytics.bigdl.utils.LoggerFilter
import com.intel.analytics.zoo.pipeline.nnframes._
import com.intel.analytics.zoo.feature.image.ImageSet
import com.intel.analytics.bigdl.transform.vision.image.ImageFeature
import com.intel.analytics.zoo.models.image.imageclassification.{ImageClassifier, LabelOutput}

import org.apache.spark.sql.Row
import org.apache.spark.sql.functions._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, Row, SQLContext}

import org.apache.spark.streaming.StreamingContext
import org.apache.spark.streaming.Duration
import org.apache.spark.streaming.dstream.SocketInputDStream

import org.apache.spark.storage.StorageLevel
import org.apache.spark.api.java.function.Function
import org.apache.spark.util.NextIterator
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext

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

import scopt.OptionParser


import scala.tools.jline_embedded.internal.InputStreamReader
import scala.reflect.io.Streamable.Bytes

import org.apache.log4j.{Level, Logger}

import org.opencv.core.{CvType, Mat}
import org.opencv.imgcodecs.Imgcodecs

class StreamTunedImgClassify(module: String = "",
                     host: String = "",
                     port: Int = 9990,
                     nPartition: Int = 1,
                     batchSize: Int = 4,
                     batchDuration: Int = 500,
                     mode: String = "local"
) extends Serializable { 
  
  @transient lazy val logger = Logger.getLogger("meghlogger")
  @transient var sc: SparkContext = _

  def bytesToImageObjects(is: InputStream) : Iterator[ImageFeature] = {
      val dis = new DataInputStream(is)  

      class ImageIterator extends Iterator[ImageFeature] with Serializable {
        private var gotNext = false
        private var nextValue: ImageFeature = _
        protected var finished = false
        val sw = new StringWriter
        
         def getNext():Unit = {
             //logger.info("Start get next");
             try {
               //logger.info("Start reading record");
               
               val nameSize = new Array[Byte](4);
    	         dis.readFully(nameSize, 0, 4);
               val strLen = ByteBuffer.wrap(nameSize).order(ByteOrder.BIG_ENDIAN).getInt();
    	         //logger.info("Image name length: " + strLen);
    
    	         if (strLen <= 0 || strLen > 28) {
    	             logger.info("Image file name size is null or invalid");
    	             finished = true;
    	             dis.close();
    	             return
    	         }
            
    	         val name = new Array[Byte](strLen);
    	         dis.readFully(name, 0, strLen);
               val imageName = new String(name)
               if (imageName == null || imageName.isEmpty()) {
    	             logger.info("Image filename is null or empty");
    	             finished = true;
    	             dis.close();
    	             return
    	         }
    						
               //logger.info("Image filename: " + imageName);                
               
    						
               val imgLen = new Array[Byte](4);
    	         dis.readFully(imgLen, 0, 4);
               val len = ByteBuffer.wrap(imgLen).order(ByteOrder.BIG_ENDIAN).getInt();
    	         //logger.info("Image size: " + len);
    
    	         if (len <= 0) {
    	             finished = true;
    	             dis.close();
    	             return
    	         }
    
    	         val data = new Array[Byte](len);
    	         dis.readFully(data, 0, len);
    	         
    	         try{
    	             
    	             nextValue = ImageFeature(data, uri = imageName)    
                   if (nextValue.bytes() == null) {
                       logger.info("Next value empty!!");
        	             finished = true;
        	             dis.close();
        	             return
        	         }
    	         }
    	         catch {               
                   case e: Exception => e.printStackTrace(new PrintWriter(sw))
                   finished = true;
                   dis.close();
                   logger.error(sw.toString())
               }
               
               //logger.info("Next value fine");
             }
             catch {
               
               case e: Exception => e.printStackTrace(new PrintWriter(sw))
               finished = true;
               logger.error(sw.toString())
             }
          //ogger.info("End get next");
          gotNext = true
        }  
      
        override def hasNext: Boolean = {
          //logger.info("Start hasNext");
          if (!finished) {
            if (!gotNext) {
              getNext()
              if (finished) {
                finished = true
                dis.close()
              }
            }
          }
          //logger.info("End hasNext");
          !finished
        }
      
        override def next(): ImageFeature = {
          //logger.info("Start next");
          if (finished) {
            throw new NoSuchElementException("End of stream")            
          }
          if(!gotNext)
            getNext()
          gotNext = false
          //logger.info("End next");
          nextValue
        }        
      }
      
      new ImageIterator
  }
   
  def doClassify(rdd : RDD[ImageFeature],
                 model: Module[Float],
                 transformer: Preprocessing[ImageFeature, ImageFeature]) : Unit =  {
      logger.info(s"Start classification")
      val count = rdd.count()
      logger.info("RDD Count:" + count)
      if(count > 0)
      {
        logger.info(s"Non-Empty RDD start processing")
        
        val st = System.nanoTime()
        if (mode == "local") {
          localInference(rdd, model, transformer)      
        } else {    
          distributedInference(rdd, model, transformer, sc) 
        }       
        val inferTime = (System.nanoTime() - st) / 1e9
        logger.info("inference finished in " + inferTime)
        logger.info("throughput: " + rdd.count() / inferTime)
      }
  }
  

  def stream() = {
    logger.setLevel(Level.ALL)
    sc = NNContext.initNNContext("ImageInference")
      
    val ssc = new StreamingContext(sc, new Duration(batchDuration))
    //ssc.remember(new Duration(60000));
    
    logger.info(s"Load model and start socket stream")    
        
    var imageDStream = ssc.socketStream(host, port, bytesToImageObjects, StorageLevel.MEMORY_AND_DISK_SER)
    
    val transformer = ImageResize(256, 256) -> ImageCenterCrop(224, 224) ->
      ImageChannelNormalize(123, 117, 104) -> ImageMatToTensor() -> ImageSetToSample()
      
    val dlmodel = Module.loadModule(module).evaluate()
    
    imageDStream.foreachRDD(rdd => doClassify(rdd, dlmodel, transformer))
    
    ssc.start()  
    ssc.awaitTermination();
  }
  
  /**
   * read images from local file system and run inference locally without sparkcontext
   * master = local[x]
   * only support local file system
   */
  def localInference(
      rdd : RDD[ImageFeature],
      model: Module[Float],
      transformer: Preprocessing[ImageFeature, ImageFeature]): Unit = {
    
    val data = ImageSet.array(rdd.collect())
    val images = ImageSet.streamread(data)
    val features = images.transform(transformer)
    val result = model.predictImage(features.toImageFrame(), batchPerPartition = 8)
    val output = result.toLocal().array.head.predict()
    
  }

  /**
   * run inference in cluster mode, with spark overhead.
   * use master = local[x] or yarn
   * support HDFS path
   */
  def distributedInference(
      rdd : RDD[ImageFeature],
      model: Module[Float],
      transformer: Preprocessing[ImageFeature, ImageFeature],
      sc: SparkContext): Unit = {
    
    val data = ImageSet.rdd(rdd)
    val images = ImageSet.streamread(data)
    println("#number of partitions: " + images.toDistributed().rdd.partitions.length)
    val features = images.transform(transformer)
    val result = model.predictImage(features.toImageFrame(), batchPerPartition = 8)
    val output = result.toDistributed().rdd.collect().head.predict()
    
  }

}  

object StreamTunedImgClassify{
  Logger.getLogger("org").setLevel(Level.ERROR)
  Logger.getLogger("akka").setLevel(Level.ERROR)
  Logger.getLogger("breeze").setLevel(Level.ERROR)
  Logger.getLogger("com.intel.analytics.zoo").setLevel(Level.INFO)

  val logger = Logger.getLogger(getClass)

  case class TopNClassificationParam(model: String = "",
                                     host: String = "",
                                     port: Int = 9990,
                                     nPartition: Int = 1,
                                     batchSize: Int = 4,
                                     batchDuration: Int = 500,                                     
                                     mode: String = "local")

  val parser = new OptionParser[TopNClassificationParam]("ImageClassification demo") {
    head("Analytics Zoo ImageClassification demo")
   
    opt[String]("host")
      .text("host ip to connect to")
      .action((x, c) => c.copy(host = x))
      .required()      
    opt[Int]("port")
      .text("port to connect to")
      .action((x, c) => c.copy(port = x))
      .required()
    opt[String]("model")
      .text("Analytics Zoo model")
      .action((x, c) => c.copy(model = x))
      .required()
    opt[Int]('p', "partition")
      .text("number of partitions")
      .action((x, c) => c.copy(nPartition = x))
      .required()
    opt[Int]('b', "batchSize")
      .text("batch size")
      .action((x, c) => c.copy(batchSize = x))
      .required()
    opt[Int]('b', "batchDuration")
      .text("batch duration")
      .action((x, c) => c.copy(batchDuration = x))
      .required()
    opt[String]("mode")
      .text("cluster mode")
      .action((x, c) => c.copy(mode = x))
      .required()
  }   

  def main(args: Array[String]): Unit = {
      parser.parse(args, TopNClassificationParam()).foreach { params =>
      require(Seq("local", "distributed").contains(params.mode))
      
      var sparkDriver = new StreamTunedImgClassify(params.model,
  				        params.host,
  				        params.port,
  				        params.nPartition,
  				        params.batchSize,
  				        params.batchDuration,
  				        params.mode) 
      sparkDriver.stream()
    }
  }
}