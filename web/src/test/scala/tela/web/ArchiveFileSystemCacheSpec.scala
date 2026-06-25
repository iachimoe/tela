package tela.web

import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers._
import play.api.inject.DefaultApplicationLifecycle
import tela.baseinterfaces.BaseSpec

import java.nio.file.FileSystem
import scala.concurrent.Await
import scala.concurrent.duration.*

class ArchiveFileSystemCacheSpec extends BaseSpec with BeforeAndAfterAll {
  private val lifecycle = new DefaultApplicationLifecycle()

  override protected def afterAll(): Unit = {
    Await.result(lifecycle.stop(), 10.seconds)
    super.afterAll()
  }

  private def shortTtlCache(): ArchiveFileSystemCache =
    new ArchiveFileSystemCache(lifecycle) {
      override protected val inactivityPeriod: FiniteDuration = 200.millis
    }

  private val key1 = ArchiveKey("key1")

  "acquire" should "call the factory on first access and return the FileSystem" in {
    val cache = shortTtlCache()
    var callCount = 0
    val mockFs = mock[FileSystem]
    val result = cache.acquire(key1, () => { callCount += 1; mockFs })
    result should ===(mockFs)
    callCount should ===(1)
    cache.release(key1)
  }

  it should "reuse an active entry without calling the factory again" in {
    val cache = shortTtlCache()
    var callCount = 0
    val mockFs = mock[FileSystem]
    val factory = () => { callCount += 1; mockFs }
    cache.acquire(key1, factory)
    val second = cache.acquire(key1, factory)
    second should ===(mockFs)
    callCount should ===(1)
    cache.release(key1)
    cache.release(key1)
  }

  it should "reuse an idle entry without calling the factory again" in {
    val cache = shortTtlCache()
    var callCount = 0
    val mockFs = mock[FileSystem]
    val factory = () => { callCount += 1; mockFs }
    cache.acquire(key1, factory)
    cache.release(key1)
    val second = cache.acquire(key1, factory)
    second should ===(mockFs)
    callCount should ===(1)
    cache.release(key1)
  }

  "release" should "not close the FileSystem immediately" in {
    val cache = shortTtlCache()
    val mockFs = mock[FileSystem]
    cache.acquire(key1, () => mockFs)
    cache.release(key1)
    org.mockito.Mockito.verify(mockFs, org.mockito.Mockito.never()).close()
  }

  it should "close the FileSystem after the inactivity period expires" in {
    val cache = shortTtlCache()
    val mockFs = mock[FileSystem]
    cache.acquire(key1, () => mockFs)
    cache.release(key1)
    Thread.sleep(500)
    org.mockito.Mockito.verify(mockFs).close()
  }

  it should "cancel eviction when the entry is re-acquired before TTL fires" in {
    val cache = shortTtlCache()
    val mockFs = mock[FileSystem]
    cache.acquire(key1, () => mockFs)
    cache.release(key1)
    cache.acquire(key1, () => mockFs)
    Thread.sleep(500)
    org.mockito.Mockito.verify(mockFs, org.mockito.Mockito.never()).close()
    cache.release(key1)
  }

  it should "not throw when releasing an unknown key" in {
    val cache = shortTtlCache()
    noException should be thrownBy cache.release(ArchiveKey("nonexistent"))
  }

  "concurrent access" should "call the factory exactly once when two threads race to acquire the same key" in {
    val cache = shortTtlCache()
    var callCount = 0
    val mockFs = mock[FileSystem]
    val factory = () => { callCount += 1; Thread.sleep(10); mockFs }

    val t1 = new Thread(() => { cache.acquire(key1, factory); () })
    val t2 = new Thread(() => { cache.acquire(key1, factory); () })
    t1.start(); t2.start()
    t1.join(); t2.join()

    callCount should ===(1)
    cache.release(key1)
    cache.release(key1)
  }

  "keys" should "be independent — different keys have separate entries" in {
    val cache = shortTtlCache()
    var countA = 0
    var countB = 0
    val fsA = mock[FileSystem]
    val fsB = mock[FileSystem]
    val keyA = ArchiveKey("keyA")
    val keyB = ArchiveKey("keyB")
    val resultA = cache.acquire(keyA, () => { countA += 1; fsA })
    val resultB = cache.acquire(keyB, () => { countB += 1; fsB })
    resultA should ===(fsA)
    resultB should ===(fsB)
    countA should ===(1)
    countB should ===(1)
    cache.release(keyA)
    cache.release(keyB)
  }

  "nested archives" should "not evict the parent while the child is still cached" in {
    val cache = shortTtlCache()
    val parentFs = mock[FileSystem]
    val childFs = mock[FileSystem]
    val parentKey = ArchiveKey("parent")
    val childKey = NestedArchiveKey(parentKey, "child")

    cache.acquire(parentKey, () => parentFs)
    cache.acquire(childKey, () => childFs)

    // Both released — child holds an extra ref on parent so parent refCount is still 1
    cache.release(childKey)
    cache.release(parentKey)

    // After one TTL: child is evicted, but parent's TTL has only just started
    Thread.sleep(300)
    org.mockito.Mockito.verify(childFs).close()
    org.mockito.Mockito.verify(parentFs, org.mockito.Mockito.never()).close()

    // After a second TTL: parent is now evicted too
    Thread.sleep(300)
    org.mockito.Mockito.verify(parentFs).close()
  }

  it should "evict the parent after the child is evicted" in {
    val cache = shortTtlCache()
    val parentFs = mock[FileSystem]
    val childFs = mock[FileSystem]
    val parentKey = ArchiveKey("parent")
    val childKey = NestedArchiveKey(parentKey, "child")

    cache.acquire(parentKey, () => parentFs)
    cache.acquire(childKey, () => childFs)
    cache.release(childKey)
    cache.release(parentKey)

    Thread.sleep(600) // two TTL periods
    org.mockito.Mockito.verify(childFs).close()
    org.mockito.Mockito.verify(parentFs).close()
  }

  "stop hook" should "close all open FileSystems regardless of whether they are in use" in {
    val localLifecycle = new DefaultApplicationLifecycle()
    val cache = new ArchiveFileSystemCache(localLifecycle)
    val activeFs = mock[FileSystem]
    val idleFs = mock[FileSystem]
    cache.acquire(ArchiveKey("active"), () => activeFs)
    cache.acquire(ArchiveKey("idle"), () => idleFs)
    cache.release(ArchiveKey("idle"))
    Await.result(localLifecycle.stop(), 10.seconds)
    org.mockito.Mockito.verify(activeFs).close()
    org.mockito.Mockito.verify(idleFs).close()
  }
}
