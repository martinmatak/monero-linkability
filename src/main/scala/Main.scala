package main.scala

import java.io.PrintWriter
import java.util

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.types.{LongType, StructField, StructType}
import org.apache.spark.sql.Row

import scala.collection.mutable


object Main {

  // https://stackoverflow.com/questions/30304810/dataframe-ified-zipwithindex, second answer
  def dfZipWithIndex(
                      df: DataFrame,
                      offset: Int = 1,
                      colName: String = "id",
                      inFront: Boolean = true
                    ) : DataFrame = {
    df.sqlContext.createDataFrame(
      df.rdd.zipWithIndex.map(ln =>
        Row.fromSeq(
          (if (inFront) Seq(ln._2 + offset) else Seq())
            ++ ln._1.toSeq ++
            (if (inFront) Seq() else Seq(ln._2 + offset))
        )
      ),
      StructType(
        (if (inFront) Array(StructField(colName,LongType,false)) else Array[StructField]())
          ++ df.schema.fields ++
          (if (inFront) Array[StructField]() else Array(StructField(colName,LongType,false)))
      )
    )
  }

  def main(args: Array[String]) {

    val spark = org.apache.spark.sql.SparkSession.builder
      .appName("Monero Linkability")
      .getOrCreate

    // used for SQLSpark API (DataFrame)
    import spark.implicits._


    try {
      val bucket_name = args(0)
      val input_fn = args(1)

      // Load the lines of text
      val lines = spark.read.format("csv")
        .option("header", "true")
        .load("gs://" + bucket_name + "/" + input_fn)
        .toDF("key_image", "coin_candidate")

      //      optimization: numbers as id instead of string hashes
      val key_image_id = dfZipWithIndex(lines.select("key_image").distinct(), colName = "key_image_id")
        .withColumnRenamed("key_image", "key_image_join")
      val candidate_id = dfZipWithIndex(lines.select("coin_candidate").distinct(), colName = "coin_candidate_id")
        .withColumnRenamed("coin_candidate", "coin_candidate_join")

      // (key_image, candidate1)
      // (key_image, candidate2)
      val key_image_candidate = lines
        .join(key_image_id, $"key_image" === $"key_image_join")
        .join(candidate_id, $"coin_candidate" === $"coin_candidate_join")
        .select("key_image_id", "coin_candidate_id")


      val tx_input = key_image_candidate.map(row => (row(0).asInstanceOf[Long], row(1).asInstanceOf[Long])).rdd


      // (key_image, [candidate1, candidate2, ...])
      val tx_inputs = tx_input
        .groupByKey()
        .mapValues(iterable => iterable.toSet).collectAsMap()
      // (input1, false)
      val input_tx = tx_input.map {
        case (tx, input) =>
          input
      }.distinct()
        .map {
          case (input) =>
            (input, -1L)
        }

      // that's it - nothing more can be done with those RDDs actually ..

      val input_tx_map = collection.mutable.Map(input_tx.collectAsMap().toSeq: _*)
      val tx_inputs_map = new util.HashMap[Long, util.HashSet[Long]]()

      for ((tx, inputs) <- tx_inputs) {
        tx_inputs_map.put(tx, new util.HashSet[Long]())
        for (input <- inputs) {
          tx_inputs_map.get(tx).add(input)
        }
      }


      var numOfIterations = 0
      var changeHappened = false
      do {
        numOfIterations += 1
        var keysToRemove = new collection.mutable.HashSet[Long]()
        var inputsToRemove = new collection.mutable.HashSet[Long]()
        changeHappened = false
        val iterator = tx_inputs_map.entrySet.iterator()
        while (iterator.hasNext) {
          val pair = iterator.next()
          var tx = pair.getKey
          val inputs = pair.getValue
          if (inputs.size() == 1) {
            val input = inputs.iterator().next()
            input_tx_map.put(input, tx)
            inputsToRemove += input
            keysToRemove += tx
            changeHappened = true
          }
        }
        // remove determined key images
        for (key <- keysToRemove) {
          tx_inputs_map.remove(key)
        }
        // remove used candidates
        val value_iterator = tx_inputs_map.values().iterator()
        while (value_iterator.hasNext) {
          val values = value_iterator.next()
          for (input <- inputsToRemove) {
            values.remove(input)
          }
        }

      } while (changeHappened)

      val tx_realInput = new mutable.HashMap[Long, Long]()
      //prepare result
      for ((input, tx) <- input_tx_map) {
        if (tx != -1L) {
          tx_realInput(tx) = input
        }
      }
      val percentage = tx_realInput.size * 1.0 / tx_inputs.size

      //convert Long back to String
      val tx_realInput_rdd = spark.sparkContext.parallelize(tx_realInput.toSeq)
      val key_image_coin_determined = tx_realInput_rdd.toDF("image_id", "determined_coin_id")
      val determined_coins = key_image_coin_determined
        .join(key_image_id, $"image_id" === $"key_image_id", joinType = "inner")
        .join(candidate_id, $"determined_coin_id" === $"coin_candidate_id", joinType = "inner")
          .select("key_image_join", "coin_candidate_join")

      // results are in two columns: key_image_join, coin_candidate_join

      // here we are printing whole dataframe just to display the result
      // consequence is that it is all collected to the driver and then printed
      // WARNING: you don't want to do this in production!!
      determined_coins.collect().foreach(println)

      // in production, you should save it in HDFS / Hive / Cloud ...
      // e.g. determined_coins.write.option("header", "true").csv("result.csv")
      // be aware that you need to set up additional configuration so that you can connect to your storage space

      new PrintWriter(System.out) {
        write("Percentage of determined real coins: " + percentage + "\n")
        write("Number of iterations: " + numOfIterations + "\n")
        close()
      }
    }

    finally {
      spark.stop()
    }
  }

}
