# Linkability in Monero
This project is implementation of the paper: 

[An Empirical Analysis of Linkability in the Monero Blockchain](http://monerolink.com/monerolink.pdf) 

written by Andrew Miller, Malte Moser, Kevin Lee and Arvind Narayanan.

 
## Installation 
 In the following section is described how to use this source code to obtain results.
 #### Getting information about the transactions
 First what we need is to get data for processing. For that, we use the following GitHub repository: [transactions-export](https://github.com/moneroexamples/transactions-export).
Here is not described how to use that repository since that is already covered in that repository. 
To go through the complete problem faster, here we work with only first 1% (12361 blocks) of a blockchain which is used in the paper. 
Command which we need to execute after setting up the project linked above is:

`/xmr2csv -t 0 -n 12361  --all-key-images --out-csv-file4 "allColumns.csv"`

Now we have the information about the transactions stored in `allColumns.csv`.

#### Preparing data
We don't need actually all the information about the transactions. What we need is only two columns in generated CSV -- `Key_image` and `Referenced_output_pub_key`. If we can link (one on one) a coin with its' key image, then we know when and where we use it so it is not anonymous anymore by definition.
We get the necessary information using the following command:

`cut -d ',' -f 4,7 allColumns.csv >> transactions.csv`

Now we have only the necessary pieces of information stored in `transactions.csv`.
From now on, we refer to that file with that name (`transactions.csv`), but it can be any other name as well, just be consistent.

#### Creating a cluster

We want to run this on a cluster so we need to [create a cluster](https://console.cloud.google.com/dataproc/clustersAdd). Depending on a size of a blockchain and a budget, nodes can be with more or less RAM and CPUs. Default configuration works for 1% of a blockchain.

Every cluster has a name so we know where to submit a job. We will refer to it as `cluster_name`, but please keep in mind that name of your cluster is probably different. 
#### Uploading data
We upload file `transactions.csv` to our Google Cloud Storage. We upload it to the Cloud Storage staging bucket (top-most directory, not into google-cloud-dataproc-metainfo/) of a cluster we just created and make it publicly available.

From this point on, for the name of a bucket in which we uploaded `transactions.csv`, we will use `bucketName`.

If everything is done correctly, now at `https://storage.googleapis.com/bucketName/transactions.csv` should be a CSV file with the pieces of information we uploaded.

#### Building files

Make sure [Java 8](https://java.com/) and [sbt > 1.0](www.scala-sbt.org) is installed:

    java -version
    sbt about

Download, install, and test Apache Spark (version >= 2.2.0) in $SPARK_HOME:

    $SPARK_HOME/bin/spark-shell
    
(not sure if the last step is necessary)

Now, the next steps should be followed
0) Clone this repository. 
1) Make sure you are in right directory `cd monero-linkability`. 
3) Execute `sbt clean && sbt compile && sbt package`. 

Last three lines which are written to standard output after the last command is executed should be something similiar to:

```
[info] Packaging path_to_package ...
[info] Done packaging.
[success] Total time: 2 s, completed Feb 7, 2018 4:42:17 PM
```
 Please keep in mind where is a package now, i.e. copy `path_to_package` in some file or just into clipboard.

Finally, we have everything ready.

## Usage

There are [three ways how to submit a spark job on a cluster](https://cloud.google.com/dataproc/docs/guides/submit-job).

We will use [gcloud dataproc jobs submit](https://cloud.google.com/sdk/gcloud/reference/dataproc/jobs/submit/) command directly from our terminal which assumes you have [necessary packages installed](https://cloud.google.com/sdk/downloads). However, it is also possible to submit a job through Web UI ("Console").

Command which submits a job is the following:
```
gcloud dataproc jobs submit spark --cluster cluster_name --jar path_to_package > stdout.txt 2> results.txt
```

## Results 

Percentage of determined coins on 1% of a blockchain should be 0.725, i.e. for almost 3 out of 4 coins we know for sure in which transaction are they used as a real input.

After the last command from the previous section is executed, we can find results in `results.txt`. Moreover, results are on a cluster as well - in `bucketName`, in a directory created for this job.

## Conclusions and future work
For bigger instances, it takes rather long time to execute the algorithm. Therefore, we conclude that Spark is not a perfect technology for the iterative algorithm. For better computational results, we propose a graph database, for instance, Neo4j and Cypher to query it.

After we determined which coin is spent where, we should learn something from that. In paper, several heuristics are proposed. It is shown that among all the coins in a mixin (ring), the newest one is usually the real one. We can use that to guess the real coin in a future if a real coin can't be determined with certainity.

It would be also interesting to try to combine this result with machine learning. Maybe there are some useful features (e.g. time of output) on a blockchain and we can use that to link coins.

But then again, using ML and heuristics, we can only have some probability that some coin is actually spent as an input. 
