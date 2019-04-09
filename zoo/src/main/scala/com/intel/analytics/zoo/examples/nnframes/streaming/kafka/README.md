## Image Inference with Kafka Streaming example - with Dataframes
There are two example illustrations
* how to ingest images through a kafka connector and pass it through an image inferencing pipeline. This is a RDD microbatch based implementation
* how to ingest images through a kafka connector using structured streaming and pass it through image inferencing pipeline.

## Download Analytics Zoo
You will need to use the analytics zoo version from the branch feature/streaming and build locally (mvn clean install -DskipTests=true)
## Run the example
### Download the pre-trained model
You can download pre-trained models from [Image Classification](https://github.com/intel-analytics/analytics-zoo/blob/master/docs/docs/ProgrammingGuide/image-classification.md)

### Setup Zookeeper - Kafka servers
Apart from setting up spark, please setup zookeeper and kafka servers as per instructions in [Apache Kafka Quick Start](https://kafka.apache.org/quickstart) 

### Prepare predict dataset
Put your image data for prediction in one folder.

### Micro batch RDDs

#### Run this to start kafka image producer
```shell
IMAGEDIR=... // the folder in which images are stored
BROKERLIST=... // BROKERIP:PORT
JARDIR=... // location of the built JAR
CLIENTID=... // Kafka producer client id
TOPIC=... // Kafka topic
DELAY=... // delay between consecutive images sent
NUMPARTITIONS=1 // The number of topic partitions is only dummy and does not really set the topic partitions. Please use the kafka quick start to understand how topics can be split into partitions 

java -cp ${JARDIR}/analytics-zoo-bigdl_0.7.2-spark_2.3.1-0.4.0-SNAPSHOT-jar-with-dependencies-and-spark.jar com.intel.analytics.zoo.examples.nnframes.streaming.kafka.Producers.ImageProducer --brokers ${BROKERLIST} --clientId ${CLIENTID} --imageFolder ${IMAGEDIR} --topic ${TOPIC} --txDelay ${DELAY} --numPartitions ${NUMPARTITIONS}
```

#### Run this example to start kafka micro batch RDD connector
Run the following command for Spark local mode (MASTER=local[*]) or cluster mode:
```bash
export PATH=$PATH:${SPARK_HOME}/bin 

LOGDIR=... // Directory where the log4j.properties files is maintained (sample log properties file in zoo/src/resources/)
SPARK_HOME=... // the root directory of spark
MASTER=... // local[*] or spark://host-ip:port
JARDIR=... // location of the built JAR
STREAMING_PROP=... // absolute path of the streaming properties files (sample properties file in zoo/src/resources/). Please change the paths and parameters in the streaming properties files before execution

spark-submit \
--master ${MASTER} \
--driver-memory 8g \
--executor-memory 8g \
--verbose \
--conf spark.executor.cores=4 \
--conf spark.driver.maxResultSize=10G \
--conf spark.shuffle.memoryFraction=0 \
--conf spark.network.timeout=10000000 \
--total-executor-cores 4 \
--driver-java-options "-Dlog4j.configuration=file:$LOGDIR/log4j.properties" \
--conf "spark.executor.extraJavaOptions=-Dlog4j.configuration=file:$LOGDIR/log4j.properties" \
--class com.intel.analytics.zoo.examples.nnframes.streaming.kafka.Consumers.ImageConsumeAndInference ${JARDIR}/analytics-zoo-bigdl_0.7.2-spark_2.3.1-0.4.0-SNAPSHOT-jar-with-dependencies-and-spark.jar --propFile ${STREAMING_PROP}
```
### Structured Streaming

#### Run this to start kafka image producer
```shell
IMAGEDIR=... // the folder in which images are stored
BROKERLIST=... // BROKERIP:PORT
JARDIR=... // location of the built JAR
CLIENTID=... // Kafka producer client id
TOPIC=... // Kafka topic
DELAY=... // delay between consecutive images sent
NUMPARTITIONS=1 // The number of topic partitions is only dummy and does not really set the topic partitions. Please use the kafka quick start to understand how topics can be split into partitions 

java -cp ${JARDIR}/analytics-zoo-bigdl_0.7.2-spark_2.3.1-0.4.0-SNAPSHOT-jar-with-dependencies-and-spark.jar com.intel.analytics.zoo.examples.nnframes.streaming.kafka.Producers.ImageStructuredProducer --brokers ${BROKERLIST} --clientId ${CLIENTID} --imageFolder ${IMAGEDIR} --topic ${TOPIC} --txDelay ${DELAY} --numPartitions ${NUMPARTITIONS}
```

#### Run this example to start kafka structured streaming consumer
Run the following command for Spark local mode (MASTER=local[*]) or cluster mode:
```bash
export PATH=$PATH:${SPARK_HOME}/bin 

LOGDIR=... // Directory where the log4j.properties files is maintained (sample log properties file in zoo/src/resources/)
SPARK_HOME=... // the root directory of spark
MASTER=... // local[*] or spark://host-ip:port
JARDIR=... // location of the built JAR
STREAMING_PROP=... // absolute path of the streaming properties files (sample properties file in zoo/src/resources/). Please change the paths and parameters in the streaming properties files before execution

spark-submit \
--master ${MASTER} \
--driver-memory 8g \
--executor-memory 8g \
--verbose \
--conf spark.executor.cores=4 \
--conf spark.driver.maxResultSize=10G \
--conf spark.shuffle.memoryFraction=0 \
--conf spark.network.timeout=10000000 \
--total-executor-cores 4 \
--driver-java-options "-Dlog4j.configuration=file:$LOGDIR/log4j.properties" \
--conf "spark.executor.extraJavaOptions=-Dlog4j.configuration=file:$LOGDIR/log4j.properties" \
--class com.intel.analytics.zoo.examples.nnframes.streaming.kafka.Consumers.ImageStructuredConsumer ${JARDIR}/analytics-zoo-bigdl_0.7.2-spark_2.3.1-0.4.0-SNAPSHOT-jar-with-dependencies-and-spark.jar --propFile ${STREAMING_PROP}
```
#### Current Issues faced with the structured streaming implementation
##### This example runs in local mode. However, this example gives following error on the executor side when run in cluster mode:
*[Executor task launch worker for task 3] ERROR meghlogger - java.lang.IllegalArgumentException: requirement failed: Engine.init: Core number is not initialized. Do you call Engine.init? See more at https://bigdl-project.github.io/master/#APIGuide/Engine/*
