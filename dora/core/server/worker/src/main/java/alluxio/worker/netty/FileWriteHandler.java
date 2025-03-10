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

package alluxio.worker.netty;

import alluxio.DefaultStorageTierAssoc;
import alluxio.StorageTierAssoc;
import alluxio.conf.Configuration;
import alluxio.conf.PropertyKey;
import alluxio.metrics.MetricsSystem;
import alluxio.network.protocol.RPCProtoMessage;
import alluxio.proto.dataserver.Protocol;
import alluxio.worker.dora.DoraWorker;

import com.google.common.base.Preconditions;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * This handler handles block write request. Check more information in
 * {@link AbstractWriteHandler}.
 */
@alluxio.annotation.SuppressFBWarnings(
    value = "BC_UNCONFIRMED_CAST_OF_RETURN_VALUE",
    justification = "false positive with superclass generics, "
        + "see more description in https://sourceforge.net/p/findbugs/bugs/1242/")
@NotThreadSafe
public class FileWriteHandler extends AbstractWriteHandler<BlockWriteRequestContext> {
  private static final Logger LOG = LoggerFactory.getLogger(FileWriteHandler.class);
  private static final long FILE_BUFFER_SIZE = Configuration.getBytes(
      PropertyKey.WORKER_FILE_BUFFER_SIZE);

  /** The Block Worker which handles blocks stored in the Alluxio storage of the worker. */
  private final DoraWorker mWorker;
  /** An object storing the mapping of tier aliases to ordinals. */
  private final StorageTierAssoc mStorageTierAssoc = new DefaultStorageTierAssoc(
      PropertyKey.WORKER_TIERED_STORE_LEVELS,
      PropertyKey.Template.WORKER_TIERED_STORE_LEVEL_ALIAS);

  /**
   * Creates an instance of {@link FileWriteHandler}.
   *
   * @param executorService the executor service to run {@link PacketWriter}s
   * @param doraWorker the dora worker
   */
  protected FileWriteHandler(ExecutorService executorService, DoraWorker doraWorker) {
    super(executorService);
    mWorker = doraWorker;
  }

  @Override
  protected boolean acceptMessage(Object object) {
    if (!super.acceptMessage(object)) {
      return false;
    }
    Protocol.WriteRequest request = ((RPCProtoMessage) object).getMessage().asWriteRequest();
    return request.getType() == Protocol.RequestType.ALLUXIO_BLOCK;
  }

  @Override
  protected PacketWriter createPacketWriter(BlockWriteRequestContext context, Channel channel) {
    return new BlockPacketWriter(context, channel, mWorker);
  }

  @Override
  protected BlockWriteRequestContext createRequestContext(Protocol.WriteRequest msg) {
    BlockWriteRequestContext context = new BlockWriteRequestContext(msg, FILE_BUFFER_SIZE);
    return context;
  }

  @Override
  protected void initRequestContext(BlockWriteRequestContext context) throws Exception {
    // reserved as a placeholder as there is no need to do any processing here
  }

  protected DoraWorker getWorker() {
    return mWorker;
  }

  /**
   * The packet writer that writes to a local block worker.
   */
  public class BlockPacketWriter extends PacketWriter {
    /** The Block Worker which handles blocks stored in the Alluxio storage of the worker. */
    private final DoraWorker mWorker;

    /**
     * @param context context of this packet writer
     * @param channel netty channel
     * @param worker local block worker
     */
    public BlockPacketWriter(
        BlockWriteRequestContext context, Channel channel, DoraWorker worker) {
      super(context, channel);
      mWorker = worker;
    }

    @Override
    protected void completeRequest(BlockWriteRequestContext context, Channel channel)
        throws Exception {
      if (context.getBlockWriter() != null) {
        context.getBlockWriter().commitFile();
        context.getBlockWriter().close();
      }
    }

    @Override
    protected void cancelRequest(BlockWriteRequestContext context) throws Exception {
      if (context.getBlockWriter() != null) {
        context.getBlockWriter().close();
      }
    }

    @Override
    protected void cleanupRequest(BlockWriteRequestContext context) throws Exception {
      WriteRequest request = context.getRequest();
      mWorker.cleanupSession(request.getSessionId());
      context.getBlockWriter().abort();
    }

    @Override
    protected void writeBuf(BlockWriteRequestContext context, Channel channel, ByteBuf buf,
        long pos) throws Exception {
      Preconditions.checkState(context != null);
      WriteRequest request = context.getRequest();
      long bytesReserved = context.getBytesReserved();
      if (bytesReserved < pos) {
        long bytesToReserve = Math.max(FILE_BUFFER_SIZE, pos - bytesReserved);
        // Allocate enough space in the existing temporary block for the write.
        //mWorker.requestSpace(request.getSessionId(), request.getId(), bytesToReserve);

        context.setBytesReserved(bytesReserved + bytesToReserve);
      }
      if (context.getBlockWriter() == null) {
        String metricName = "BytesWrittenAlluxio";
        context.setBlockWriter(
            mWorker.createFileWriter(request.getFileId(), request.getUfsPath()));
        context.setCounter(MetricsSystem.counter(metricName));
      }
      Preconditions.checkState(context.getBlockWriter() != null);
      int sz = buf.readableBytes();
      Preconditions.checkState(context.getBlockWriter().append(buf) == sz);
    }
  }
}
