/*
 * Copyright 2016 Mesosphere
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.mesosphere.dcos.cassandra.executor.tasks;

import com.mesosphere.dcos.cassandra.common.tasks.backup.BackupRestoreContext;
import com.mesosphere.dcos.cassandra.common.tasks.backup.BackupUploadTask;
import com.mesosphere.dcos.cassandra.executor.CassandraDaemonProcess;
import com.mesosphere.dcos.cassandra.executor.backup.BackupStorageDriver;
import org.apache.mesos.ExecutorDriver;
import org.apache.mesos.Protos;
import org.apache.mesos.executor.ExecutorTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.Future;

/**
 * UploadSnapshot implements UploadSnapshotTask by delegating the upload of
 * the snapshot to a BackupStorageDriver implementation and the clearing of
 * the local snapshot to CassandraDaemonProcess.
 */
public class UploadSnapshot implements ExecutorTask {
    private static final Logger LOGGER = LoggerFactory.getLogger(
        UploadSnapshot.class);
    private final CassandraDaemonProcess daemon;
    private final ExecutorDriver driver;
    private final BackupRestoreContext context;
    private final BackupUploadTask cassandraTask;
    private final BackupStorageDriver backupStorageDriver;

    /**
     * Constructs a new UploadSnapshot
     *
     * @param driver              The ExecutorDriver used to send task status.
     * @param daemon              The CassandraDaemonProcess used clean the local snapshot.
     * @param cassandraTask       The BackUploadTask that will be executed.
     * @param backupStorageDriver The BackupStorageDriver used to upload the
     *                            snapshot.
     */
    public UploadSnapshot(
        ExecutorDriver driver,
        CassandraDaemonProcess daemon,
        BackupUploadTask cassandraTask,
        BackupStorageDriver backupStorageDriver) {
        this.daemon = daemon;
        this.driver = driver;
        this.cassandraTask = cassandraTask;
        this.backupStorageDriver = backupStorageDriver;
        context = cassandraTask.getBackupRestoreContext();
    }

    private void sendStatus(ExecutorDriver driver,
                            Protos.TaskState state,
                            String message) {
        Protos.TaskStatus status = cassandraTask.createStatus(state,
            Optional.of(message)).getTaskStatus();
        driver.sendStatusUpdate(status);
    }

    @Override
    public void run() {
        try {
            // Send TASK_RUNNING
            sendStatus(driver, Protos.TaskState.TASK_RUNNING,
                "Started uploading snapshots");

            // Upload snapshots to external location.
            backupStorageDriver.upload(context);

            // Once we have uploaded all existing snapshots, let's clear on-disk snapshots
            daemon.clearSnapShot(context.getName());

            // Send TASK_FINISHED
            sendStatus(driver, Protos.TaskState.TASK_FINISHED,
                "Finished uploading snapshots");
        } catch (Throwable t) {
            LOGGER.error("Upload snapshot failed", t);
            sendStatus(driver, Protos.TaskState.TASK_FAILED, t.getMessage());
        }
    }

    @Override
    public void stop(Future<?> future) {
        future.cancel(true);
    }
}
