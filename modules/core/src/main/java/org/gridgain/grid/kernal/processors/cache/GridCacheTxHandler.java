/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.cache;

import org.apache.ignite.cluster.*;
import org.gridgain.grid.*;
import org.gridgain.grid.cache.*;
import org.gridgain.grid.kernal.processors.cache.distributed.*;
import org.gridgain.grid.kernal.processors.cache.distributed.dht.*;
import org.gridgain.grid.kernal.processors.cache.distributed.near.*;
import org.gridgain.grid.logger.*;
import org.gridgain.grid.util.future.*;
import org.gridgain.grid.util.lang.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.jetbrains.annotations.*;

import java.util.*;

import static org.gridgain.grid.cache.GridCacheTxConcurrency.*;
import static org.gridgain.grid.cache.GridCacheTxIsolation.*;
import static org.gridgain.grid.cache.GridCacheTxState.*;
import static org.gridgain.grid.kernal.processors.cache.GridCacheTxEx.FinalizationStatus.*;
import static org.gridgain.grid.kernal.processors.cache.GridCacheUtils.*;

/**
 * Isolated logic to process cache messages.
 */
public class GridCacheTxHandler<K, V> {
    /** Logger. */
    private GridLogger log;

    /** Shared cache context. */
    private GridCacheSharedContext<K, V> ctx;

    public IgniteFuture<GridCacheTxEx<K, V>> processNearTxPrepareRequest(final UUID nearNodeId,
        final GridNearTxPrepareRequest<K, V> req) {
        return prepareTx(nearNodeId, null, req);
    }

    /**
     * @param ctx Shared cache context.
     */
    public GridCacheTxHandler(GridCacheSharedContext<K, V> ctx) {
        this.ctx = ctx;

        log = ctx.logger(GridCacheTxHandler.class);

        ctx.io().addHandler(0, GridNearTxPrepareRequest.class, new CI2<UUID, GridCacheMessage<K, V>>() {
            @Override public void apply(UUID nodeId, GridCacheMessage<K, V> msg) {
                processNearTxPrepareRequest(nodeId, (GridNearTxPrepareRequest<K, V>)msg);
            }
        });

        ctx.io().addHandler(0, GridNearTxPrepareResponse.class, new CI2<UUID, GridCacheMessage<K, V>>() {
            @Override public void apply(UUID nodeId, GridCacheMessage<K, V> msg) {
                processNearTxPrepareResponse(nodeId, (GridNearTxPrepareResponse<K, V>)msg);
            }
        });

        ctx.io().addHandler(0, GridNearTxFinishRequest.class, new CI2<UUID, GridCacheMessage<K, V>>() {
            @Override public void apply(UUID nodeId, GridCacheMessage<K, V> msg) {
                processNearTxFinishRequest(nodeId, (GridNearTxFinishRequest<K, V>)msg);
            }
        });

        ctx.io().addHandler(0, GridNearTxFinishResponse.class, new CI2<UUID, GridCacheMessage<K, V>>() {
            @Override public void apply(UUID nodeId, GridCacheMessage<K, V> msg) {
                processNearTxFinishResponse(nodeId, (GridNearTxFinishResponse<K, V>)msg);
            }
        });

        ctx.io().addHandler(0, GridDhtTxPrepareRequest.class, new CI2<UUID, GridCacheMessage<K, V>>() {
            @Override public void apply(UUID nodeId, GridCacheMessage<K, V> msg) {
                processDhtTxPrepareRequest(nodeId, (GridDhtTxPrepareRequest<K, V>)msg);
            }
        });

        ctx.io().addHandler(0, GridDhtTxPrepareResponse.class, new CI2<UUID, GridCacheMessage<K, V>>() {
            @Override public void apply(UUID nodeId, GridCacheMessage<K, V> msg) {
                processDhtTxPrepareResponse(nodeId, (GridDhtTxPrepareResponse<K, V>)msg);
            }
        });

        ctx.io().addHandler(0, GridDhtTxFinishRequest.class, new CI2<UUID, GridCacheMessage<K, V>>() {
            @Override public void apply(UUID nodeId, GridCacheMessage<K, V> msg) {
                processDhtTxFinishRequest(nodeId, (GridDhtTxFinishRequest<K, V>)msg);
            }
        });

        ctx.io().addHandler(0, GridDhtTxFinishResponse.class, new CI2<UUID, GridCacheMessage<K, V>>() {
            @Override public void apply(UUID nodeId, GridCacheMessage<K, V> msg) {
                processDhtTxFinishResponse(nodeId, (GridDhtTxFinishResponse<K, V>)msg);
            }
        });
    }

    /**
     * @param nearNodeId Near node ID that initiated transaction.
     * @param locTx Optional local transaction.
     * @param req Near prepare request.
     * @return Future for transaction.
     */
    public IgniteFuture<GridCacheTxEx<K, V>> prepareTx(final UUID nearNodeId, @Nullable GridNearTxLocal<K, V> locTx,
        final GridNearTxPrepareRequest<K, V> req) {
        assert nearNodeId != null;
        assert req != null;

        if (locTx != null) {
            if (req.near()) {
                // Make sure not to provide Near entries to DHT cache.
                req.cloneEntries();

                return prepareNearTx(nearNodeId, req);
            }
            else
                return prepareColocatedTx(locTx, req);
        }
        else
            return prepareNearTx(nearNodeId, req);
    }

    /**
     * Prepares local colocated tx.
     *
     * @param locTx Local transaction.
     * @param req Near prepare request.
     * @return Prepare future.
     */
    private IgniteFuture<GridCacheTxEx<K, V>> prepareColocatedTx(final GridNearTxLocal<K, V> locTx,
        final GridNearTxPrepareRequest<K, V> req) {

        IgniteFuture<Object> fut = new GridFinishedFutureEx<>(); // TODO force preload keys.

        return new GridEmbeddedFuture<>(
            ctx.kernalContext(),
            fut,
            new C2<Object, Exception, IgniteFuture<GridCacheTxEx<K, V>>>() {
                @Override public IgniteFuture<GridCacheTxEx<K, V>> apply(Object o, Exception ex) {
                    if (ex != null)
                        throw new GridClosureException(ex);

                    IgniteFuture<GridCacheTxEx<K, V>> fut = locTx.prepareAsyncLocal(req.reads(), req.writes(),
                        locTx.transactionNodes(), req.last(), req.lastBackups());

                    if (locTx.isRollbackOnly())
                        locTx.rollbackAsync();

                    return fut;
                }
            },
            new C2<GridCacheTxEx<K, V>, Exception, GridCacheTxEx<K, V>>() {
                @Nullable @Override public GridCacheTxEx<K, V> apply(GridCacheTxEx<K, V> tx, Exception e) {
                    if (e != null) {
                        // tx can be null of exception occurred.
                        if (tx != null)
                            tx.setRollbackOnly(); // Just in case.

                        if (!(e instanceof GridCacheTxOptimisticException))
                            U.error(log, "Failed to prepare DHT transaction: " + tx, e);
                    }

                    return tx;
                }
            }
        );
    }

    /**
     * Prepares near transaction.
     *
     * @param nearNodeId Near node ID that initiated transaction.
     * @param req Near prepare request.
     * @return Prepare future.
     */
    private IgniteFuture<GridCacheTxEx<K, V>> prepareNearTx(final UUID nearNodeId,
        final GridNearTxPrepareRequest<K, V> req) {
        ClusterNode nearNode = ctx.node(nearNodeId);

        if (nearNode == null) {
            if (log.isDebugEnabled())
                log.debug("Received transaction request from node that left grid (will ignore): " + nearNodeId);

            return null;
        }

        try {
            for (GridCacheTxEntry<K, V> e : F.concat(false, req.reads(), req.writes()))
                e.unmarshal(ctx, false, ctx.deploy().globalLoader());
        }
        catch (GridException e) {
            return new GridFinishedFuture<>(ctx.kernalContext(), e);
        }

        GridDhtTxLocal<K, V> tx;

        GridCacheVersion mappedVer = ctx.tm().mappedVersion(req.version());

        if (mappedVer != null) {
            tx = ctx.tm().tx(mappedVer);

            if (tx == null)
                U.warn(log, "Missing local transaction for mapped near version [nearVer=" + req.version()
                    + ", mappedVer=" + mappedVer + ']');
        }
        else {
            tx = new GridDhtTxLocal<>(
                nearNode.id(),
                req.version(),
                req.futureId(),
                req.miniId(),
                req.threadId(),
                /*implicit*/false,
                /*implicit-single*/false,
                ctx,
                req.concurrency(),
                req.isolation(),
                req.timeout(),
                false,
                req.txSize(),
                req.groupLockKey(),
                req.partitionLock(),
                req.transactionNodes(),
                req.subjectId(),
                req.taskNameHash()
            );

            tx = ctx.tm().onCreated(tx);

            if (tx != null)
                tx.topologyVersion(req.topologyVersion());
            else
                U.warn(log, "Failed to create local transaction (was transaction rolled back?) [xid=" +
                    tx.xid() + ", req=" + req + ']');
        }

        if (tx != null) {
            IgniteFuture<GridCacheTxEx<K, V>> fut = tx.prepareAsync(req.reads(), req.writes(),
                req.dhtVersions(), req.messageId(), req.miniId(), req.transactionNodes(), req.last(),
                req.lastBackups());

            if (tx.isRollbackOnly()) {
                try {
                    tx.rollback();
                }
                catch (GridException e) {
                    U.error(log, "Failed to rollback transaction: " + tx, e);
                }
            }

            final GridDhtTxLocal<K, V> tx0 = tx;

            fut.listenAsync(new CI1<IgniteFuture<GridCacheTxEx<K, V>>>() {
                @Override public void apply(IgniteFuture<GridCacheTxEx<K, V>> txFut) {
                    try {
                        txFut.get();
                    }
                    catch (GridException e) {
                        tx0.setRollbackOnly(); // Just in case.

                        if (!(e instanceof GridCacheTxOptimisticException))
                            U.error(log, "Failed to prepare DHT transaction: " + tx0, e);
                    }
                }
            });

            return fut;
        }
        else
            return new GridFinishedFuture<>(ctx.kernalContext(), (GridCacheTxEx<K, V>)null);
    }

    /**
     * @param nodeId Node ID.
     * @param res Response.
     */
    private void processNearTxPrepareResponse(UUID nodeId, GridNearTxPrepareResponse<K, V> res) {
        GridNearTxPrepareFuture<K, V> fut = (GridNearTxPrepareFuture<K, V>)ctx.mvcc()
            .<GridCacheTxEx<K, V>>future(res.version(), res.futureId());

        if (fut == null) {
            if (log.isDebugEnabled())
                log.debug("Failed to find future for prepare response [sender=" + nodeId + ", res=" + res + ']');

            return;
        }

        fut.onResult(nodeId, res);
    }

    /**
     * @param nodeId Node ID.
     * @param res Response.
     */
    private void processNearTxFinishResponse(UUID nodeId, GridNearTxFinishResponse<K, V> res) {
        ctx.tm().onFinishedRemote(nodeId, res.threadId());

        GridNearTxFinishFuture<K, V> fut = (GridNearTxFinishFuture<K, V>)ctx.mvcc().<GridCacheTx>future(
            res.xid(), res.futureId());

        if (fut == null) {
            if (log.isDebugEnabled())
                log.debug("Failed to find future for finish response [sender=" + nodeId + ", res=" + res + ']');

            return;
        }

        fut.onResult(nodeId, res);
    }

    /**
     * @param nodeId Node ID.
     * @param res Response.
     */
    private void processDhtTxPrepareResponse(UUID nodeId, GridDhtTxPrepareResponse<K, V> res) {
        GridDhtTxPrepareFuture<K, V> fut = (GridDhtTxPrepareFuture<K, V>)ctx.mvcc().
            <GridCacheTxEx<K, V>>future(res.version(), res.futureId());

        if (fut == null) {
            if (log.isDebugEnabled())
                log.debug("Received response for unknown future (will ignore): " + res);

            return;
        }

        fut.onResult(nodeId, res);
    }

    /**
     * @param nodeId Node ID.
     * @param res Response.
     */
    private void processDhtTxFinishResponse(UUID nodeId, GridDhtTxFinishResponse<K, V> res) {
        assert nodeId != null;
        assert res != null;

        GridDhtTxFinishFuture<K, V> fut = (GridDhtTxFinishFuture<K, V>)ctx.mvcc().<GridCacheTx>future(res.xid(),
            res.futureId());

        if (fut == null) {
            if (log.isDebugEnabled())
                log.debug("Received response for unknown future (will ignore): " + res);

            return;
        }

        fut.onResult(nodeId, res);
    }

    /**
     * @param nodeId Node ID.
     * @param req Request.
     * @return Future.
     */
    @Nullable public IgniteFuture<GridCacheTx> processNearTxFinishRequest(UUID nodeId, GridNearTxFinishRequest<K, V> req) {
        return finish(nodeId, null, req);
    }

    /**
     * @param nodeId Node ID.
     * @param req Request.
     * @return Future.
     */
    @Nullable public IgniteFuture<GridCacheTx> finish(UUID nodeId, @Nullable GridNearTxLocal<K, V> locTx,
        GridNearTxFinishRequest<K, V> req) {
        assert locTx == null || locTx.nearLocallyMapped() || locTx.colocatedLocallyMapped();
        assert nodeId != null;
        assert req != null;

        if (log.isDebugEnabled())
            log.debug("Processing near tx finish request [nodeId=" + nodeId + ", req=" + req + "]");

        IgniteFuture<GridCacheTx> colocatedFinishFut = null;

        if (locTx != null && locTx.colocatedLocallyMapped())
            colocatedFinishFut = finishColocatedLocal(req.commit(), locTx);

        IgniteFuture<GridCacheTx> nearFinishFut = null;

        if (locTx == null || locTx.nearLocallyMapped()) {
            if (locTx != null)
                req.cloneEntries();

            nearFinishFut = finishDhtLocal(nodeId, locTx, req);
        }

        if (colocatedFinishFut != null && nearFinishFut != null) {
            GridCompoundFuture<GridCacheTx, GridCacheTx> res = new GridCompoundFuture<>(ctx.kernalContext());

            res.add(colocatedFinishFut);
            res.add(nearFinishFut);

            res.markInitialized();

            return res;
        }

        if (colocatedFinishFut != null)
            return colocatedFinishFut;

        return nearFinishFut;
    }

    /**
     * @param nodeId Node ID initiated commit.
     * @param locTx Optional local transaction.
     * @param req Finish request.
     * @return Finish future.
     */
    private IgniteFuture<GridCacheTx> finishDhtLocal(UUID nodeId, @Nullable GridNearTxLocal<K, V> locTx,
        GridNearTxFinishRequest<K, V> req) {
        GridCacheVersion dhtVer = ctx.tm().mappedVersion(req.version());

        GridDhtTxLocal<K, V> tx = null;

        if (dhtVer == null) {
            if (log.isDebugEnabled())
                log.debug("Received transaction finish request for unknown near version (was lock explicit?): " + req);
        }
        else
            tx = ctx.tm().tx(dhtVer);

        if (tx == null && !req.explicitLock()) {
            assert locTx == null : "DHT local tx should never be lost for near local tx: " + locTx;

            U.warn(log, "Received finish request for completed transaction (the message may be too late " +
                "and transaction could have been DGCed by now) [commit=" + req.commit() +
                ", xid=" + req.version() + ']');

            // Always send finish response.
            GridCacheMessage<K, V> res = new GridNearTxFinishResponse<>(req.version(), req.threadId(), req.futureId(),
                req.miniId(), new GridException("Transaction has been already completed."));

            try {
                ctx.io().send(nodeId, res);
            }
            catch (Throwable e) {
                // Double-check.
                if (ctx.discovery().node(nodeId) == null) {
                    if (log.isDebugEnabled())
                        log.debug("Node left while sending finish response [nodeId=" + nodeId + ", res=" + res +
                            ']');
                }
                else
                    U.error(log, "Failed to send finish response to node [nodeId=" + nodeId + ", " +
                        "res=" + res + ']', e);
            }

            return null;
        }

        try {
            if (req.commit()) {
                if (tx == null) {
                    // Create transaction and add entries.
                    tx = ctx.tm().onCreated(
                        new GridDhtTxLocal<>(
                            nodeId,
                            req.version(),
                            req.futureId(),
                            req.miniId(),
                            req.threadId(),
                            true,
                            false, /* we don't know, so assume false. */
                            ctx,
                            PESSIMISTIC,
                            READ_COMMITTED,
                            /*timeout */0,
                            req.explicitLock(),
                            req.txSize(),
                            req.groupLockKey(),
                            false,
                            null,
                            req.subjectId(),
                            req.taskNameHash()));

                    if (tx == null || !ctx.tm().onStarted(tx))
                        throw new GridCacheTxRollbackException("Attempt to start a completed transaction: " + req);

                    tx.topologyVersion(req.topologyVersion());
                }

                if (!tx.markFinalizing(USER_FINISH)) {
                    if (log.isDebugEnabled())
                        log.debug("Will not finish transaction (it is handled by another thread): " + tx);

                    return null;
                }

                tx.syncCommit(req.syncCommit());
                tx.nearFinishFutureId(req.futureId());
                tx.nearFinishMiniId(req.miniId());
                tx.recoveryWrites(req.recoveryWrites());

                Collection<GridCacheTxEntry<K, V>> writeEntries = req.writes();

                if (!F.isEmpty(writeEntries)) {
                    // In OPTIMISTIC mode, we get the values at PREPARE stage.
                    assert tx.concurrency() == PESSIMISTIC;

                    for (GridCacheTxEntry<K, V> entry : writeEntries)
                        tx.addEntry(req.messageId(), entry);
                }

                if (tx.pessimistic())
                    tx.prepare();

                IgniteFuture<GridCacheTx> commitFut = tx.commitAsync();

                // Only for error logging.
                commitFut.listenAsync(CU.errorLogger(log));

                return commitFut;
            }
            else {
                assert tx != null : "Transaction is null for near rollback request [nodeId=" +
                    nodeId + ", req=" + req + "]";

                tx.syncRollback(req.syncRollback());

                tx.nearFinishFutureId(req.futureId());
                tx.nearFinishMiniId(req.miniId());

                IgniteFuture<GridCacheTx> rollbackFut = tx.rollbackAsync();

                // Only for error logging.
                rollbackFut.listenAsync(CU.errorLogger(log));

                return rollbackFut;
            }
        }
        catch (Throwable e) {
            U.error(log, "Failed completing transaction [commit=" + req.commit() + ", tx=" + tx + ']', e);

            if (tx != null) {
                IgniteFuture<GridCacheTx> rollbackFut = tx.rollbackAsync();

                // Only for error logging.
                rollbackFut.listenAsync(CU.errorLogger(log));

                return rollbackFut;
            }

            return new GridFinishedFuture<>(ctx.kernalContext(), e);
        }
    }

    /**
     * @param commit Commit flag (rollback if {@code false}).
     * @param tx Transaction to commit.
     * @return Future.
     */
    public IgniteFuture<GridCacheTx> finishColocatedLocal(boolean commit, GridNearTxLocal<K, V> tx) {
        try {
            if (commit) {
                if (!tx.markFinalizing(USER_FINISH)) {
                    if (log.isDebugEnabled())
                        log.debug("Will not finish transaction (it is handled by another thread): " + tx);

                    return null;
                }

                return tx.commitAsyncLocal();
            }
            else
                return tx.rollbackAsyncLocal();
        }
        catch (Throwable e) {
            U.error(log, "Failed completing transaction [commit=" + commit + ", tx=" + tx + ']', e);

            if (tx != null)
                return tx.rollbackAsync();

            return new GridFinishedFuture<>(ctx.kernalContext(), e);
        }
    }

    /**
     * @param nodeId Sender node ID.
     * @param req Request.
     */
    protected final void processDhtTxPrepareRequest(UUID nodeId, GridDhtTxPrepareRequest<K, V> req) {
        assert nodeId != null;
        assert req != null;

        if (log.isDebugEnabled())
            log.debug("Processing dht tx prepare request [locNodeId=" + ctx.localNodeId() +
                ", nodeId=" + nodeId + ", req=" + req + ']');

        GridDhtTxRemote<K, V> dhtTx = null;
        GridNearTxRemote<K, V> nearTx = null;

        GridDhtTxPrepareResponse<K, V> res;

        try {
            res = new GridDhtTxPrepareResponse<>(req.version(), req.futureId(), req.miniId());

            // Start near transaction first.
            nearTx = !F.isEmpty(req.nearWrites()) ? startNearRemoteTx(ctx.deploy().globalLoader(), nodeId, req) : null;
            dhtTx = startRemoteTx(nodeId, req, res);

            // Set evicted keys from near transaction.
            if (nearTx != null)
                res.nearEvicted(nearTx.evicted());

            if (dhtTx != null && !F.isEmpty(dhtTx.invalidPartitions()))
                res.invalidPartitions(dhtTx.invalidPartitions());
        }
        catch (GridException e) {
            if (e instanceof GridCacheTxRollbackException)
                U.error(log, "Transaction was rolled back before prepare completed: " + dhtTx, e);
            else if (e instanceof GridCacheTxOptimisticException) {
                if (log.isDebugEnabled())
                    log.debug("Optimistic failure for remote transaction (will rollback): " + dhtTx);
            }
            else
                U.error(log, "Failed to process prepare request: " + req, e);

            if (nearTx != null)
                nearTx.rollback();

            if (dhtTx != null)
                dhtTx.rollback();

            res = new GridDhtTxPrepareResponse<>(req.version(), req.futureId(), req.miniId(), e);
        }

        try {
            // Reply back to sender.
            ctx.io().send(nodeId, res);
        }
        catch (GridException e) {
            if (e instanceof GridTopologyException) {
                if (log.isDebugEnabled())
                    log.debug("Failed to send tx response to remote node (node left grid) [node=" + nodeId +
                        ", xid=" + req.version());
            }
            else
                U.warn(log, "Failed to send tx response to remote node (will rollback transaction) [node=" + nodeId +
                    ", xid=" + req.version() + ", err=" +  e.getMessage() + ']');

            if (nearTx != null)
                nearTx.rollback();

            if (dhtTx != null)
                dhtTx.rollback();
        }
    }

    /**
     * @param nodeId Node ID.
     * @param req Request.
     */
    @SuppressWarnings({"unchecked"})
    protected final void processDhtTxFinishRequest(final UUID nodeId, final GridDhtTxFinishRequest<K, V> req) {
        assert nodeId != null;
        assert req != null;

        if (log.isDebugEnabled())
            log.debug("Processing dht tx finish request [nodeId=" + nodeId + ", req=" + req + ']');

        GridDhtTxRemote<K, V> dhtTx = ctx.tm().tx(req.version());
        GridNearTxRemote<K, V> nearTx = ctx.tm().nearTx(req.version());

        try {
            if (dhtTx == null && !F.isEmpty(req.writes()))
                dhtTx = startRemoteTxForFinish(nodeId, req);

            if (dhtTx != null) {
                dhtTx.syncCommit(req.syncCommit());
                dhtTx.syncRollback(req.syncRollback());
            }

            // One-phase commit transactions send finish requests to backup nodes.
            if (dhtTx != null && req.onePhaseCommit()) {
                dhtTx.onePhaseCommit(true);

                dhtTx.writeVersion(req.writeVersion());
            }

            if (nearTx == null && !F.isEmpty(req.nearWrites()) && req.groupLock())
                nearTx = startNearRemoteTxForFinish(nodeId, req);

            if (nearTx != null) {
                nearTx.syncCommit(req.syncCommit());
                nearTx.syncRollback(req.syncRollback());
            }
        }
        catch (GridCacheTxRollbackException e) {
            if (log.isDebugEnabled())
                log.debug("Received finish request for completed transaction (will ignore) [req=" + req + ", err=" +
                    e.getMessage() + ']');

            sendReply(nodeId, req);

            return;
        }
        catch (GridException e) {
            U.error(log, "Failed to start remote DHT and Near transactions (will invalidate transactions) [dhtTx=" +
                dhtTx + ", nearTx=" + nearTx + ']', e);

            if (dhtTx != null)
                dhtTx.invalidate(true);

            if (nearTx != null)
                nearTx.invalidate(true);
        }
        catch (GridDistributedLockCancelledException ignore) {
            U.warn(log, "Received commit request to cancelled lock (will invalidate transaction) [dhtTx=" +
                dhtTx + ", nearTx=" + nearTx + ']');

            if (dhtTx != null)
                dhtTx.invalidate(true);

            if (nearTx != null)
                nearTx.invalidate(true);
        }

        // Safety - local transaction will finish explicitly.
        if (nearTx != null && nearTx.local())
            nearTx = null;

        finish(nodeId, dhtTx, req, req.writes());

        if (nearTx != null)
            finish(nodeId, nearTx, req, req.nearWrites());

        sendReply(nodeId, req);
    }

    /**
     * @param nodeId Node ID.
     * @param tx Transaction.
     * @param req Request.
     * @param writes Writes.
     */
    protected void finish(
        UUID nodeId,
        GridCacheTxRemoteEx<K, V> tx,
        GridDhtTxFinishRequest<K, V> req,
        Collection<GridCacheTxEntry<K, V>> writes) {
        // We don't allow explicit locks for transactions and
        // therefore immediately return if transaction is null.
        // However, we may decide to relax this restriction in
        // future.
        if (tx == null) {
            if (req.commit())
                // Must be some long time duplicate, but we add it anyway.
                ctx.tm().addCommittedTx(req.version(), null);
            else
                ctx.tm().addRolledbackTx(req.version());

            if (log.isDebugEnabled())
                log.debug("Received finish request for non-existing transaction (added to completed set) " +
                    "[senderNodeId=" + nodeId + ", res=" + req + ']');

            return;
        }
        else if (log.isDebugEnabled())
            log.debug("Received finish request for transaction [senderNodeId=" + nodeId + ", req=" + req +
                ", tx=" + tx + ']');

        try {
            if (req.commit() || req.isSystemInvalidate()) {
                if (tx.commitVersion(req.commitVersion())) {
                    tx.invalidate(req.isInvalidate());
                    tx.systemInvalidate(req.isSystemInvalidate());

                    if (!F.isEmpty(writes)) {
                        // In OPTIMISTIC mode, we get the values at PREPARE stage.
                        assert tx.concurrency() == PESSIMISTIC;

                        for (GridCacheTxEntry<K, V> entry : writes) {
                            if (log.isDebugEnabled())
                                log.debug("Unmarshalled transaction entry from pessimistic transaction [key=" +
                                    entry.key() + ", value=" + entry.value() + ", tx=" + tx + ']');

                            if (!tx.setWriteValue(entry))
                                U.warn(log, "Received entry to commit that was not present in transaction [entry=" +
                                    entry + ", tx=" + tx + ']');
                        }
                    }

                    // Complete remote candidates.
                    tx.doneRemote(req.baseVersion(), null, null, null);

                    if (tx.pessimistic())
                        tx.prepare();

                    tx.commit();
                }
            }
            else {
                assert tx != null;

                tx.doneRemote(req.baseVersion(), null, null, null);

                tx.rollback();
            }
        }
        catch (Throwable e) {
            U.error(log, "Failed completing transaction [commit=" + req.commit() + ", tx=" + tx + ']', e);

            // Mark transaction for invalidate.
            tx.invalidate(true);
            tx.systemInvalidate(true);

            try {
                tx.commit();
            }
            catch (GridException ex) {
                U.error(log, "Failed to invalidate transaction: " + tx, ex);
            }
        }
    }

    /**
     * Sends tx finish response to remote node, if response is requested.
     *
     * @param nodeId Node id that originated finish request.
     * @param req Request.
     */
    protected void sendReply(UUID nodeId, GridDhtTxFinishRequest<K, V> req) {
        if (req.replyRequired()) {
            GridCacheMessage<K, V> res = new GridDhtTxFinishResponse<>(req.version(), req.futureId(), req.miniId());

            try {
                ctx.io().send(nodeId, res);
            }
            catch (Throwable e) {
                // Double-check.
                if (ctx.discovery().node(nodeId) == null) {
                    if (log.isDebugEnabled())
                        log.debug("Node left while sending finish response [nodeId=" + nodeId + ", res=" + res + ']');
                }
                else
                    U.error(log, "Failed to send finish response to node [nodeId=" + nodeId + ", res=" + res + ']', e);
            }
        }
    }

    /**
     * @param nodeId Node ID.
     * @param req Request.
     * @param res Response.
     * @return Remote transaction.
     * @throws GridException If failed.
     */
    @Nullable GridDhtTxRemote<K, V> startRemoteTx(UUID nodeId,
        GridDhtTxPrepareRequest<K, V> req,
        GridDhtTxPrepareResponse<K, V> res) throws GridException {
        if (!F.isEmpty(req.writes())) {
            GridDhtTxRemote<K, V> tx = ctx.tm().tx(req.version());

            assert F.isEmpty(req.candidatesByKey());

            if (tx == null) {
                tx = new GridDhtTxRemote<>(
                    req.nearNodeId(),
                    req.futureId(),
                    nodeId,
                    req.threadId(),
                    req.topologyVersion(),
                    req.version(),
                    req.commitVersion(),
                    req.concurrency(),
                    req.isolation(),
                    req.isInvalidate(),
                    req.timeout(),
                    ctx,
                    req.writes() != null ? Math.max(req.writes().size(), req.txSize()) : req.txSize(),
                    req.groupLockKey(),
                    req.nearXidVersion(),
                    req.transactionNodes(),
                    req.subjectId(),
                    req.taskNameHash());

                tx = ctx.tm().onCreated(tx);

                if (tx == null || !ctx.tm().onStarted(tx)) {
                    if (log.isDebugEnabled())
                        log.debug("Attempt to start a completed transaction (will ignore): " + tx);

                    return null;
                }
            }

            if (!tx.isSystemInvalidate() && !F.isEmpty(req.writes())) {
                int idx = 0;

                for (GridCacheTxEntry<K, V> entry : req.writes()) {
                    GridCacheContext<K, V> cacheCtx = entry.context();

                    tx.addWrite(entry, ctx.deploy().globalLoader());

                    if (isNearEnabled(cacheCtx) && req.invalidateNearEntry(idx))
                        invalidateNearEntry(cacheCtx, entry.key(), req.version());

                    try {
                        if (req.needPreloadKey(idx)) {
                            GridCacheEntryEx<K, V> cached = entry.cached();

                            if (cached == null)
                                cached = cacheCtx.cache().entryEx(entry.key(), req.topologyVersion());

                            GridCacheEntryInfo<K, V> info = cached.info();

                            if (info != null && !info.isNew() && !info.isDeleted())
                                res.addPreloadEntry(info);
                        }
                    }
                    catch (GridDhtInvalidPartitionException e) {
                        tx.addInvalidPartition(cacheCtx, e.partition());

                        tx.clearEntry(entry.txKey());
                    }

                    idx++;
                }
            }

            // Prepare prior to reordering, so the pending locks added
            // in prepare phase will get properly ordered as well.
            tx.prepare();

            if (req.last())
                tx.state(PREPARED);

            res.invalidPartitions(tx.invalidPartitions());

            if (tx.empty()) {
                tx.rollback();

                return null;
            }

            return tx;
        }

        return null;
    }

    /**
     * @param key Key
     * @param ver Version.
     * @throws GridException If invalidate failed.
     */
    private void invalidateNearEntry(GridCacheContext<K, V> cacheCtx, K key, GridCacheVersion ver)
        throws GridException {
        GridNearCacheAdapter<K, V> near = cacheCtx.isNear() ? cacheCtx.near() : cacheCtx.dht().near();

        GridCacheEntryEx<K, V> nearEntry = near.peekEx(key);

        if (nearEntry != null)
            nearEntry.invalidate(null, ver);
    }

    /**
     * Called while processing dht tx prepare request.
     *
     * @param ldr Loader.
     * @param nodeId Sender node ID.
     * @param req Request.
     * @return Remote transaction.
     * @throws GridException If failed.
     */
    @Nullable public GridNearTxRemote<K, V> startNearRemoteTx(ClassLoader ldr, UUID nodeId,
        GridDhtTxPrepareRequest<K, V> req) throws GridException {
        assert F.isEmpty(req.candidatesByKey());

        if (!F.isEmpty(req.nearWrites())) {
            GridNearTxRemote<K, V> tx = ctx.tm().nearTx(req.version());

            if (tx == null) {
                tx = new GridNearTxRemote<>(
                    ldr,
                    nodeId,
                    req.nearNodeId(),
                    req.threadId(),
                    req.version(),
                    req.commitVersion(),
                    req.concurrency(),
                    req.isolation(),
                    req.isInvalidate(),
                    req.timeout(),
                    req.nearWrites(),
                    ctx,
                    req.txSize(),
                    req.groupLockKey(),
                    req.subjectId(),
                    req.taskNameHash()
                );

                if (!tx.empty()) {
                    tx = ctx.tm().onCreated(tx);

                    if (tx == null || !ctx.tm().onStarted(tx))
                        throw new GridCacheTxRollbackException("Attempt to start a completed transaction: " + tx);
                }
            }
            else
                tx.addEntries(ldr, req.nearWrites());

            tx.ownedVersions(req.owned());

            // Prepare prior to reordering, so the pending locks added
            // in prepare phase will get properly ordered as well.
            tx.prepare();

            return tx;
        }

        return null;
    }

    /**
     * @param nodeId Primary node ID.
     * @param req Request.
     * @return Remote transaction.
     * @throws GridException If failed.
     * @throws GridDistributedLockCancelledException If lock has been cancelled.
     */
    @SuppressWarnings({"RedundantTypeArguments"})
    @Nullable GridDhtTxRemote<K, V> startRemoteTxForFinish(UUID nodeId, GridDhtTxFinishRequest<K, V> req)
        throws GridException, GridDistributedLockCancelledException {

        GridDhtTxRemote<K, V> tx = null;

        boolean marked = false;

        for (GridCacheTxEntry<K, V> txEntry : req.writes()) {
            GridDistributedCacheEntry<K, V> entry = null;

            GridCacheContext<K, V> cacheCtx = txEntry.context();

            while (true) {
                try {
                    int part = cacheCtx.affinity().partition(txEntry.key());

                    GridDhtLocalPartition<K, V> locPart = cacheCtx.topology().localPartition(part,
                        req.topologyVersion(), false);

                    // Handle implicit locks for pessimistic transactions.
                    if (tx == null)
                        tx = ctx.tm().tx(req.version());

                    if (locPart == null || !locPart.reserve()) {
                        if (log.isDebugEnabled())
                            log.debug("Local partition for given key is already evicted (will remove from tx) " +
                                "[key=" + txEntry.key() + ", part=" + part + ", locPart=" + locPart + ']');

                        if (tx != null)
                            tx.clearEntry(txEntry.txKey());

                        break;
                    }

                    try {
                        entry = (GridDistributedCacheEntry<K, V>)cacheCtx.cache().entryEx(txEntry.key(),
                            req.topologyVersion());

                        if (tx == null) {
                            tx = new GridDhtTxRemote<>(
                                req.nearNodeId(),
                                req.futureId(),
                                nodeId,
                                // We can pass null as nearXidVersion as transaction will be committed right away.
                                null,
                                req.threadId(),
                                req.topologyVersion(),
                                req.version(),
                                /*commitVer*/null,
                                PESSIMISTIC,
                                req.isolation(),
                                req.isInvalidate(),
                                0,
                                ctx,
                                req.txSize(),
                                req.groupLockKey(),
                                req.subjectId(),
                                req.taskNameHash());

                            tx = ctx.tm().onCreated(tx);

                            if (tx == null || !ctx.tm().onStarted(tx))
                                throw new GridCacheTxRollbackException("Failed to acquire lock " +
                                    "(transaction has been completed): " + req.version());
                        }

                        tx.addWrite(cacheCtx, txEntry.op(), txEntry.txKey(), txEntry.keyBytes(), txEntry.value(),
                            txEntry.valueBytes(), txEntry.transformClosures(), txEntry.drVersion());

                        if (!marked) {
                            if (tx.markFinalizing(USER_FINISH))
                                marked = true;
                            else {
                                tx.clearEntry(txEntry.txKey());

                                return null;
                            }
                        }

                        // Add remote candidate before reordering.
                        if (txEntry.explicitVersion() == null && !txEntry.groupLockEntry())
                            entry.addRemote(
                                req.nearNodeId(),
                                nodeId,
                                req.threadId(),
                                req.version(),
                                0,
                                /*tx*/true,
                                tx.implicitSingle(),
                                null
                            );

                        // Double-check in case if sender node left the grid.
                        if (ctx.discovery().node(req.nearNodeId()) == null) {
                            if (log.isDebugEnabled())
                                log.debug("Node requesting lock left grid (lock request will be ignored): " + req);

                            tx.rollback();

                            return null;
                        }

                        // Entry is legit.
                        break;
                    }
                    finally {
                        locPart.release();
                    }
                }
                catch (GridCacheEntryRemovedException ignored) {
                    assert entry.obsoleteVersion() != null : "Obsolete flag not set on removed entry: " +
                        entry;

                    if (log.isDebugEnabled())
                        log.debug("Received entry removed exception (will retry on renewed entry): " + entry);

                    tx.clearEntry(txEntry.txKey());

                    if (log.isDebugEnabled())
                        log.debug("Cleared removed entry from remote transaction (will retry) [entry=" +
                            entry + ", tx=" + tx + ']');
                }
                catch (GridDhtInvalidPartitionException p) {
                    if (log.isDebugEnabled())
                        log.debug("Received invalid partition (will clear entry from tx) [part=" + p + ", req=" +
                            req + ", txEntry=" + txEntry + ']');

                    if (tx != null)
                        tx.clearEntry(txEntry.txKey());

                    break;
                }
            }
        }

        if (tx != null && tx.empty()) {
            tx.rollback();

            return null;
        }

        return tx;
    }

    /**
     * @param nodeId Primary node ID.
     * @param req Request.
     * @return Remote transaction.
     * @throws GridException If failed.
     * @throws GridDistributedLockCancelledException If lock has been cancelled.
     */
    @SuppressWarnings({"RedundantTypeArguments"})
    @Nullable public GridNearTxRemote<K, V> startNearRemoteTxForFinish(UUID nodeId, GridDhtTxFinishRequest<K, V> req)
        throws GridException, GridDistributedLockCancelledException {
        assert req.groupLock();

        GridNearTxRemote<K, V> tx = null;

        ClassLoader ldr = ctx.deploy().globalLoader();

        if (ldr != null) {
            boolean marked = false;

            for (GridCacheTxEntry<K, V> txEntry : req.nearWrites()) {
                GridDistributedCacheEntry<K, V> entry = null;

                GridCacheContext<K, V> cacheCtx = txEntry.context();

                while (true) {
                    try {
                        entry = cacheCtx.dht().near().peekExx(txEntry.key());

                        if (entry != null) {
                            entry.keyBytes(txEntry.keyBytes());

                            // Handle implicit locks for pessimistic transactions.
                            tx = ctx.tm().tx(req.version());

                            if (tx == null) {
                                tx = new GridNearTxRemote<>(
                                    nodeId,
                                    req.nearNodeId(),
                                    // We can pass null as nearXidVer as transaction will be committed right away.
                                    null,
                                    req.threadId(),
                                    req.version(),
                                    null,
                                    PESSIMISTIC,
                                    req.isolation(),
                                    req.isInvalidate(),
                                    0,
                                    ctx,
                                    req.txSize(),
                                    req.groupLockKey(),
                                    req.subjectId(),
                                    req.taskNameHash());

                                tx = ctx.tm().onCreated(tx);

                                if (tx == null || !ctx.tm().onStarted(tx))
                                    throw new GridCacheTxRollbackException("Failed to acquire lock " +
                                        "(transaction has been completed): " + req.version());

                                if (!marked)
                                    marked = tx.markFinalizing(USER_FINISH);

                                if (!marked)
                                    return null;
                            }

                            if (tx.local())
                                return null;

                            if (!marked)
                                marked = tx.markFinalizing(USER_FINISH);

                            if (marked)
                                tx.addEntry(cacheCtx, txEntry.txKey(), txEntry.keyBytes(), txEntry.op(), txEntry.value(),
                                    txEntry.valueBytes(), txEntry.drVersion());
                            else
                                return null;

                            if (req.groupLock()) {
                                tx.markGroupLock();

                                if (!txEntry.groupLockEntry())
                                    tx.groupLockKey(txEntry.txKey());
                            }

                            // Add remote candidate before reordering.
                            if (txEntry.explicitVersion() == null && !txEntry.groupLockEntry())
                                entry.addRemote(
                                    req.nearNodeId(),
                                    nodeId,
                                    req.threadId(),
                                    req.version(),
                                    0,
                                    /*tx*/true,
                                    tx.implicitSingle(),
                                    null
                                );
                        }

                        // Double-check in case if sender node left the grid.
                        if (ctx.discovery().node(req.nearNodeId()) == null) {
                            if (log.isDebugEnabled())
                                log.debug("Node requesting lock left grid (lock request will be ignored): " + req);

                            if (tx != null)
                                tx.rollback();

                            return null;
                        }

                        // Entry is legit.
                        break;
                    }
                    catch (GridCacheEntryRemovedException ignored) {
                        assert entry.obsoleteVersion() != null : "Obsolete flag not set on removed entry: " +
                            entry;

                        if (log.isDebugEnabled())
                            log.debug("Received entry removed exception (will retry on renewed entry): " + entry);

                        if (tx != null) {
                            tx.clearEntry(txEntry.txKey());

                            if (log.isDebugEnabled())
                                log.debug("Cleared removed entry from remote transaction (will retry) [entry=" +
                                    entry + ", tx=" + tx + ']');
                        }

                        // Will retry in while loop.
                    }
                }
            }
        }
        else {
            String err = "Failed to acquire deployment class loader for message: " + req;

            U.warn(log, err);

            throw new GridException(err);
        }

        return tx;
    }
}
