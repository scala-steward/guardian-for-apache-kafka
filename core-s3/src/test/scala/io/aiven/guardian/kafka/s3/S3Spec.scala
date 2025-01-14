package io.aiven.guardian.kafka.s3

import com.softwaremill.diffx.ShowConfig
import com.typesafe.scalalogging.LazyLogging
import io.aiven.guardian.kafka.models.ReducedConsumerRecord
import io.aiven.guardian.pekko.PekkoHttpTestKit
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.pekko
import org.scalactic.Prettifier
import org.scalactic.SizeLimit
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.propspec.AnyPropSpecLike
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.language.postfixOps

import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue

import pekko.NotUsed
import pekko.actor.Scheduler
import pekko.stream.Attributes
import pekko.stream.connectors.s3.BucketAccess
import pekko.stream.connectors.s3.ListBucketResultContents
import pekko.stream.connectors.s3.S3Attributes
import pekko.stream.connectors.s3.S3Settings
import pekko.stream.connectors.s3.scaladsl.S3
import pekko.stream.scaladsl.Sink
import pekko.stream.scaladsl.Source
import pekko.testkit.TestKitBase

trait S3Spec
    extends TestKitBase
    with AnyPropSpecLike
    with PekkoHttpTestKit
    with ScalaCheckPropertyChecks
    with ScalaFutures
    with Config
    with LazyLogging {

  implicit val ec: ExecutionContext            = system.dispatcher
  implicit val defaultPatience: PatienceConfig = PatienceConfig(20 minutes, 100 millis)
  implicit val showConfig: ShowConfig          = ShowConfig.default.skipIdentical
  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 1)

  /** Due to the fact that we have to deal with massively generated collections when testing against S3, we override the
    * default prettifier with one that truncates so we don't generate ridiculously large logs
    */
  implicit val prettifier: Prettifier = Prettifier.truncateAt(SizeLimit(10))

  val s3Settings: S3Settings

  implicit lazy val s3Attrs: Attributes = S3Attributes.settings(s3Settings)

  /** Whether to use virtual dot host, Typically this is disabled when testing against real services because they
    * require domain name verification
    */
  val useVirtualDotHost: Boolean

  /** A prefix that will get added to each generated bucket in the test, this is to track the buckets that are
    * specifically created by the test
    */
  lazy val bucketPrefix: Option[String] = None

  private val bucketsToCleanup = new ConcurrentLinkedQueue[String]()

  /** Whether to enable cleanup of buckets after tests are run and if so the initial delay to wait after the test
    */
  lazy val enableCleanup: Option[FiniteDuration] = None

  /** The MaxTimeout when cleaning up all of the buckets during `afterAll`
    */
  lazy val maxCleanupTimeout: FiniteDuration = 10 minutes

  def createBucket(bucket: String): Future[Unit] =
    for {
      bucketResponse <- S3.checkIfBucketExists(bucket)
      _ <- bucketResponse match {
             case BucketAccess.AccessDenied =>
               throw new RuntimeException(
                 s"Unable to create bucket: $bucket since it already exists however permissions are inadequate"
               )
             case BucketAccess.AccessGranted =>
               logger.info(s"Deleting and recreating bucket: $bucket since it already exists with correct permissions")
               for {
                 _ <- S3TestUtils.cleanAndDeleteBucket(bucket)
                 _ <- S3.makeBucket(bucket)
               } yield ()
             case BucketAccess.NotExists =>
               S3.makeBucket(bucket)
           }
      _ = if (enableCleanup.isDefined)
            bucketsToCleanup.add(bucket)
    } yield ()

  private def cleanBucket(bucket: String): Future[Unit] = (for {
    check <- S3.checkIfBucketExists(bucket)
    _ <- check match {
           case BucketAccess.AccessDenied =>
             Future {
               logger.warn(
                 s"Cannot delete bucket: $bucket due to having access denied. Please look into this as it can fill up your AWS account"
               )
             }
           case BucketAccess.AccessGranted =>
             logger.info(s"Cleaning up bucket: $bucket")
             S3TestUtils.cleanAndDeleteBucket(bucket)
           case BucketAccess.NotExists =>
             Future {
               logger.info(s"Not deleting bucket: $bucket since it no longer exists")
             }
         }

  } yield ()).recover { case util.control.NonFatal(error) =>
    logger.error(s"Error deleting bucket: $bucket", error)
  }

  override def afterAll(): Unit =
    try
      enableCleanup match {
        case Some(initialDelay) =>
          Await.result(pekko.pattern.after(initialDelay)(
                         Future.sequence(bucketsToCleanup.asScala.toList.distinct.map(cleanBucket))
                       ),
                       maxCleanupTimeout
          )
        case None => ()
      }
    finally
      super.afterAll()

  /** @param dataBucket
    *   Which S3 bucket the objects are being persisted into
    * @param transformResult
    *   A function that transforms the download result from S3 into the data `T` that you need. Note that you can also
    *   throw an exception in this transform function to trigger a retry (i.e. using it as a an additional predicate)
    * @param attempts
    *   Total number of attempts
    * @param delay
    *   The delay between each attempt after the first
    * @tparam T
    *   Type of the final result transformed by `transformResult`
    * @return
    */
  def waitForS3Download[T](dataBucket: String,
                           transformResult: Seq[ListBucketResultContents] => T,
                           attempts: Int = 10,
                           delay: FiniteDuration = 1 second
  ): Future[T] = {
    implicit val scheduler: Scheduler = system.scheduler

    val attempt = () =>
      S3.listBucket(dataBucket, None).withAttributes(s3Attrs).runWith(Sink.seq).map {
        transformResult
      }

    pekko.pattern.retry(attempt, attempts, delay)
  }

  case class DownloadNotReady(downloads: Seq[ListBucketResultContents])
      extends Exception(s"Download not ready, current state is ${downloads.map(_.toString).mkString(",")}")

  def reducedConsumerRecordsToJson(reducedConsumerRecords: List[ReducedConsumerRecord]): Array[Byte] = {
    import io.aiven.guardian.kafka.codecs.Circe._
    import io.circe.syntax._
    reducedConsumerRecords.asJson.noSpaces.getBytes
  }

  /** Converts a list of `ProducerRecord` to a source that is streamed over a period of time
    * @param producerRecords
    *   The list of producer records
    * @param streamDuration
    *   The period over which the topics will be streamed
    * @return
    *   Source ready to be passed into a Kafka producer
    */
  def toSource(
      producerRecords: List[ProducerRecord[Array[Byte], Array[Byte]]],
      streamDuration: FiniteDuration
  ): Source[ProducerRecord[Array[Byte], Array[Byte]], NotUsed] = {
    val durationToMicros = streamDuration.toMillis
    val topicsPerMillis  = producerRecords.size / durationToMicros
    Source(producerRecords).throttle(topicsPerMillis.toInt max 1, 1 millis)
  }

  /** Converts a generated list of `ReducedConsumerRecord` to a list of `ProducerRecord`
    * @param data
    *   List of `ReducedConsumerRecord`'s generated by scalacheck
    * @return
    *   A list of `ProducerRecord`. Note that it only uses the `key`/`value` and ignores other values
    */
  def toProducerRecords(data: List[ReducedConsumerRecord]): List[ProducerRecord[Array[Byte], Array[Byte]]] = data.map {
    reducedConsumerRecord =>
      val valueAsByteArray = Base64.getDecoder.decode(reducedConsumerRecord.value)
      reducedConsumerRecord.key match {
        case Some(key) =>
          new ProducerRecord[Array[Byte], Array[Byte]](reducedConsumerRecord.topic,
                                                       Base64.getDecoder.decode(key),
                                                       valueAsByteArray
          )
        case None =>
          new ProducerRecord[Array[Byte], Array[Byte]](reducedConsumerRecord.topic, valueAsByteArray)
      }
  }
}
