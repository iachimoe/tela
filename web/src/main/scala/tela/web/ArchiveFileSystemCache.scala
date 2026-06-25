package tela.web

import play.api.Logging
import play.api.inject.ApplicationLifecycle

import java.nio.file.FileSystem
import java.util.concurrent.{Executors, ScheduledFuture, TimeUnit}
import javax.inject.{Inject, Singleton}
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.Try

sealed trait CacheKey {
  def parentKey: Option[CacheKey]
}
case class ArchiveKey(absolutePath: String) extends CacheKey {
  val parentKey: Option[CacheKey] = None
}
case class NestedArchiveKey(parent: CacheKey, pathWithinParent: String) extends CacheKey {
  val parentKey: Option[CacheKey] = Some(parent)
}

@Singleton
class ArchiveFileSystemCache @Inject()(lifecycle: ApplicationLifecycle) extends Logging {
  protected val inactivityPeriod: FiniteDuration = 10.minutes

  private val scheduler = Executors.newSingleThreadScheduledExecutor()

  lifecycle.addStopHook { () =>
    Future.successful {
      scheduler.shutdown()
      synchronized {
        cache.foreach { case (key, entry) =>
          logger.info(s"Closing FileSystem for $key on shutdown")
          Try(entry.fileSystem.close()).failed.foreach(e => logger.warn(s"Error closing FileSystem for $key on shutdown", e))
        }
        cache.clear()
      }
    }
  }

  private case class CacheEntry(fileSystem: FileSystem, var refCount: Int, var evictionTask: Option[ScheduledFuture[?]])

  private val cache = mutable.HashMap[CacheKey, CacheEntry]()

  def acquire(key: CacheKey, create: () => FileSystem): FileSystem = synchronized {
    cache.get(key) match {
      case Some(entry) =>
        if (entry.evictionTask.isDefined) {
          entry.evictionTask.foreach(_.cancel(false))
          entry.evictionTask = None
          logger.info(s"Cancelled eviction for $key")
        }
        entry.refCount += 1
        logger.info(s"Re-acquired active entry for $key (refCount: ${entry.refCount})")
        entry.fileSystem
      case None =>
        val fs = create()
        key.parentKey.foreach { pk =>
          cache.get(pk).foreach { parentEntry =>
            if (parentEntry.evictionTask.isDefined) {
              logger.info(s"Cancelling eviction of parent $pk due to new child $key")
              parentEntry.evictionTask.foreach(_.cancel(false))
              parentEntry.evictionTask = None
            }
            parentEntry.refCount += 1
            logger.info(s"Acquired new entry for $pk (refCount: ${parentEntry.refCount}) as parent of $key")
          }
        }
        cache(key) = CacheEntry(fs, refCount = 1, evictionTask = None)
        logger.info(s"Acquired new entry for $key (refCount: 1)")
        fs
    }
  }

  def release(key: CacheKey): Unit = synchronized {
    cache.get(key).foreach { entry =>
      entry.refCount -= 1
      logger.info(s"Released $key (refCount: ${entry.refCount})")
      if (entry.refCount == 0) {
        val task = scheduler.schedule((() => evict(key)): Runnable, inactivityPeriod.toMillis, TimeUnit.MILLISECONDS)
        entry.evictionTask = Some(task)
      }
    }
  }

  private def evict(key: CacheKey): Unit = synchronized {
    cache.get(key).foreach { entry =>
      logger.info(s"Evict called for $key (refCount: ${entry.refCount})")
      if (entry.refCount == 0) {
        Try(entry.fileSystem.close()).failed.foreach(e => logger.warn(s"Error closing FileSystem for key $key", e))
        cache.remove(key)
        key.parentKey.foreach(release)
      }
    }
  }
}
