package main.scala

import java.io.{File, PrintWriter}
import java.util

import org.apache.commons.io.FileUtils
import org.apache.spark.{SparkContext, SparkFiles}
import org.apache.spark.sql.SQLContext

import scala.collection.mutable
import scala.io.Source


object Main {

  def main(args: Array[String]) {

    val spark = org.apache.spark.sql.SparkSession.builder
      .appName("Monero Linkability")
      .getOrCreate
      //.master("local")

    try {
      // val bucket_name = "dataproc-c6577e59-b6be-4c72-9aaf-aba47b84a259-asia-southeast1"
      // val input_fn = "transactions-1percent.csv"

      val bucket_name = args(0)
      val input_fn = args(1)

      // Load the lines of text
      val lines = spark.read.format("csv").option("header", "true").load("gs://" + bucket_name + "/" + input_fn).rdd

      // (key_image, candidate1)
      // (key_image, candidate2)
      val tx_input = lines
        .map {
          line =>
            val chunks = line.toString().substring(1, line.toString().length - 1).split(",")
            (chunks(0).trim(): String, chunks(1).trim(): String)
        }

      //      possible optimization: Long numbers instead of string hashes
      //      val key_image_unique_id = lines.map {
      //        line =>
      //          val chunks = line.toString().substring(1, line.toString().length - 1).split(",")
      //          chunks(0).trim()
      //      }.distinct().zipWithUniqueId()
      //
      //      val candidate_unique_id = lines.map {
      //        line =>
      //          val chunks = line.toString().substring(1, line.toString().length - 1).split(",")
      //          chunks(1).trim()
      //      }.distinct().zipWithUniqueId()

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
            (input, "?")
        }

      // that's it - nothing more can be done with those RDDs actually ..

      val input_tx_map = collection.mutable.Map(input_tx.collectAsMap().toSeq: _*)
      val tx_inputs_map = new util.HashMap[String, util.HashSet[String]]()

      for ((tx, inputs) <- tx_inputs) {
        tx_inputs_map.put(tx, new util.HashSet[String]())
        for (input <- inputs) {
          tx_inputs_map.get(tx).add(input)
        }
      }


      var changeHappened = false
      do {
        var keysToRemove = new collection.mutable.HashSet[String]()
        var inputsToRemove = new collection.mutable.HashSet[String]()
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

      val tx_realInput = new mutable.HashMap[String, String]()
      //prepare result
      for ((input, tx) <- input_tx_map) {
        if (tx != "?") {
          tx_realInput(tx) = input
        }
      }


      // save to file
      new PrintWriter(System.out) {
        tx_realInput.foreach {
          case (k, v) =>
            write(k + "," + v)
            write("\n")
        }
        write("Percentage of determined real coins: " + tx_realInput.size * 1.0 / tx_inputs.size + "\n")
        close()
      }
    }

    finally {
      spark.stop()
    }
  }

}
