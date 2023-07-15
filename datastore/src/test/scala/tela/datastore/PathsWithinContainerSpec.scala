package tela.datastore

import org.scalatest.matchers.should.Matchers._
import tela.baseinterfaces.BaseSpec
import tela.datastore.PathsWithinContainer.TikaPathInfo

class PathsWithinContainerSpec extends BaseSpec {
  "getCompletePath" should "retrieve path to file located just under root of container" in {
    new PathsWithinContainer(Vector(
      TikaPathInfo("file.txt", "/file.txt")
    )).getCompletePath(TikaPathInfo("file.txt", "/file.txt")) should ===("file.txt")
  }

  it should "retrieve path to file located in a folder under root of container" in {
    new PathsWithinContainer(Vector(
      TikaPathInfo("folder1/file.txt", "/file.txt")
    )).getCompletePath(TikaPathInfo("folder1/file.txt", "/file.txt")) should ===("folder1/file.txt")
  }

  it should "handle zip on root of other zip" in {
    new PathsWithinContainer(Vector(
      TikaPathInfo("next.zip", "/next.zip"),
      TikaPathInfo("folder1/file.txt", "/next.zip/file.txt")
    )).getCompletePath(TikaPathInfo("folder1/file.txt", "/next.zip/file.txt")) should ===("next.zip/folder1/file.txt")
  }

  it should "handle zip inside folder" in {
    new PathsWithinContainer(Vector(
      TikaPathInfo("rootFolder/next.zip", "/next.zip"),
      TikaPathInfo("folder1/file.txt", "/next.zip/file.txt")
    )).getCompletePath(TikaPathInfo("folder1/file.txt", "/next.zip/file.txt")) should ===("rootFolder/next.zip/folder1/file.txt")
  }

  it should "handle zip within zip" in {
    new PathsWithinContainer(Vector(
      TikaPathInfo("rootFolder/middleZip.zip", "/middleZip.zip"),
      TikaPathInfo("middleFolder/innermostZip.zip", "/middleZip.zip/innermostZip.zip"),
      TikaPathInfo("innermostFolder/file.txt", "/middleZip.zip/innermostZip.zip/file.txt")
    )).getCompletePath(
      TikaPathInfo("innermostFolder/file.txt", "/middleZip.zip/innermostZip.zip/file.txt")
    ) should ===("rootFolder/middleZip.zip/middleFolder/innermostZip.zip/innermostFolder/file.txt")
  }
}
