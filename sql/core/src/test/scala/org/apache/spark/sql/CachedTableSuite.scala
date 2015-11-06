/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql

import org.apache.spark.sql.execution.PhysicalRDD

import scala.concurrent.duration._
import scala.language.postfixOps

import org.scalatest.concurrent.Eventually._

import org.apache.spark.Accumulators
import org.apache.spark.sql.columnar._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.test.{SQLTestUtils, SharedSQLContext}
import org.apache.spark.storage.{StorageLevel, RDDBlockId}

private case class BigData(s: String)

class CachedTableSuite extends QueryTest with SQLTestUtils with SharedSQLContext {
  import testImplicits._

  def rddIdOf(tableName: String): Int = {
    val executedPlan = ctx.table(tableName).queryExecution.executedPlan
    executedPlan.collect {
      case InMemoryColumnarTableScan(_, _, relation) =>
        relation.cachedColumnBuffers.id
      case _ =>
        fail(s"Table $tableName is not cached\n" + executedPlan)
    }.head
  }

  def isMaterialized(rddId: Int): Boolean = {
    ctx.sparkContext.env.blockManager.get(RDDBlockId(rddId, 0)).nonEmpty
  }

  test("withColumn doesn't invalidate cached dataframe") {
    var evalCount = 0
    val myUDF = udf((x: String) => { evalCount += 1; "result" })
    val df = Seq(("test", 1)).toDF("s", "i").select(myUDF($"s"))
    df.cache()

    df.collect()
    assert(evalCount === 1)

    df.collect()
    assert(evalCount === 1)

    val df2 = df.withColumn("newColumn", lit(1))
    df2.collect()

    // We should not reevaluate the cached dataframe
    assert(evalCount === 1)
  }

  test("cache temp table") {
    testData.select('key).registerTempTable("tempTable")
    assertCached(sql("SELECT COUNT(*) FROM tempTable"), 0)
    ctx.cacheTable("tempTable")
    assertCached(sql("SELECT COUNT(*) FROM tempTable"))
    ctx.uncacheTable("tempTable")
  }

  test("unpersist an uncached table will not raise exception") {
    assert(None == ctx.cacheManager.lookupCachedData(testData))
    testData.unpersist(blocking = true)
    assert(None == ctx.cacheManager.lookupCachedData(testData))
    testData.unpersist(blocking = false)
    assert(None == ctx.cacheManager.lookupCachedData(testData))
    testData.persist()
    assert(None != ctx.cacheManager.lookupCachedData(testData))
    testData.unpersist(blocking = true)
    assert(None == ctx.cacheManager.lookupCachedData(testData))
    testData.unpersist(blocking = false)
    assert(None == ctx.cacheManager.lookupCachedData(testData))
  }

  test("cache table as select") {
    sql("CACHE TABLE tempTable AS SELECT key FROM testData")
    assertCached(sql("SELECT COUNT(*) FROM tempTable"))
    ctx.uncacheTable("tempTable")
  }

  test("uncaching temp table") {
    testData.select('key).registerTempTable("tempTable1")
    testData.select('key).registerTempTable("tempTable2")
    ctx.cacheTable("tempTable1")

    assertCached(sql("SELECT COUNT(*) FROM tempTable1"))
    assertCached(sql("SELECT COUNT(*) FROM tempTable2"))

    // Is this valid?
    ctx.uncacheTable("tempTable2")

    // Should this be cached?
    assertCached(sql("SELECT COUNT(*) FROM tempTable1"), 0)
  }

  test("too big for memory") {
    val data = "*" * 1000
    ctx.sparkContext.parallelize(1 to 200000, 1).map(_ => BigData(data)).toDF()
      .registerTempTable("bigData")
    ctx.table("bigData").persist(StorageLevel.MEMORY_AND_DISK)
    assert(ctx.table("bigData").count() === 200000L)
    ctx.table("bigData").unpersist(blocking = true)
  }

  test("calling .cache() should use in-memory columnar caching") {
    ctx.table("testData").cache()
    assertCached(ctx.table("testData"))
    ctx.table("testData").unpersist(blocking = true)
  }

  test("calling .unpersist() should drop in-memory columnar cache") {
    ctx.table("testData").cache()
    ctx.table("testData").count()
    ctx.table("testData").unpersist(blocking = true)
    assertCached(ctx.table("testData"), 0)
  }

  test("isCached") {
    ctx.cacheTable("testData")

    assertCached(ctx.table("testData"))
    assert(ctx.table("testData").queryExecution.withCachedData match {
      case _: InMemoryRelation => true
      case _ => false
    })

    ctx.uncacheTable("testData")
    assert(!ctx.isCached("testData"))
    assert(ctx.table("testData").queryExecution.withCachedData match {
      case _: InMemoryRelation => false
      case _ => true
    })
  }

  test("SPARK-1669: cacheTable should be idempotent") {
    assume(!ctx.table("testData").logicalPlan.isInstanceOf[InMemoryRelation])

    ctx.cacheTable("testData")
    assertCached(ctx.table("testData"))

    assertResult(1, "InMemoryRelation not found, testData should have been cached") {
      ctx.table("testData").queryExecution.withCachedData.collect {
        case r: InMemoryRelation => r
      }.size
    }

    ctx.cacheTable("testData")
    assertResult(0, "Double InMemoryRelations found, cacheTable() is not idempotent") {
      ctx.table("testData").queryExecution.withCachedData.collect {
        case r @ InMemoryRelation(_, _, _, _, _: InMemoryColumnarTableScan, _) => r
      }.size
    }

    ctx.uncacheTable("testData")
  }

  test("read from cached table and uncache") {
    ctx.cacheTable("testData")
    checkAnswer(ctx.table("testData"), testData.collect().toSeq)
    assertCached(ctx.table("testData"))

    ctx.uncacheTable("testData")
    checkAnswer(ctx.table("testData"), testData.collect().toSeq)
    assertCached(ctx.table("testData"), 0)
  }

  test("correct error on uncache of non-cached table") {
    intercept[IllegalArgumentException] {
      ctx.uncacheTable("testData")
    }
  }

  test("SELECT star from cached table") {
    sql("SELECT * FROM testData").registerTempTable("selectStar")
    ctx.cacheTable("selectStar")
    checkAnswer(
      sql("SELECT * FROM selectStar WHERE key = 1"),
      Seq(Row(1, "1")))
    ctx.uncacheTable("selectStar")
  }

  test("Self-join cached") {
    val unCachedAnswer =
      sql("SELECT * FROM testData a JOIN testData b ON a.key = b.key").collect()
    ctx.cacheTable("testData")
    checkAnswer(
      sql("SELECT * FROM testData a JOIN testData b ON a.key = b.key"),
      unCachedAnswer.toSeq)
    ctx.uncacheTable("testData")
  }

  test("'CACHE TABLE' and 'UNCACHE TABLE' SQL statement") {
    sql("CACHE TABLE testData")
    assertCached(ctx.table("testData"))

    val rddId = rddIdOf("testData")
    assert(
      isMaterialized(rddId),
      "Eagerly cached in-memory table should have already been materialized")

    sql("UNCACHE TABLE testData")
    assert(!ctx.isCached("testData"), "Table 'testData' should not be cached")

    eventually(timeout(10 seconds)) {
      assert(!isMaterialized(rddId), "Uncached in-memory table should have been unpersisted")
    }
  }

  test("CACHE TABLE tableName AS SELECT * FROM anotherTable") {
    sql("CACHE TABLE testCacheTable AS SELECT * FROM testData")
    assertCached(ctx.table("testCacheTable"))

    val rddId = rddIdOf("testCacheTable")
    assert(
      isMaterialized(rddId),
      "Eagerly cached in-memory table should have already been materialized")

    ctx.uncacheTable("testCacheTable")
    eventually(timeout(10 seconds)) {
      assert(!isMaterialized(rddId), "Uncached in-memory table should have been unpersisted")
    }
  }

  test("CACHE TABLE tableName AS SELECT ...") {
    sql("CACHE TABLE testCacheTable AS SELECT key FROM testData LIMIT 10")
    assertCached(ctx.table("testCacheTable"))

    val rddId = rddIdOf("testCacheTable")
    assert(
      isMaterialized(rddId),
      "Eagerly cached in-memory table should have already been materialized")

    ctx.uncacheTable("testCacheTable")
    eventually(timeout(10 seconds)) {
      assert(!isMaterialized(rddId), "Uncached in-memory table should have been unpersisted")
    }
  }

  test("CACHE LAZY TABLE tableName") {
    sql("CACHE LAZY TABLE testData")
    assertCached(ctx.table("testData"))

    val rddId = rddIdOf("testData")
    assert(
      !isMaterialized(rddId),
      "Lazily cached in-memory table shouldn't be materialized eagerly")

    sql("SELECT COUNT(*) FROM testData").collect()
    assert(
      isMaterialized(rddId),
      "Lazily cached in-memory table should have been materialized")

    ctx.uncacheTable("testData")
    eventually(timeout(10 seconds)) {
      assert(!isMaterialized(rddId), "Uncached in-memory table should have been unpersisted")
    }
  }

  test("InMemoryRelation statistics") {
    sql("CACHE TABLE testData")
    ctx.table("testData").queryExecution.withCachedData.collect {
      case cached: InMemoryRelation =>
        val actualSizeInBytes = (1 to 100).map(i => INT.defaultSize + i.toString.length + 4).sum
        assert(cached.statistics.sizeInBytes === actualSizeInBytes)
    }
  }

  test("Drops temporary table") {
    testData.select('key).registerTempTable("t1")
    ctx.table("t1")
    ctx.dropTempTable("t1")
    assert(intercept[RuntimeException](ctx.table("t1")).getMessage.startsWith("Table Not Found"))
  }

  test("Drops cached temporary table") {
    testData.select('key).registerTempTable("t1")
    testData.select('key).registerTempTable("t2")
    ctx.cacheTable("t1")

    assert(ctx.isCached("t1"))
    assert(ctx.isCached("t2"))

    ctx.dropTempTable("t1")
    assert(intercept[RuntimeException](ctx.table("t1")).getMessage.startsWith("Table Not Found"))
    assert(!ctx.isCached("t2"))
  }

  test("Clear all cache") {
    sql("SELECT key FROM testData LIMIT 10").registerTempTable("t1")
    sql("SELECT key FROM testData LIMIT 5").registerTempTable("t2")
    ctx.cacheTable("t1")
    ctx.cacheTable("t2")
    ctx.clearCache()
    assert(ctx.cacheManager.isEmpty)

    sql("SELECT key FROM testData LIMIT 10").registerTempTable("t1")
    sql("SELECT key FROM testData LIMIT 5").registerTempTable("t2")
    ctx.cacheTable("t1")
    ctx.cacheTable("t2")
    sql("Clear CACHE")
    assert(ctx.cacheManager.isEmpty)
  }

  test("Clear accumulators when uncacheTable to prevent memory leaking") {
    sql("SELECT key FROM testData LIMIT 10").registerTempTable("t1")
    sql("SELECT key FROM testData LIMIT 5").registerTempTable("t2")

    ctx.cacheTable("t1")
    ctx.cacheTable("t2")

    sql("SELECT * FROM t1").count()
    sql("SELECT * FROM t2").count()
    sql("SELECT * FROM t1").count()
    sql("SELECT * FROM t2").count()

    Accumulators.synchronized {
      val accsSize = Accumulators.originals.size
      ctx.uncacheTable("t1")
      ctx.uncacheTable("t2")
      assert((accsSize - 2) == Accumulators.originals.size)
    }
  }

  test("SPARK-10327 Cache Table is not working while subquery has alias in its project list") {
    ctx.sparkContext.parallelize((1, 1) :: (2, 2) :: Nil)
      .toDF("key", "value").selectExpr("key", "value", "key+1").registerTempTable("abc")
    ctx.cacheTable("abc")

    val sparkPlan = sql(
      """select a.key, b.key, c.key from
        |abc a join abc b on a.key=b.key
        |join abc c on a.key=c.key""".stripMargin).queryExecution.sparkPlan

    assert(sparkPlan.collect { case e: InMemoryColumnarTableScan => e }.size === 3)
    assert(sparkPlan.collect { case e: PhysicalRDD => e }.size === 0)
  }

  /**
   * Verifies that the plan for `df` contains `expected` number of Exchange operators.
   */
  private def verifyNumExchanges(df: DataFrame, expected: Int): Unit = {
    assert(df.queryExecution.executedPlan.collect { case e: Exchange => e }.size == expected)
  }

  test("A cached table preserves the partitioning and ordering of its cached SparkPlan") {
    val table3x = testData.unionAll(testData).unionAll(testData)
    table3x.registerTempTable("testData3x")

    sql("SELECT key, value FROM testData3x ORDER BY key").registerTempTable("orderedTable")
    sqlContext.cacheTable("orderedTable")
    assertCached(sqlContext.table("orderedTable"))
    // Should not have an exchange as the query is already sorted on the group by key.
    verifyNumExchanges(sql("SELECT key, count(*) FROM orderedTable GROUP BY key"), 0)
    checkAnswer(
      sql("SELECT key, count(*) FROM orderedTable GROUP BY key ORDER BY key"),
      sql("SELECT key, count(*) FROM testData3x GROUP BY key ORDER BY key").collect())
    sqlContext.uncacheTable("orderedTable")
    sqlContext.dropTempTable("orderedTable")

    // Set up two tables distributed in the same way. Try this with the data distributed into
    // different number of partitions.
    for (numPartitions <- 1 until 10 by 4) {
      withTempTable("t1", "t2") {
        testData.repartition(numPartitions, $"key").registerTempTable("t1")
        testData2.repartition(numPartitions, $"a").registerTempTable("t2")
        sqlContext.cacheTable("t1")
        sqlContext.cacheTable("t2")

        // Joining them should result in no exchanges.
        verifyNumExchanges(sql("SELECT * FROM t1 t1 JOIN t2 t2 ON t1.key = t2.a"), 0)
        checkAnswer(sql("SELECT * FROM t1 t1 JOIN t2 t2 ON t1.key = t2.a"),
          sql("SELECT * FROM testData t1 JOIN testData2 t2 ON t1.key = t2.a"))

        // Grouping on the partition key should result in no exchanges
        verifyNumExchanges(sql("SELECT count(*) FROM t1 GROUP BY key"), 0)
        checkAnswer(sql("SELECT count(*) FROM t1 GROUP BY key"),
          sql("SELECT count(*) FROM testData GROUP BY key"))

        sqlContext.uncacheTable("t1")
        sqlContext.uncacheTable("t2")
      }
    }

    // Distribute the tables into non-matching number of partitions. Need to shuffle one side.
    withTempTable("t1", "t2") {
      testData.repartition(6, $"key").registerTempTable("t1")
      testData2.repartition(3, $"a").registerTempTable("t2")
      sqlContext.cacheTable("t1")
      sqlContext.cacheTable("t2")

      val query = sql("SELECT key, value, a, b FROM t1 t1 JOIN t2 t2 ON t1.key = t2.a")
      verifyNumExchanges(query, 1)
      assert(query.queryExecution.executedPlan.outputPartitioning.numPartitions === 6)
      checkAnswer(
        query,
        testData.join(testData2, $"key" === $"a").select($"key", $"value", $"a", $"b"))
      sqlContext.uncacheTable("t1")
      sqlContext.uncacheTable("t2")
    }

    // One side of join is not partitioned in the desired way. Need to shuffle one side.
    withTempTable("t1", "t2") {
      testData.repartition(6, $"value").registerTempTable("t1")
      testData2.repartition(6, $"a").registerTempTable("t2")
      sqlContext.cacheTable("t1")
      sqlContext.cacheTable("t2")

      val query = sql("SELECT key, value, a, b FROM t1 t1 JOIN t2 t2 ON t1.key = t2.a")
      verifyNumExchanges(query, 1)
      assert(query.queryExecution.executedPlan.outputPartitioning.numPartitions === 6)
      checkAnswer(
        query,
        testData.join(testData2, $"key" === $"a").select($"key", $"value", $"a", $"b"))
      sqlContext.uncacheTable("t1")
      sqlContext.uncacheTable("t2")
    }

    withTempTable("t1", "t2") {
      testData.repartition(6, $"value").registerTempTable("t1")
      testData2.repartition(12, $"a").registerTempTable("t2")
      sqlContext.cacheTable("t1")
      sqlContext.cacheTable("t2")

      val query = sql("SELECT key, value, a, b FROM t1 t1 JOIN t2 t2 ON t1.key = t2.a")
      verifyNumExchanges(query, 1)
      assert(query.queryExecution.executedPlan.outputPartitioning.numPartitions === 12)
      checkAnswer(
        query,
        testData.join(testData2, $"key" === $"a").select($"key", $"value", $"a", $"b"))
      sqlContext.uncacheTable("t1")
      sqlContext.uncacheTable("t2")
    }

    // One side of join is not partitioned in the desired way. Since the number of partitions of
    // the side that has already partitioned is smaller than the side that is not partitioned,
    // we shuffle both side.
    withTempTable("t1", "t2") {
      testData.repartition(6, $"value").registerTempTable("t1")
      testData2.repartition(3, $"a").registerTempTable("t2")
      sqlContext.cacheTable("t1")
      sqlContext.cacheTable("t2")

      val query = sql("SELECT key, value, a, b FROM t1 t1 JOIN t2 t2 ON t1.key = t2.a")
      verifyNumExchanges(query, 2)
      checkAnswer(
        query,
        testData.join(testData2, $"key" === $"a").select($"key", $"value", $"a", $"b"))
      sqlContext.uncacheTable("t1")
      sqlContext.uncacheTable("t2")
    }

    // repartition's column ordering is different from group by column ordering.
    // But they use the same set of columns.
    withTempTable("t1") {
      testData.repartition(6, $"value", $"key").registerTempTable("t1")
      sqlContext.cacheTable("t1")

      val query = sql("SELECT value, key from t1 group by key, value")
      verifyNumExchanges(query, 0)
      checkAnswer(
        query,
        testData.distinct().select($"value", $"key"))
      sqlContext.uncacheTable("t1")
    }

    // repartition's column ordering is different from join condition's column ordering.
    // We will still shuffle because hashcodes of a row depend on the column ordering.
    // If we do not shuffle, we may actually partition two tables in totally two different way.
    // See PartitioningSuite for more details.
    withTempTable("t1", "t2") {
      val df1 = testData
      df1.repartition(6, $"value", $"key").registerTempTable("t1")
      val df2 = testData2.select($"a", $"b".cast("string"))
      df2.repartition(6, $"a", $"b").registerTempTable("t2")
      sqlContext.cacheTable("t1")
      sqlContext.cacheTable("t2")

      val query =
        sql("SELECT key, value, a, b FROM t1 t1 JOIN t2 t2 ON t1.key = t2.a and t1.value = t2.b")
      verifyNumExchanges(query, 1)
      assert(query.queryExecution.executedPlan.outputPartitioning.numPartitions === 6)
      checkAnswer(
        query,
        df1.join(df2, $"key" === $"a" && $"value" === $"b").select($"key", $"value", $"a", $"b"))
      sqlContext.uncacheTable("t1")
      sqlContext.uncacheTable("t2")
    }
  }
}
