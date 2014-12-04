/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.compute;

import org.gridgain.grid.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * This class defines a handler for asynchronous task execution. It's similar in design
 * to standard JDK {@link Future} interface but has improved and easier to use exception
 * hierarchy.
 * @param <R> Type of the task result returning from {@link GridComputeTask#reduce(List)} method.
 */
public interface GridComputeTaskFuture<R> extends IgniteFuture<R> {
    /**
     * {@inheritDoc}
     *
     * @throws GridComputeTaskTimeoutException If task execution timed out.
     */
    @Override public R get() throws GridException;

    /**
     * {@inheritDoc}
     *
     * @throws GridComputeTaskTimeoutException If task execution timed out.
     */
    @Override public R get(long timeout) throws GridException;

    /**
     * {@inheritDoc}
     *
     * @throws GridComputeTaskTimeoutException If task execution timed out.
     */
    @Override public R get(long timeout, TimeUnit unit) throws GridException;

    /**
     * Gets task session of execution grid task.
     *
     * @return Task session.
     */
    public GridComputeTaskSession getTaskSession();
}
