/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.fuse;

import static jnr.constants.platform.OpenFlags.O_RDONLY;
import static jnr.constants.platform.OpenFlags.O_WRONLY;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import alluxio.AlluxioURI;
import alluxio.ConfigurationRule;
import alluxio.Constants;
import alluxio.annotation.dora.DoraTestTodoItem;
import alluxio.client.block.BlockMasterClient;
import alluxio.client.file.FileInStream;
import alluxio.client.file.FileOutStream;
import alluxio.client.file.FileSystem;
import alluxio.client.file.URIStatus;
import alluxio.conf.Configuration;
import alluxio.conf.InstancedConfiguration;
import alluxio.conf.PropertyKey;
import alluxio.exception.FileAlreadyExistsException;
import alluxio.exception.FileDoesNotExistException;
import alluxio.exception.FileIncompleteException;
import alluxio.fuse.options.FuseOptions;
import alluxio.grpc.CreateDirectoryPOptions;
import alluxio.grpc.CreateFilePOptions;
import alluxio.grpc.SetAttributePOptions;
import alluxio.security.authorization.Mode;
import alluxio.wire.BlockMasterInfo;
import alluxio.wire.FileInfo;

import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import jnr.ffi.Pointer;
import jnr.ffi.Runtime;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.stubbing.Answer;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import ru.serce.jnrfuse.ErrorCodes;
import ru.serce.jnrfuse.struct.FileStat;
import ru.serce.jnrfuse.struct.FuseFileInfo;
import ru.serce.jnrfuse.struct.Statvfs;

import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Isolation tests for {@link AlluxioJnrFuseFileSystem}.
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest({BlockMasterClient.Factory.class})
@Ignore
@DoraTestTodoItem(action = DoraTestTodoItem.Action.REMOVE, owner = "LuQQiu",
    comment = "remove JNR support in 30x")
public class AlluxioJnrFuseFileSystemTest {

  private static final String TEST_ROOT_PATH = "/t/root";
  private static final AlluxioURI BASE_EXPECTED_URI = new AlluxioURI(TEST_ROOT_PATH);
  private static final String MOUNT_POINT = "/t/mountPoint";

  private AlluxioJnrFuseFileSystem mFuseFs;
  private FileSystem mFileSystem;
  private FuseFileInfo mFileInfo;
  private InstancedConfiguration mConf = Configuration.copyGlobal();

  @Rule
  public ConfigurationRule mConfiguration =
      new ConfigurationRule(ImmutableMap.of(PropertyKey.FUSE_CACHED_PATHS_MAX, 0,
          PropertyKey.FUSE_USER_GROUP_TRANSLATION_ENABLED, true,
          PropertyKey.FUSE_MOUNT_ALLUXIO_PATH, TEST_ROOT_PATH,
          PropertyKey.FUSE_MOUNT_POINT, MOUNT_POINT,
          PropertyKey.USER_METADATA_CACHE_MAX_SIZE, 0), mConf);

  @Before
  public void before() throws Exception {
    mFileSystem = mock(FileSystem.class);
    try {
      mFuseFs = new AlluxioJnrFuseFileSystem(mFileSystem, mConf, FuseOptions.create(mConf));
    } catch (UnsatisfiedLinkError e) {
      // stop test and ignore if FuseFileSystem fails to create due to missing libfuse library
      Assume.assumeNoException(e);
    }
    mFileInfo = allocateNativeFileInfo();
  }

  @Test
  public void chmod() throws Exception {
    long mode = 123;
    mFuseFs.chmod("/foo/bar", mode);
    AlluxioURI expectedPath = BASE_EXPECTED_URI.join("/foo/bar");
    SetAttributePOptions options =
        SetAttributePOptions.newBuilder().setMode(new Mode((short) mode).toProto()).build();
    verify(mFileSystem).setAttribute(expectedPath, options);
  }

  @Test
  public void chown() throws Exception {
    Optional<Long> uid = AlluxioFuseUtils.getUid(System.getProperty("user.name"));
    Optional<Long> gid = AlluxioFuseUtils.getGidFromUserName(System.getProperty("user.name"));
    assertTrue(uid.isPresent());
    assertTrue(gid.isPresent());
    mFuseFs.chown("/foo/bar", uid.get(), gid.get());
    String userName = System.getProperty("user.name");
    Optional<String> groupName = AlluxioFuseUtils.getGroupName(gid.get());
    Assert.assertTrue(groupName.isPresent());
    AlluxioURI expectedPath = BASE_EXPECTED_URI.join("/foo/bar");
    SetAttributePOptions options =
        SetAttributePOptions.newBuilder().setGroup(groupName.get()).setOwner(userName).build();
    verify(mFileSystem).setAttribute(expectedPath, options);
  }

  @Test
  public void chownWithoutValidGid() throws Exception {
    Optional<Long> uid = AlluxioFuseUtils.getUid(System.getProperty("user.name"));
    assertTrue(uid.isPresent());
    long gid = AlluxioJnrFuseFileSystem.ID_NOT_SET_VALUE;
    mFuseFs.chown("/foo/bar", uid.get(), gid);
    String userName = System.getProperty("user.name");
    Optional<String> groupName = AlluxioFuseUtils.getGroupName(userName);
    Assert.assertTrue(groupName.isPresent());
    AlluxioURI expectedPath = BASE_EXPECTED_URI.join("/foo/bar");
    SetAttributePOptions options =
        SetAttributePOptions.newBuilder().setOwner(userName).build();
    verify(mFileSystem).setAttribute(expectedPath, options);

    gid = AlluxioJnrFuseFileSystem.ID_NOT_SET_VALUE_UNSIGNED;
    mFuseFs.chown("/foo/bar", uid.get(), gid);
    verify(mFileSystem, times(2)).setAttribute(expectedPath, options);
  }

  @Test
  public void chownWithoutValidUid() throws Exception {
    String userName = System.getProperty("user.name");
    long uid = AlluxioJnrFuseFileSystem.ID_NOT_SET_VALUE;
    Optional<Long> gid = AlluxioFuseUtils.getGidFromUserName(userName);
    assertTrue(gid.isPresent());
    mFuseFs.chown("/foo/bar", uid, gid.get());

    Optional<String> groupName = AlluxioFuseUtils.getGroupName(gid.get());
    Assert.assertTrue(groupName.isPresent());
    AlluxioURI expectedPath = BASE_EXPECTED_URI.join("/foo/bar");
    SetAttributePOptions options = SetAttributePOptions.newBuilder()
        .setGroup(groupName.get()).build();
    verify(mFileSystem).setAttribute(expectedPath, options);

    uid = AlluxioJnrFuseFileSystem.ID_NOT_SET_VALUE_UNSIGNED;
    mFuseFs.chown("/foo/bar", uid, gid.get());
    verify(mFileSystem, times(2)).setAttribute(expectedPath, options);
  }

  @Test
  public void chownWithoutValidUidAndGid() throws Exception {
    long uid = AlluxioJnrFuseFileSystem.ID_NOT_SET_VALUE;
    long gid = AlluxioJnrFuseFileSystem.ID_NOT_SET_VALUE;
    mFuseFs.chown("/foo/bar", uid, gid);
    verify(mFileSystem, never()).setAttribute(any());

    uid = AlluxioJnrFuseFileSystem.ID_NOT_SET_VALUE_UNSIGNED;
    gid = AlluxioJnrFuseFileSystem.ID_NOT_SET_VALUE_UNSIGNED;
    mFuseFs.chown("/foo/bar", uid, gid);
    verify(mFileSystem, never()).setAttribute(any());
  }

  @Test
  public void create() throws Exception {
    mFileInfo.flags.set(O_WRONLY.intValue());
    mFuseFs.create("/foo/bar", 0, mFileInfo);
    AlluxioURI expectedPath = BASE_EXPECTED_URI.join("/foo/bar");
    verify(mFileSystem).createFile(expectedPath, CreateFilePOptions.newBuilder()
        .setMode(new alluxio.security.authorization.Mode((short) 0).toProto())
        .build());
  }

  @Test
  public void createWithLengthLimit() throws Exception {
    String c256 = String.join("", Collections.nCopies(16, "0123456789ABCDEF"));
    mFileInfo.flags.set(O_WRONLY.intValue());
    assertEquals(-ErrorCodes.ENAMETOOLONG(),
        mFuseFs.create("/foo/" + c256, 0, mFileInfo));
  }

  @Test
  public void flush() throws Exception {
    FileOutStream fos = mock(FileOutStream.class);
    AlluxioURI anyURI = any();
    CreateFilePOptions options = any();
    when(mFileSystem.createFile(anyURI, options)).thenReturn(fos);

    // open a file
    mFileInfo.flags.set(O_WRONLY.intValue());
    mFuseFs.create("/foo/bar", 0, mFileInfo);

    // then call flush into it
    mFuseFs.flush("/foo/bar", mFileInfo);
    verify(fos).flush();
  }

  @Test
  public void getattr() throws Exception {
    // set up status
    FileInfo info = new FileInfo();
    info.setLength(4 * Constants.KB + 1);
    info.setLastModificationTimeMs(1000);
    String userName = System.getProperty("user.name");
    info.setOwner(userName);
    Optional<String> groupName = AlluxioFuseUtils.getGroupName(userName);
    Assert.assertTrue(groupName.isPresent());
    info.setGroup(groupName.get());
    info.setFolder(true);
    info.setMode(123);
    info.setCompleted(true);
    URIStatus status = new URIStatus(info);

    // mock fs
    when(mFileSystem.getStatus(any(AlluxioURI.class))).thenReturn(status);

    FileStat stat = new FileStat(Runtime.getSystemRuntime());
    assertEquals(0, mFuseFs.getattr("/foo", stat));
    assertEquals(status.getLength(), stat.st_size.longValue());
    assertEquals(9, stat.st_blocks.intValue());
    assertEquals(status.getLastModificationTimeMs() / 1000, stat.st_ctim.tv_sec.get());
    assertEquals((status.getLastModificationTimeMs() % 1000) * 1000,
        stat.st_ctim.tv_nsec.longValue());
    assertEquals(status.getLastModificationTimeMs() / 1000, stat.st_mtim.tv_sec.get());
    assertEquals((status.getLastModificationTimeMs() % 1000) * 1000,
        stat.st_mtim.tv_nsec.longValue());
    Optional<Long> uid = AlluxioFuseUtils.getUid(System.getProperty("user.name"));
    Optional<Long> gid = AlluxioFuseUtils.getGidFromUserName(System.getProperty("user.name"));
    assertTrue(uid.isPresent());
    assertTrue(gid.isPresent());
    assertEquals((long) uid.get(), stat.st_uid.get());
    assertEquals((long) gid.get(), stat.st_gid.get());
    assertEquals(123 | FileStat.S_IFDIR, stat.st_mode.intValue());
  }

  @Test
  public void getattrWithDelay() throws Exception {
    String path = "/foo/bar";
    AlluxioURI expectedPath = BASE_EXPECTED_URI.join("/foo/bar");

    // set up status
    FileInfo info = new FileInfo();
    info.setLength(0);
    info.setCompleted(false);
    URIStatus status = new URIStatus(info);

    // mock fs
    when(mFileSystem.getStatus(any(AlluxioURI.class))).thenReturn(status);

    FileStat stat = new FileStat(Runtime.getSystemRuntime());

    // Use another thread to open file so that
    // we could change the file status when opening it
    Thread t = new Thread(() -> mFuseFs.getattr(path, stat));
    t.start();
    Thread.sleep(1000);

    // If the file is not being written and is not completed,
    // we will wait for the file to complete
    verify(mFileSystem, atLeast(10)).getStatus(expectedPath);
    assertEquals(0, stat.st_size.longValue());

    info.setCompleted(true);
    info.setLength(1000);

    t.join();

    assertEquals(1000, stat.st_size.longValue());
  }

  @Test
  public void getattrWhenWriting() throws Exception {
    String path = "/foo/bar";
    AlluxioURI expectedPath = BASE_EXPECTED_URI.join(path);

    FileOutStream fos = mock(FileOutStream.class);
    when(mFileSystem.createFile(expectedPath)).thenReturn(fos);

    mFuseFs.create(path, 0, mFileInfo);

    // Prepare file status
    FileInfo info = new FileInfo();
    info.setLength(0);
    info.setCompleted(false);
    URIStatus status = new URIStatus(info);

    when(mFileSystem.exists(any(AlluxioURI.class))).thenReturn(true);
    when(mFileSystem.getStatus(any(AlluxioURI.class))).thenReturn(status);

    FileStat stat = new FileStat(Runtime.getSystemRuntime());

    // getattr() will not be blocked when writing
    mFuseFs.getattr(path, stat);
    // If getattr() is blocking, it will continuously get status of the file
    verify(mFileSystem, atMost(300)).getStatus(expectedPath);
    assertEquals(0, stat.st_size.longValue());

    mFuseFs.release(path, mFileInfo);

    // getattr() will be blocked waiting for the file to be completed
    // If release() is called (returned) but does not finished
    Thread t = new Thread(() -> mFuseFs.getattr(path, stat));
    t.start();
    Thread.sleep(1000);
    verify(mFileSystem, atLeast(10)).getStatus(expectedPath);
    assertEquals(0, stat.st_size.longValue());

    info.setCompleted(true);
    info.setLength(1000);

    t.join();

    // getattr() completed and set the file size
    assertEquals(1000, stat.st_size.longValue());
  }

  @Test
  public void mkDir() throws Exception {
    long mode = 0755L;
    mFuseFs.mkdir("/foo/bar", mode);
    verify(mFileSystem).createDirectory(BASE_EXPECTED_URI.join("/foo/bar"),
        CreateDirectoryPOptions.newBuilder()
            .setMode(new alluxio.security.authorization.Mode((short) mode).toProto())
            .build());
  }

  @Test
  public void mkDirWithLengthLimit() throws Exception {
    long mode = 0755L;
    String c256 = String.join("", Collections.nCopies(16, "0123456789ABCDEF"));
    assertEquals(-ErrorCodes.ENAMETOOLONG(),
        mFuseFs.mkdir("/foo/" + c256, mode));
  }

  @Test
  public void openWithoutDelay() throws Exception {
    AlluxioURI expectedPath = BASE_EXPECTED_URI.join("/foo/bar");
    setUpOpenMock(expectedPath);

    FileInStream is = mock(FileInStream.class);
    when(mFileSystem.openFile(expectedPath)).thenReturn(is);
    mFuseFs.open("/foo/bar", mFileInfo);
    verify(mFileSystem).openFile(expectedPath);
  }

  @Test
  public void incompleteFileCannotOpen() throws Exception {
    AlluxioURI expectedPath = BASE_EXPECTED_URI.join("/foo/bar");
    FileInfo fi = setUpOpenMock(expectedPath);
    fi.setCompleted(false);

    when(mFileSystem.openFile(expectedPath)).thenThrow(new FileIncompleteException(expectedPath));
    assertEquals(-ErrorCodes.EFAULT(), mFuseFs.open("/foo/bar", mFileInfo));
  }

  @Test
  public void openWithDelay() throws Exception {
    AlluxioURI expectedPath = BASE_EXPECTED_URI.join("/foo/bar");
    FileInfo fi = setUpOpenMock(expectedPath);
    fi.setCompleted(false);
    when(mFileSystem.openFile(expectedPath)).thenThrow(new FileIncompleteException(expectedPath));

    // Use another thread to open file so that
    // we could change the file status when opening it
    Thread t = new Thread(() -> mFuseFs.open("/foo/bar", mFileInfo));
    t.start();
    Thread.sleep(1000);
    // If the file exists but is not completed, we will wait for the file to complete
    verify(mFileSystem, atLeast(10)).getStatus(expectedPath);

    fi.setCompleted(true);
    t.join();
    verify(mFileSystem, times(2)).openFile(expectedPath);
  }

  @Test
  public void read() throws Exception {
    // mocks set-up
    AlluxioURI expectedPath = BASE_EXPECTED_URI.join("/foo/bar");
    setUpOpenMock(expectedPath);

    FileInStream fakeInStream = mock(FileInStream.class);
    when(fakeInStream.read(any(byte[].class),
        anyInt(), anyInt())).then((Answer<Integer>) invocationOnMock -> {
          byte[] myDest = (byte[]) invocationOnMock.getArguments()[0];
          for (byte i = 0; i < 4; i++) {
            myDest[i] = i;
          }
          return 4;
        });
    when(fakeInStream.remaining()).thenReturn(4L);

    when(mFileSystem.openFile(expectedPath)).thenReturn(fakeInStream);
    mFileInfo.flags.set(O_RDONLY.intValue());

    // prepare something to read to it
    Runtime r = Runtime.getSystemRuntime();
    Pointer ptr = r.getMemoryManager().allocateTemporary(4, true);

    // actual test
    mFuseFs.open("/foo/bar", mFileInfo);

    mFuseFs.read("/foo/bar", ptr, 4, 0, mFileInfo);
    final byte[] dst = new byte[4];
    ptr.get(0, dst, 0, 4);
    final byte[] expected = new byte[] {0, 1, 2, 3};

    assertArrayEquals("Source and dst data should be equal", expected, dst);
  }

  @Test
  public void readOffset() throws Exception {
    // mocks set-up
    AlluxioURI expectedPath = BASE_EXPECTED_URI.join("/foo/bar");
    setUpOpenMock(expectedPath);

    FileInStream fakeInStream = mock(FileInStream.class);
    when(fakeInStream.read(any(byte[].class),
        anyInt(), anyInt())).then((Answer<Integer>) invocationOnMock -> {
          byte[] myDest = (byte[]) invocationOnMock.getArguments()[0];
          for (byte i = 0; i < (int) invocationOnMock.getArgument(2); i++) {
            myDest[i] = (byte) (i + 1);
          }
          return myDest.length;
        });
    AtomicInteger callCounter = new AtomicInteger();
    when(fakeInStream.remaining()).then((Answer<Long>) invocationOnMock -> {
      if (callCounter.getAndIncrement() == 0) {
        return 4L;
      } else {
        return 3L;
      }
    });

    when(mFileSystem.openFile(expectedPath)).thenReturn(fakeInStream);
    mFileInfo.flags.set(O_RDONLY.intValue());

    // prepare something to read to it
    Runtime r = Runtime.getSystemRuntime();
    Pointer ptr = r.getMemoryManager().allocateTemporary(2, true);

    // actual test
    mFuseFs.open("/foo/bar", mFileInfo);

    mFuseFs.read("/foo/bar", ptr, 2, 1, mFileInfo);
    final byte[] dst = new byte[2];
    ptr.get(0, dst, 0, 2);
    final byte[] expected = new byte[] {1, 2};

    assertArrayEquals("Source and dst data should be equal", expected, dst);
  }

  @Test
  public void readOffset2() throws Exception {
    // mocks set-up
    AlluxioURI expectedPath = BASE_EXPECTED_URI.join("/foo/bar");
    setUpOpenMock(expectedPath);

    FileInStream fakeInStream = mock(FileInStream.class);
    when(fakeInStream.read(any(byte[].class),
        anyInt(), anyInt())).then((Answer<Integer>) invocationOnMock -> {
          byte[] myDest = (byte[]) invocationOnMock.getArguments()[0];
          for (byte i = 0; i < (int) invocationOnMock.getArgument(2); i++) {
            myDest[i] = i;
          }
          return myDest.length;
        });
    AtomicInteger callCounter = new AtomicInteger();
    when(fakeInStream.remaining()).then((Answer<Long>) invocationOnMock -> {
      if (callCounter.getAndIncrement() == 0) {
        return 4L;
      } else {
        return 3L;
      }
    });

    when(mFileSystem.openFile(expectedPath)).thenReturn(fakeInStream);
    mFileInfo.flags.set(O_RDONLY.intValue());

    // prepare something to read to it
    Runtime r = Runtime.getSystemRuntime();
    Pointer ptr = r.getMemoryManager().allocateTemporary(4, true);

    // actual test
    mFuseFs.open("/foo/bar", mFileInfo);

    mFuseFs.read("/foo/bar", ptr, 4, 4, mFileInfo);
    final byte[] dst = new byte[0];
    ptr.get(0, dst, 0, 0);
    final byte[] expected = new byte[0];

    assertArrayEquals("Source and dst data should be equal", expected, dst);
  }

  @Test
  public void rename() throws Exception {
    AlluxioURI oldPath = BASE_EXPECTED_URI.join("/old");
    AlluxioURI newPath = BASE_EXPECTED_URI.join("/new");
    doNothing().when(mFileSystem).rename(oldPath, newPath);
    mFuseFs.rename("/old", "/new");
    verify(mFileSystem).rename(oldPath, newPath);
  }

  @Test
  public void renameOldNotExist() throws Exception {
    AlluxioURI oldPath = BASE_EXPECTED_URI.join("/old");
    AlluxioURI newPath = BASE_EXPECTED_URI.join("/new");
    doThrow(new FileDoesNotExistException("File /old does not exist"))
        .when(mFileSystem).rename(oldPath, newPath);
    assertEquals(-ErrorCodes.ENOENT(), mFuseFs.rename("/old", "/new"));
  }

  @Test
  public void renameNewExist() throws Exception {
    AlluxioURI oldPath = BASE_EXPECTED_URI.join("/old");
    AlluxioURI newPath = BASE_EXPECTED_URI.join("/new");
    doThrow(new FileAlreadyExistsException("File /new already exists"))
        .when(mFileSystem).rename(oldPath, newPath);
    assertEquals(-ErrorCodes.EEXIST(), mFuseFs.rename("/old", "/new"));
  }

  @Test
  public void renameWithLengthLimit() throws Exception {
    String c256 = String.join("", Collections.nCopies(16, "0123456789ABCDEF"));
    AlluxioURI oldPath = BASE_EXPECTED_URI.join("/old");
    AlluxioURI newPath = BASE_EXPECTED_URI.join("/" + c256);
    doNothing().when(mFileSystem).rename(oldPath, newPath);
    assertEquals(-ErrorCodes.ENAMETOOLONG(),
        mFuseFs.rename("/old", "/" + c256));
  }

  @Test
  public void rmdir() throws Exception {
    AlluxioURI expectedPath = BASE_EXPECTED_URI.join("/foo/bar");
    doNothing().when(mFileSystem).delete(expectedPath);
    mFuseFs.rmdir("/foo/bar");
    verify(mFileSystem).delete(expectedPath);
  }

  @Test
  public void write() throws Exception {
    FileOutStream fos = mock(FileOutStream.class);
    AlluxioURI anyURI = any();
    CreateFilePOptions options = any();
    when(mFileSystem.createFile(anyURI, options)).thenReturn(fos);

    // open a file
    mFileInfo.flags.set(O_WRONLY.intValue());
    mFuseFs.create("/foo/bar", 0, mFileInfo);

    // prepare something to write into it
    Runtime r = Runtime.getSystemRuntime();
    Pointer ptr = r.getMemoryManager().allocateTemporary(4, true);
    byte[] expected = {42, -128, 1, 3};
    ptr.put(0, expected, 0, 4);

    mFuseFs.write("/foo/bar", ptr, 4, 0, mFileInfo);
    verify(fos).write(expected);

    // the second write is no-op because the writes must be sequential and overwriting is supported
    mFuseFs.write("/foo/bar", ptr, 4, 0, mFileInfo);
    verify(fos, times(1)).write(expected);
  }

  @Test
  public void unlink() throws Exception {
    AlluxioURI expectedPath = BASE_EXPECTED_URI.join("/foo/bar");
    doNothing().when(mFileSystem).delete(expectedPath);
    mFuseFs.unlink("/foo/bar");
    verify(mFileSystem).delete(expectedPath);
  }

  @Test
  public void pathTranslation() throws Exception {
    final LoadingCache<String, AlluxioURI> resolver = mFuseFs.getPathResolverCache();

    AlluxioURI expected = new AlluxioURI(TEST_ROOT_PATH);
    AlluxioURI actual = resolver.apply("/");
    assertEquals("/ should resolve to " + expected, expected, actual);

    expected = new AlluxioURI(TEST_ROOT_PATH + "/home/foo");
    actual = resolver.apply("/home/foo");
    assertEquals("/home/foo should resolve to " + expected, expected, actual);
  }

  // Allocate native memory for a FuseFileInfo data struct and return its pointer
  private FuseFileInfo allocateNativeFileInfo() {
    final Runtime runtime = Runtime.getSystemRuntime();
    final Pointer pt = runtime.getMemoryManager().allocateTemporary(36, true);
    return FuseFileInfo.of(pt);
  }

  /**
   * Sets up mock for open() operation.
   *
   * @param uri the path to run operations on
   * @return the file information
   */
  private FileInfo setUpOpenMock(AlluxioURI uri) throws Exception {
    FileInfo fi = new FileInfo();
    fi.setCompleted(true);
    fi.setFolder(false);
    URIStatus status = new URIStatus(fi);

    when(mFileSystem.getStatus(uri)).thenReturn(status);
    return fi;
  }

  @Test
  public void statfs() throws Exception {
    Runtime runtime = Runtime.getSystemRuntime();
    Pointer pointer = runtime.getMemoryManager().allocateTemporary(4 * Constants.KB, true);
    Statvfs stbuf = Statvfs.of(pointer);

    int blockSize = 4 * Constants.KB;
    int totalBlocks = 4;
    int freeBlocks = 3;

    BlockMasterClient blockMasterClient = PowerMockito.mock(BlockMasterClient.class);
    PowerMockito.mockStatic(BlockMasterClient.Factory.class);
    when(BlockMasterClient.Factory.create(any())).thenReturn(blockMasterClient);

    BlockMasterInfo blockMasterInfo = new BlockMasterInfo();
    blockMasterInfo.setCapacityBytes(totalBlocks * blockSize);
    blockMasterInfo.setFreeBytes(freeBlocks * blockSize);
    when(blockMasterClient.getBlockMasterInfo(any())).thenReturn(blockMasterInfo);

    assertEquals(0, mFuseFs.statfs("/", stbuf));

    assertEquals(blockSize, stbuf.f_bsize.intValue());
    assertEquals(blockSize, stbuf.f_frsize.intValue());
    assertEquals(totalBlocks, stbuf.f_blocks.longValue());
    assertEquals(freeBlocks, stbuf.f_bfree.longValue());
    assertEquals(freeBlocks, stbuf.f_bavail.longValue());

    assertEquals(AlluxioJnrFuseFileSystem.UNKNOWN_INODES, stbuf.f_files.intValue());
    assertEquals(AlluxioJnrFuseFileSystem.UNKNOWN_INODES, stbuf.f_ffree.intValue());
    assertEquals(AlluxioJnrFuseFileSystem.UNKNOWN_INODES, stbuf.f_favail.intValue());
    assertEquals(AlluxioJnrFuseFileSystem.MAX_NAME_LENGTH, stbuf.f_namemax.intValue());
  }
}
