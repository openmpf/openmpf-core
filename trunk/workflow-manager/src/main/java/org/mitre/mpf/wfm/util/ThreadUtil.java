/******************************************************************************
 * NOTICE                                                                     *
 *                                                                            *
 * This software (or technical data) was produced for the U.S. Government     *
 * under contract, and is subject to the Rights in Data-General Clause        *
 * 52.227-14, Alt. IV (DEC 2007).                                             *
 *                                                                            *
 * Copyright 2020 The MITRE Corporation. All Rights Reserved.                 *
 ******************************************************************************/

/******************************************************************************
 * Copyright 2020 The MITRE Corporation                                       *
 *                                                                            *
 * Licensed under the Apache License, Version 2.0 (the "License");            *
 * you may not use this file except in compliance with the License.           *
 * You may obtain a copy of the License at                                    *
 *                                                                            *
 *    http://www.apache.org/licenses/LICENSE-2.0                              *
 *                                                                            *
 * Unless required by applicable law or agreed to in writing, software        *
 * distributed under the License is distributed on an "AS IS" BASIS,          *
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.   *
 * See the License for the specific language governing permissions and        *
 * limitations under the License.                                             *
 ******************************************************************************/


package org.mitre.mpf.wfm.util;

import java.util.concurrent.*;

public class ThreadUtil {

    private static ExecutorService THREAD_POOL = Executors.newCachedThreadPool();

    private ThreadUtil() {
    }


    public static synchronized CustomCompletableFuture<Void> runAsync(ThrowingRunnable task) {
        return callAsync(() -> {
            task.run();
            return null;
        });
    }

    public static synchronized CustomCompletableFuture<Void> runAsync(long delay, TimeUnit unit,
                                                                      ThrowingRunnable task) {
        return callAsync(delay, unit, () -> {
            task.run();
            return null;
        });
    }


    public static synchronized <T> CustomCompletableFuture<T> callAsync(Callable<T> task) {
        return new CustomCompletableFuture<>(task, THREAD_POOL);
    }


    public static synchronized <T> CustomCompletableFuture<T> callAsync(long delay, TimeUnit unit, Callable<T> task) {
        var future = ThreadUtil.<T>newFuture();

        return (CustomCompletableFuture<T>)  future.completeAsync(() -> {
            try {
                return task.call();
            }
            catch (Exception e) {
                throw new CompletionException(e);
            }

        }, delayedExecutor(delay, unit));
    }


    public static synchronized Executor delayedExecutor(long delay, TimeUnit unit) {
        return CompletableFuture.delayedExecutor(delay, unit, THREAD_POOL);
    }


    public static <T> CustomCompletableFuture<T> newFuture() {
        return new CustomCompletableFuture<>(THREAD_POOL);
    }

    public static <T> CustomCompletableFuture<T> completedFuture(T value) {
        var future = ThreadUtil.<T>newFuture();
        future.complete(value);
        return future;
    }


    public static <T> CustomCompletableFuture<T> failedFuture(Throwable ex) {
        var future = ThreadUtil.<T>newFuture();
        future.completeExceptionally(ex);
        return future;
    }


    @FunctionalInterface
    public interface ThrowingRunnable {
        public void run() throws Exception;
    }


    public static synchronized void start() {
        if (THREAD_POOL.isShutdown()) {
            THREAD_POOL = Executors.newCachedThreadPool();
        }
    }

    public static synchronized void shutdown() {
        try {
            THREAD_POOL.shutdown();
            THREAD_POOL.awaitTermination(1, TimeUnit.SECONDS);
        }
        catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            // No need to do anything, already about to exit.
        }
        finally {
            THREAD_POOL.shutdownNow();
        }
    }


    // Regular CompletableFuture runs tasks on ForkJoinPool.commonPool() by default.
    // ForkJoinPool.commonPool() is for non-blocking CPU bound tasks.
    // This version runs tasks on ThreadUtil.THREAD_POOL by default.
    public static class CustomCompletableFuture<T> extends CompletableFuture<T> {

        private final ExecutorService _defaultExecutor;

        private final Future<?> _cancellableFuture;

        public CustomCompletableFuture(Callable<T> task, ExecutorService defaultExecutor) {
            _defaultExecutor = defaultExecutor;

            _cancellableFuture = _defaultExecutor.submit(() -> {
                try {
                    T result = task.call();
                    complete(result);
                    return result;
                }
                catch (Throwable e) {
                    completeExceptionally(e);
                    throw e;
                }
            });
        }

        public CustomCompletableFuture(ExecutorService defaultExecutor) {
            _defaultExecutor = defaultExecutor;
            _cancellableFuture = null;
        }

        private CustomCompletableFuture(Future<?> cancellableFuture, ExecutorService defaultExecutor) {
            _cancellableFuture = cancellableFuture;
            _defaultExecutor = defaultExecutor;
        }


        @Override
        public Executor defaultExecutor() {
            return _defaultExecutor;
        }

        @Override
        public <U> CompletableFuture<U> newIncompleteFuture() {
            return new CustomCompletableFuture<U>(_cancellableFuture, _defaultExecutor);
        }


        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (_cancellableFuture != null) {
                _cancellableFuture.cancel(mayInterruptIfRunning);
            }
            return super.cancel(mayInterruptIfRunning);
        }
    }
}
