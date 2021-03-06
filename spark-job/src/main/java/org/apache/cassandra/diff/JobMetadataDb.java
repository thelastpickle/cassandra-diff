/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cassandra.diff;

import java.math.BigInteger;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datastax.driver.core.*;
import com.datastax.driver.core.utils.UUIDs;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

public class JobMetadataDb {
    private static final Logger logger = LoggerFactory.getLogger(JobMetadataDb.class);

    static class ProgressTracker {

        private final UUID jobId;
        private final int bucket;
        private final String startToken;
        private final String endToken;
        private final String keyspace;
        private Session session;

        private static PreparedStatement updateStmt;
        private static PreparedStatement mismatchStmt;
        private static PreparedStatement errorSummaryStmt;
        private static PreparedStatement errorDetailStmt;
        private static PreparedStatement updateCompleteStmt;

        public ProgressTracker(UUID jobId,
                               int bucket,
                               BigInteger startToken,
                               BigInteger endToken,
                               String keyspace,
                               Session session) {
            this.jobId = jobId;
            this.bucket = bucket;
            this.startToken = startToken.toString();
            this.endToken = endToken.toString();
            this.keyspace = keyspace;
            this.session = session;
        }

        /**
         * Runs on each executor to prepare statements shared across all instances
         */
        public static void initializeStatements(Session session, String keyspace) {
            if (updateStmt == null) {
                updateStmt = session.prepare(String.format("INSERT INTO %s.%s (" +
                                                           " job_id," +
                                                           " bucket," +
                                                           " table_name," +
                                                           " start_token," +
                                                           " end_token," +
                                                           " matched_partitions," +
                                                           " mismatched_partitions," +
                                                           " partitions_only_in_source," +
                                                           " partitions_only_in_target," +
                                                           " matched_rows," +
                                                           " matched_values," +
                                                           " mismatched_values," +
                                                           " skipped_partitions," +
                                                           " last_token )" +
                                                           "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                                                           keyspace, Schema.TASK_STATUS));
            }
            if (mismatchStmt == null) {
                mismatchStmt = session.prepare(String.format("INSERT INTO %s.%s (" +
                                                             " job_id," +
                                                             " bucket," +
                                                             " table_name," +
                                                             " mismatching_token," +
                                                             " mismatch_type )" +
                                                             "VALUES (?, ?, ?, ?, ?)",
                                                             keyspace, Schema.MISMATCHES));
            }
            if (updateCompleteStmt == null) {
                updateCompleteStmt = session.prepare(String.format("UPDATE %s.%s " +
                                                                   " SET completed = completed + 1" +
                                                                   " WHERE job_id = ? " +
                                                                   " AND bucket = ? " +
                                                                   " AND table_name = ? ",
                                                                   keyspace, Schema.JOB_STATUS))
                                            .setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM);
            }
            if (errorSummaryStmt == null) {
                errorSummaryStmt = session.prepare(String.format("INSERT INTO %s.%s (" +
                                                                 " job_id," +
                                                                 " bucket," +
                                                                 " table_name," +
                                                                 " start_token," +
                                                                 " end_token)" +
                                                                 " VALUES (?, ?, ?, ?, ?)",
                                                                 keyspace, Schema.ERROR_SUMMARY));
            }
            if (errorDetailStmt == null) {
                errorDetailStmt = session.prepare(String.format("INSERT INTO %s.%s (" +
                                                                " job_id," +
                                                                " bucket," +
                                                                " table_name," +
                                                                " start_token," +
                                                                " end_token," +
                                                                " error_token)" +
                                                                " VALUES (?, ?, ?, ?, ?, ?)",
                                                                keyspace, Schema.ERROR_DETAIL));
            }

        }

        public static void resetStatements()
        {
            updateStmt = null;
            mismatchStmt = null;
            errorSummaryStmt = null;
            errorDetailStmt = null;
            updateCompleteStmt = null;
        }

        /**
         *
         * @param table
         * @return
         */
        public DiffJob.TaskStatus getLastStatus(String table) {
            ResultSet rs = session.execute(String.format("SELECT last_token, " +
                                                         "       matched_partitions, " +
                                                         "       mismatched_partitions, " +
                                                         "       partitions_only_in_source, " +
                                                         "       partitions_only_in_target, " +
                                                         "       matched_rows," +
                                                         "       matched_values," +
                                                         "       mismatched_values," +
                                                         "       skipped_partitions " +
                                                         " FROM %s.%s " +
                                                         " WHERE job_id = ? " +
                                                         " AND   bucket = ? " +
                                                         " AND   table_name = ? " +
                                                         " AND   start_token = ? " +
                                                         " AND   end_token = ?",
                                                         keyspace, Schema.TASK_STATUS),
                                           jobId, bucket, table, startToken, endToken);
            Row row = rs.one();
            if (null == row)
                return DiffJob.TaskStatus.EMPTY;

            RangeStats stats = RangeStats.withValues(getOrDefaultLong(row, "matched_partitions"),
                                                     getOrDefaultLong(row, "mismatched_partitions"),
                                                     0L, // error counts are per-run and not persisted in the metadata db
                                                     getOrDefaultLong(row, "skipped_partitions"),
                                                     getOrDefaultLong(row, "partitions_only_in_source"),
                                                     getOrDefaultLong(row, "partitions_only_in_target"),
                                                     getOrDefaultLong(row, "matched_rows"),
                                                     getOrDefaultLong(row, "matched_values"),
                                                     getOrDefaultLong(row, "mismatched_values"));

            BigInteger lastToken = row.isNull("last_token") ? null : new BigInteger(row.getString("last_token"));
            return new DiffJob.TaskStatus(lastToken, stats);
        }

        /**
         *
         * @param table
         * @param diffStats
         * @param latestToken
         */
        public void updateStatus(String table, RangeStats diffStats, BigInteger latestToken) {
            session.execute(bindUpdateStatement(table, diffStats, latestToken));
        }

        public void recordMismatch(String table, MismatchType type, BigInteger token) {
            logger.info("Detected mismatch in table {}; partition with token {} is {}",
                        table, token, type == MismatchType.PARTITION_MISMATCH
                                      ? " different in source and target clusters"
                                      : type == MismatchType.ONLY_IN_SOURCE ? "only present in source cluster"
                                                                            : "only present in target cluster");
            session.execute(bindMismatchesStatement(table, token, type.name()));
        }

        /**
         *
         * @param table
         * @param token
         * @param error
         */
        public void recordError(String table, BigInteger token, Throwable error) {
            logger.error(String.format("Encountered error during partition comparison in table %s; " +
                                       "error for partition with token %s", table, token), error);
            BatchStatement batch = new BatchStatement();
            batch.add(bindErrorSummaryStatement(table));
            batch.add(bindErrorDetailStatement(table, token));
            batch.setIdempotent(true);
            session.execute(batch);
        }

        /**
         *
         * @param table
         * @param stats
         */
        public void finishTable(String table, RangeStats stats, boolean updateCompletedCount) {
            logger.info("Finishing range [{}, {}] for table {}", startToken, endToken, table);
            // first flush out the last status.
            session.execute(bindUpdateStatement(table, stats, endToken));
            // then update the count of completed tasks
            if (updateCompletedCount)
                session.execute(updateCompleteStmt.bind(jobId, bucket, table));
        }

        private Statement bindMismatchesStatement(String table, BigInteger token, String type) {
            return mismatchStmt.bind(jobId, bucket, table, token.toString(), type)
                               .setIdempotent(true);
        }

        private Statement bindErrorSummaryStatement(String table) {
            return errorSummaryStmt.bind(jobId, bucket, table, startToken, endToken)
                                   .setIdempotent(true);
        }

        private Statement bindErrorDetailStatement(String table, BigInteger errorToken) {
            return errorDetailStmt.bind(jobId, bucket, table, startToken, endToken, errorToken.toString())
                                  .setIdempotent(true);
        }

        private Statement bindUpdateStatement(String table, RangeStats stats, BigInteger token) {
           return bindUpdateStatement(table, stats, token.toString());
        }

        private Statement bindUpdateStatement(String table, RangeStats stats, String token) {
            // We don't persist the partition error count from RangeStats as errors
            // are likely to be transient and not data related, so we don't want to
            // accumulate them across runs.
            return updateStmt.bind(jobId,
                                   bucket,
                                   table,
                                   startToken,
                                   endToken,
                                   stats.getMatchedPartitions(),
                                   stats.getMismatchedPartitions(),
                                   stats.getOnlyInSource(),
                                   stats.getOnlyInTarget(),
                                   stats.getMatchedRows(),
                                   stats.getMatchedValues(),
                                   stats.getMismatchedValues(),
                                   stats.getSkippedPartitions(),
                                   token)
                             .setIdempotent(true);
        }

        private static long getOrDefaultLong(Row row, String column) {
            return (null == row || row.isNull(column)) ? 0L : row.getLong(column);
        }
    }

    static class JobLifeCycle {
        final Session session;
        final String keyspace;

        public JobLifeCycle(Session session, String metadataKeyspace) {
            this.session = session;
            this.keyspace = metadataKeyspace;
        }

        public DiffJob.Params getJobParams(UUID jobId) {
            ResultSet rs = session.execute(String.format("SELECT keyspace_name, " +
                                                         "       table_names," +
                                                         "       buckets," +
                                                         "       total_tasks " +
                                                         "FROM %s.%s " +
                                                         "WHERE job_id = ?",
                                                         keyspace, Schema.JOB_SUMMARY),
                                           jobId);
            Row row = rs.one();
            if (null == row)
                return null;

            return new DiffJob.Params(jobId,
                                      row.getString("keyspace_name"),
                                      row.getList("table_names", String.class),
                                      row.getInt("buckets"),
                                      row.getInt("total_tasks"));
        }


        // Runs on Driver to insert top level job info
        public void initializeJob(DiffJob.Params params,
                                  String sourceClusterName,
                                  String sourceClusterDesc,
                                  String targetClusterName,
                                  String targetClusterDesc) {

            logger.info("Initializing job status");
            // The job was previously run, so this could be a re-run to
            // mop up any failed splits so mark it in progress.
            ResultSet rs = session.execute(String.format("INSERT INTO %s.%s (job_id) VALUES (?) IF NOT EXISTS",
                                                         keyspace, Schema.RUNNING_JOBS),
                                           params.jobId);
            if (!rs.one().getBool("[applied]")) {
                logger.info("Aborting due to inability to mark job as running. " +
                            "Did a previous run of job id {} fail non-gracefully?",
                            params.jobId);
                throw new RuntimeException("Unable to mark job running, aborting");
            }

            UUID timeUUID = UUIDs.timeBased();
            DateTime startDateTime = new DateTime(UUIDs.unixTimestamp(timeUUID), DateTimeZone.UTC);

            rs = session.execute(String.format("INSERT INTO %s.%s (" +
                                               " job_id," +
                                               " job_start_time," +
                                               " buckets," +
                                               " keyspace_name," +
                                               " table_names," +
                                               " source_cluster_name," +
                                               " source_cluster_desc," +
                                               " target_cluster_name," +
                                               " target_cluster_desc," +
                                               " total_tasks)" +
                                               " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)" +
                                               " IF NOT EXISTS",
                                               keyspace, Schema.JOB_SUMMARY),
                                 params.jobId,
                                 timeUUID,
                                 params.buckets,
                                 params.keyspace,
                                 params.tables,
                                 sourceClusterName,
                                 sourceClusterDesc,
                                 targetClusterName,
                                 targetClusterDesc,
                                 params.tasks);

            // This is a brand new job, index its details including start time
            if (rs.one().getBool("[applied]")) {
                BatchStatement batch = new BatchStatement();
                batch.add(new SimpleStatement(String.format("INSERT INTO %s.%s (source_cluster_name, job_id) VALUES (?, ?)",
                                                            keyspace, Schema.SOURCE_CLUSTER_INDEX),
                                              sourceClusterName, params.jobId));
                batch.add(new SimpleStatement(String.format("INSERT INTO %s.%s (target_cluster_name, job_id) VALUES (?, ?)",
                                                            keyspace, Schema.TARGET_CLUSTER_INDEX),
                                              targetClusterName, params.jobId));
                batch.add(new SimpleStatement(String.format("INSERT INTO %s.%s (keyspace_name, job_id) VALUES (?, ?)",
                                                            keyspace, Schema.KEYSPACE_INDEX),
                                              keyspace, params.jobId));
                batch.add(new SimpleStatement(String.format("INSERT INTO %s.%s (job_start_date, job_start_hour, job_start_time, job_id) " +
                                                            "VALUES ('%s', ?, ?, ?)",
                                                            keyspace, Schema.JOB_START_INDEX, startDateTime.toString("yyyy-MM-dd")),
                                              startDateTime.getHourOfDay(), timeUUID, params.jobId));
                session.execute(batch);
            }
        }

        public void finalizeJob(UUID jobId, Map<String, RangeStats> results) {
            logger.info("Finalizing job status");

            markNotRunning(jobId);

            BatchStatement batch = new BatchStatement();
            for (Map.Entry<String, RangeStats> result : results.entrySet()) {
                String table = result.getKey();
                RangeStats stats = result.getValue();
                session.execute(String.format("INSERT INTO %s.%s (" +
                                              "  job_id," +
                                              "  table_name," +
                                              "  matched_partitions," +
                                              "  mismatched_partitions," +
                                              "  partitions_only_in_source," +
                                              "  partitions_only_in_target," +
                                              "  matched_rows," +
                                              "  matched_values," +
                                              "  mismatched_values," +
                                              "  skipped_partitions) " +
                                              "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                                              keyspace, Schema.JOB_RESULTS),
                                jobId,
                                table,
                                stats.getMatchedPartitions(),
                                stats.getMismatchedPartitions(),
                                stats.getOnlyInSource(),
                                stats.getOnlyInTarget(),
                                stats.getMatchedRows(),
                                stats.getMatchedValues(),
                                stats.getMismatchedValues(),
                                stats.getSkippedPartitions());
            }
            session.execute(batch);
        }


        public void markNotRunning(UUID jobId) {
            try
            {
                logger.info("Marking job {} as not running", jobId);

                ResultSet rs = session.execute(String.format("DELETE FROM %s.%s WHERE job_id = ? IF EXISTS",
                        keyspace, Schema.RUNNING_JOBS),
                        jobId);
                if (!rs.one().getBool("[applied]"))
                {
                    logger.warn("Non-fatal: Unable to mark job %s as not running, check logs for errors " +
                                    "during initialization as there may be no entry for this job in the {} table",
                            jobId, Schema.RUNNING_JOBS);
                }
            } catch (Exception e) {
                // Because this is called from another exception handler, we don't want to lose the original exception
                // just because we may not have been able to mark the job as not running. Just log here
                logger.error("Could not mark job {} as not running.", e);
            }
        }
    }

    static class Schema {

        public static final String TASK_STATUS = "task_status";
        private static final String TASK_STATUS_SCHEMA = "CREATE TABLE IF NOT EXISTS %s.%s (" +
                                                         " job_id uuid," +
                                                         " bucket int," +
                                                         " table_name text," +
                                                         " start_token varchar," +
                                                         " end_token varchar," +
                                                         " matched_partitions bigint," +
                                                         " mismatched_partitions bigint, " +
                                                         " partitions_only_in_source bigint," +
                                                         " partitions_only_in_target bigint," +
                                                         " matched_rows bigint," +
                                                         " matched_values bigint," +
                                                         " mismatched_values bigint," +
                                                         " skipped_partitions bigint," +
                                                         " last_token varchar," +
                                                         " PRIMARY KEY((job_id, bucket), table_name, start_token, end_token))" +
                                                         " WITH default_time_to_live = %s";

        public static final String JOB_SUMMARY = "job_summary";
        private static final String JOB_SUMMARY_SCHEMA = "CREATE TABLE IF NOT EXISTS %s.%s (" +
                                                         " job_id uuid," +
                                                         " job_start_time timeuuid," +
                                                         " buckets int," +
                                                         " keyspace_name text," +
                                                         " table_names frozen<list<text>>," +
                                                         " source_cluster_name text," +
                                                         " source_cluster_desc text," +
                                                         " target_cluster_name text," +
                                                         " target_cluster_desc text," +
                                                         " total_tasks int," +
                                                         " PRIMARY KEY(job_id))" +
                                                         " WITH default_time_to_live = %s";

        public static final String JOB_RESULTS = "job_results";
        private static final String JOB_RESULTS_SCHEMA = "CREATE TABLE IF NOT EXISTS %s.%s (" +
                                                         " job_id uuid," +
                                                         " table_name text," +
                                                         " matched_partitions bigint," +
                                                         " mismatched_partitions bigint," +
                                                         " partitions_only_in_source bigint," +
                                                         " partitions_only_in_target bigint," +
                                                         " matched_rows bigint," +
                                                         " matched_values bigint," +
                                                         " mismatched_values bigint," +
                                                         " skipped_partitions bigint," +
                                                         " PRIMARY KEY(job_id, table_name))" +
                                                         " WITH default_time_to_live = %s";

        public static final String JOB_STATUS = "job_status";
        private static final String JOB_STATUS_SCHEMA = "CREATE TABLE IF NOT EXISTS %s.%s (" +
                                                        " job_id uuid," +
                                                        " bucket int," +
                                                        " table_name text," +
                                                        " completed counter," +
                                                        " PRIMARY KEY ((job_id, bucket), table_name))";

        public static final String MISMATCHES = "mismatches";
        private static final String MISMATCHES_SCHEMA = "CREATE TABLE IF NOT EXISTS %s.%s (" +
                                                        " job_id uuid," +
                                                        " bucket int," +
                                                        " table_name text, " +
                                                        " mismatching_token varchar, " +
                                                        " mismatch_type text, " +
                                                        " PRIMARY KEY ((job_id, bucket), table_name, mismatching_token))" +
                                                        " WITH default_time_to_live = %s";

        public static final String ERROR_SUMMARY = "task_errors";
        private static final String ERROR_SUMMARY_SCHEMA = "CREATE TABLE IF NOT EXISTS %s.%s (" +
                                                           " job_id uuid," +
                                                           " bucket int," +
                                                           " table_name text," +
                                                           " start_token varchar," +
                                                           " end_token varchar," +
                                                           " PRIMARY KEY ((job_id, bucket), table_name, start_token, end_token))" +
                                                           " WITH default_time_to_live = %s";

        public static final String ERROR_DETAIL = "partition_errors";
        private static final String ERROR_DETAIL_SCHEMA = "CREATE TABLE IF NOT EXISTS %s.%s (" +
                                                          " job_id uuid," +
                                                          " bucket int," +
                                                          " table_name text," +
                                                          " start_token varchar," +
                                                          " end_token varchar," +
                                                          " error_token varchar," +
                                                          " PRIMARY KEY ((job_id, bucket, table_name, start_token, end_token), error_token))" +
                                                          " WITH default_time_to_live = %s";

        public static final String SOURCE_CLUSTER_INDEX = "source_cluster_index";
        private static final String SOURCE_CLUSTER_INDEX_SCHEMA = "CREATE TABLE IF NOT EXISTS %s.%s (" +
                                                                  " source_cluster_name text," +
                                                                  " job_id uuid," +
                                                                  " PRIMARY KEY (source_cluster_name, job_id))" +
                                                                  " WITH default_time_to_live = %s";

        public static final String TARGET_CLUSTER_INDEX = "target_cluster_index";
        private static final String TARGET_CLUSTER_INDEX_SCHEMA = "CREATE TABLE IF NOT EXISTS %s.%s (" +
                                                                  " target_cluster_name text," +
                                                                  " job_id uuid," +
                                                                  " PRIMARY KEY (target_cluster_name, job_id))" +
                                                                  " WITH default_time_to_live = %s";

        public static final String KEYSPACE_INDEX = "keyspace_index";
        private static final String KEYSPACE_INDEX_SCHEMA = "CREATE TABLE IF NOT EXISTS %s.%s (" +
                                                            " keyspace_name text," +
                                                            " job_id uuid," +
                                                            " PRIMARY KEY(keyspace_name, job_id))" +
                                                            " WITH default_time_to_live = %s";

        public static final String JOB_START_INDEX = "job_start_index";
        private static final String JOB_START_INDEX_SCHEMA = "CREATE TABLE IF NOT EXISTS %s.%s (" +
                                                             " job_start_date date," +
                                                             " job_start_hour int," +
                                                             " job_start_time timeuuid," +
                                                             " job_id uuid," +
                                                             " PRIMARY KEY ((job_start_date, job_start_hour), job_start_time))" +
                                                             " WITH default_time_to_live = %s";

        public static final String RUNNING_JOBS = "running_jobs";
        private static final String RUNNING_JOBS_SCHEMA = "CREATE TABLE IF NOT EXISTS %s.%s (" +
                                                          " job_id uuid," +
                                                          " PRIMARY KEY (job_id))" +
                                                          " WITH default_time_to_live = %s";

        private static final String KEYSPACE_SCHEMA = "CREATE KEYSPACE IF NOT EXISTS %s WITH REPLICATION = %s";


        public static void maybeInitialize(Session session, MetadataKeyspaceOptions options) {
            if (!options.should_init)
                return;

            logger.info("Initializing cassandradiff journal schema in \"{}\" keyspace", options.keyspace);
            session.execute(String.format(KEYSPACE_SCHEMA, options.keyspace, options.replication));
            session.execute(String.format(JOB_SUMMARY_SCHEMA, options.keyspace, JOB_SUMMARY, options.ttl));
            session.execute(String.format(JOB_STATUS_SCHEMA, options.keyspace, JOB_STATUS));
            session.execute(String.format(JOB_RESULTS_SCHEMA, options.keyspace, JOB_RESULTS, options.ttl));
            session.execute(String.format(TASK_STATUS_SCHEMA, options.keyspace, TASK_STATUS, options.ttl));
            session.execute(String.format(MISMATCHES_SCHEMA, options.keyspace, MISMATCHES, options.ttl));
            session.execute(String.format(ERROR_SUMMARY_SCHEMA, options.keyspace, ERROR_SUMMARY, options.ttl));
            session.execute(String.format(ERROR_DETAIL_SCHEMA, options.keyspace, ERROR_DETAIL, options.ttl));
            session.execute(String.format(SOURCE_CLUSTER_INDEX_SCHEMA, options.keyspace, SOURCE_CLUSTER_INDEX, options.ttl));
            session.execute(String.format(TARGET_CLUSTER_INDEX_SCHEMA, options.keyspace, TARGET_CLUSTER_INDEX, options.ttl));
            session.execute(String.format(KEYSPACE_INDEX_SCHEMA, options.keyspace, KEYSPACE_INDEX, options.ttl));
            session.execute(String.format(JOB_START_INDEX_SCHEMA, options.keyspace, JOB_START_INDEX, options.ttl));
            session.execute(String.format(RUNNING_JOBS_SCHEMA, options.keyspace, RUNNING_JOBS, options.ttl));
            logger.info("Schema initialized");
        }
    }
}

