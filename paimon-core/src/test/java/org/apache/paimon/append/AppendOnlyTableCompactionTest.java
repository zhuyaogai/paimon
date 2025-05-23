/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.append;

import org.apache.paimon.data.BinaryString;
import org.apache.paimon.data.GenericRow;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.fs.FileIO;
import org.apache.paimon.fs.local.LocalFileIO;
import org.apache.paimon.io.DataFileMeta;
import org.apache.paimon.operation.BaseAppendFileStoreWrite;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.schema.SchemaManager;
import org.apache.paimon.schema.TableSchema;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.FileStoreTableFactory;
import org.apache.paimon.table.sink.CommitMessage;
import org.apache.paimon.table.sink.CommitMessageImpl;
import org.apache.paimon.table.sink.StreamTableWrite;
import org.apache.paimon.table.sink.TableCommitImpl;
import org.apache.paimon.types.DataTypes;
import org.apache.paimon.utils.SnapshotManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static java.util.Collections.singletonMap;
import static org.apache.paimon.SnapshotTest.newSnapshotManager;
import static org.assertj.core.api.Assertions.assertThat;

/** Test for append table compaction. */
public class AppendOnlyTableCompactionTest {

    private static final Random random = new Random();

    @TempDir private Path tempDir;
    private FileStoreTable appendOnlyFileStoreTable;
    private SnapshotManager snapshotManager;
    private AppendCompactCoordinator coordinator;
    private BaseAppendFileStoreWrite write;
    private org.apache.paimon.fs.Path path;
    private TableSchema tableSchema;
    private final String commitUser = UUID.randomUUID().toString();

    @BeforeEach
    public void createNegativeAppendOnlyTable() throws Exception {
        FileIO fileIO = new LocalFileIO();
        path = new org.apache.paimon.fs.Path(tempDir.toString());
        tableSchema = new SchemaManager(fileIO, path).createTable(schema());
        snapshotManager = newSnapshotManager(fileIO, path);
        recreate();
    }

    private void recreate() {
        appendOnlyFileStoreTable =
                FileStoreTableFactory.create(
                        LocalFileIO.create(),
                        new org.apache.paimon.fs.Path(tempDir.toString()),
                        tableSchema);
        coordinator = new AppendCompactCoordinator(appendOnlyFileStoreTable, true);
        write = (BaseAppendFileStoreWrite) appendOnlyFileStoreTable.store().newWrite(commitUser);
    }

    @Test
    public void noCompaction() throws Exception {
        List<CommitMessage> messages = writeCommit(10);

        messages.forEach(
                message ->
                        assertThat(((CommitMessageImpl) message).compactIncrement().isEmpty())
                                .isTrue());
    }

    @Test
    public void compactionTaskTest() throws Exception {
        // commit 11 files
        List<CommitMessage> messages = writeCommit(11);
        commit(messages);

        // first compact, six files left after commit compact and update restored files
        // test run method invoke scan and compactPlan
        List<AppendCompactTask> tasks = coordinator.run();
        assertThat(tasks.size()).isEqualTo(1);
        AppendCompactTask task = tasks.get(0);
        assertThat(task.compactBefore().size()).isEqualTo(11);
        List<CommitMessage> result = doCompact(tasks);
        assertThat(result.size()).isEqualTo(1);
        commit(result);
        coordinator.scan();
        assertThat(coordinator.listRestoredFiles().size()).isEqualTo(1);
        messages = writeCommit(11);
        commit(messages);

        // second compact, only one file left after updateRestored
        coordinator.scan();
        assertThat(coordinator.listRestoredFiles().size()).isEqualTo(12);
        tasks = coordinator.compactPlan();
        assertThat(tasks.size()).isEqualTo(1);
        // before update, zero file left
        assertThat(coordinator.listRestoredFiles().size()).isEqualTo(0);
        commit(doCompact(tasks));
        coordinator.scan();
        // one file is loaded from delta
        List<DataFileMeta> last = new ArrayList<>(coordinator.listRestoredFiles());
        assertThat(last.size()).isEqualTo(1);
        assertThat(last.get(0).rowCount()).isEqualTo(22);
    }

    @Test
    public void testScanSkipBigFiles() throws Exception {
        List<CommitMessage> messages = writeCommit(11);
        commit(messages);
        tableSchema = tableSchema.copy(singletonMap("target-file-size", "1 b"));
        recreate();
        assertThat(coordinator.filesIterator().next()).isNull();
    }

    @Test
    public void testCompactionLot() throws Exception {
        // test continuous compaction
        assertThat(snapshotManager.latestSnapshotId()).isNull();

        long count = 0;
        for (int i = 90; i < 100; i++) {
            count += i;
            commit(writeCommit(i));
            List<AppendCompactTask> tasks = coordinator.run();
            assertThat(tasks).hasSizeGreaterThan(0);
            commit(doCompact(tasks));
            // scan the file generated itself
            assertThat(coordinator.scan()).isTrue();
            assertThat(
                            coordinator.listRestoredFiles().stream()
                                    .map(DataFileMeta::rowCount)
                                    .reduce(Long::sum)
                                    .get())
                    .isEqualTo(count);
        }

        assertThat(appendOnlyFileStoreTable.store().newScan().plan().files().size())
                .isEqualTo(coordinator.listRestoredFiles().size());

        List<AppendCompactTask> tasks = coordinator.run();
        while (tasks.size() != 0) {
            commit(doCompact(tasks));
            tasks = coordinator.run();
        }

        int remainedSize = appendOnlyFileStoreTable.store().newScan().plan().files().size();
        assertThat(remainedSize).isEqualTo(coordinator.listRestoredFiles().size()).isEqualTo(1);
    }

    private static Schema schema() {
        Schema.Builder schemaBuilder = Schema.newBuilder();
        schemaBuilder.column("f0", DataTypes.INT());
        schemaBuilder.column("f1", DataTypes.STRING());
        schemaBuilder.column("f2", DataTypes.STRING());
        schemaBuilder.column("f3", DataTypes.STRING());
        schemaBuilder.option("compaction.min.file-num", "3");
        schemaBuilder.option("bucket", "-1");
        return schemaBuilder.build();
    }

    private void commit(List<CommitMessage> messages) throws Exception {
        TableCommitImpl commit = appendOnlyFileStoreTable.newCommit(commitUser);
        commit.commit(messages);
        commit.close();
    }

    private List<CommitMessage> writeCommit(int number) throws Exception {
        List<CommitMessage> messages = new ArrayList<>();
        StreamTableWrite writer = appendOnlyFileStoreTable.newStreamWriteBuilder().newWrite();
        for (int i = 0; i < number; i++) {
            writer.write(randomRow());
            messages.addAll(writer.prepareCommit(true, i));
        }
        return messages;
    }

    private List<CommitMessage> doCompact(List<AppendCompactTask> tasks) throws Exception {
        List<CommitMessage> result = new ArrayList<>();
        for (AppendCompactTask task : tasks) {
            result.add(task.doCompact(appendOnlyFileStoreTable, write));
        }
        return result;
    }

    private InternalRow randomRow() {
        return GenericRow.of(
                random.nextInt(100),
                BinaryString.fromString("A" + random.nextInt(100)),
                BinaryString.fromString("B" + random.nextInt(100)),
                BinaryString.fromString("C" + random.nextInt(100)));
    }
}
