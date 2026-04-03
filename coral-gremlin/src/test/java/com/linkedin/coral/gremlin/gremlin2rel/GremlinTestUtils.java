/**
 * Copyright 2017-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.gremlin.gremlin2rel;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.ql.Driver;
import org.apache.hadoop.hive.ql.metadata.Hive;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.session.SessionState;

import com.linkedin.coral.common.HiveMscAdapter;


/**
 * Test utilities for Gremlin to Rel conversion tests.
 * Sets up HMS with graph tables (members and connections).
 * 
 * Note: The test uses "members" and "connections" as example tables,
 * but the converter is generic and can work with any vertex/edge tables.
 */
public class GremlinTestUtils {

  public static final String TEST_DIR = "coral-gremlin/target/tmp";

  // Example table names for testing - these can be any vertex/edge tables
  public static final String VERTEX_TABLE = "default.members";
  public static final String EDGE_TABLE = "default.connections";

  private static IMetaStoreClient msc;
  public static GremlinToRelConverter converter;

  /**
   * Setup HMS with graph tables for testing.
   * Creates two tables:
   * - members: represents vertices V() with columns (member, name, age, location)
   * - connections: represents edges E() with columns (src_member, dst_member, relation)
   */
  public static void setup() throws IOException, HiveException, MetaException {
    // Load Hive configuration from hive.xml (same as coral-hive tests)
    HiveConf conf = loadResourceHiveConf();

    // Start session and create tables
    SessionState.start(conf);
    Driver driver = new Driver(conf);
    try {
      driver.run("CREATE DATABASE IF NOT EXISTS default");
      // Drop tables first to ensure clean state
      driver.run("DROP TABLE IF EXISTS default.members");
      driver.run("DROP TABLE IF EXISTS default.connections");
      // Create tables with proper schema
      driver.run("CREATE TABLE default.members(" + "member STRING, name STRING, age INT, location STRING)");
      driver.run("CREATE TABLE default.connections(" + "src_member STRING, dst_member STRING, relation STRING)");
    } catch (Exception e) {
      throw new RuntimeException("Failed to create tables", e);
    }

    // Get metastore client AFTER tables exist
    msc = Hive.get(conf).getMSC();
    HiveMscAdapter mscAdapter = new HiveMscAdapter(msc);

    // Create converter with explicit column names including edge label column
    converter = new GremlinToRelConverter(mscAdapter, VERTEX_TABLE, EDGE_TABLE, "member", "src_member", "dst_member",
        "relation");
  }

  private static HiveConf loadResourceHiveConf() {
    InputStream hiveConfStream = GremlinTestUtils.class.getClassLoader().getResourceAsStream("hive.xml");
    HiveConf hiveConf = new HiveConf();
    hiveConf.addResource(hiveConfStream);
    hiveConf.set("mapreduce.framework.name", "local");
    hiveConf.set("_hive.hdfs.session.path", "/tmp/coral");
    hiveConf.set("_hive.local.session.path", "/tmp/coral");
    return hiveConf;
  }

  public static RelNode gremlinToRel(String gremlinQuery) {
    return converter.convertGremlin(gremlinQuery);
  }

  public static String relToStr(RelNode rel) {
    return RelOptUtil.toString(rel);
  }

  public static void cleanup() {
    File testDir = new File(TEST_DIR);
    if (testDir.exists()) {
      deleteDirectory(testDir);
    }
  }

  private static void deleteDirectory(File directory) {
    File[] files = directory.listFiles();
    if (files != null) {
      for (File file : files) {
        if (file.isDirectory()) {
          deleteDirectory(file);
        } else {
          file.delete();
        }
      }
    }
    directory.delete();
  }
}
