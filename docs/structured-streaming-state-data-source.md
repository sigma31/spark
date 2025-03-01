---
layout: global
displayTitle: State Data Source Integration Guide
title: State Data Source Integration Guide
license: |
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
---

State data source Guide in Structured Streaming (Experimental)

## Overview

State data source provides functionality to manipulate the state from the checkpoint.

As of Spark 4.0, state data source provides the read functionality with a batch query. Additional functionalities including write is on the future roadmap.

NOTE: this data source is currently marked as experimental - source options and the behavior (output) might be subject to change.

## Reading state key-values from the checkpoint

State data source enables reading key-value pairs from the state store in the checkpoint, via running a separate batch query.
Users can leverage the functionality to cover two major use cases described below:

* Construct a test checking both output and the state. It is non-trivial to deduce the key-value of the state from the output, and having visibility of the state would be a huge win on testing.
* Investigate an incident against stateful streaming query. If users observe the incorrect output and want to track how it came up, having visibility of the state would be required.

Users can read an instance of state store, which is matched to a single stateful operator in most cases. This means, users can expect that they can read the entire key-value pairs in the state for a single stateful operator. 

Note that there could be an exception, e.g. stream-stream join, which leverages multiple state store instances internally. The data source abstracts the internal representation away from users and
provides a user-friendly approach to read the state. See the section for stream-stream join for more details.

### Creating a State store for Batch Queries (all defaults)

<div class="codetabs">

<div data-lang="python" markdown="1">
{% highlight python %}

df = spark \
.read \
.format("statestore") \
.load("<checkpointLocation>")

{% endhighlight %}
</div>

<div data-lang="scala" markdown="1">
{% highlight scala %}

val df = spark
.read
.format("statestore")
.load("<checkpointLocation>")

{% endhighlight %}
</div>

<div data-lang="java" markdown="1">
{% highlight java %}

Dataset<Row> df = spark
.read()
.format("statestore")
.load("<checkpointLocation>");

{% endhighlight %}
</div>

</div>

Each row in the source has the following schema:

<table class="table table-striped">
<thead><tr><th>Column</th><th>Type</th><th>Note</th></tr></thead>
<tr>
  <td>key</td>
  <td>struct (depends on the type for state key)</td>
  <td></td>
</tr>
<tr>
  <td>value</td>
  <td>struct (depends on the type for state value)</td>
  <td></td>
</tr>
<tr>
  <td>_partition_id</td>
  <td>int</td>
  <td>metadata column (hidden unless specified with SELECT)</td>
</tr>
</table>

The nested columns for key and value heavily depend on the input schema of the stateful operator as well as the type of operator.
Users are encouraged to query about the schema via df.schema() / df.printSchema() first to understand the type of output.

The following options must be set for the source.

<table class="table table-striped">
<thead><tr><th>Option</th><th>value</th><th>meaning</th></tr></thead>
<tr>
  <td>path</td>
  <td>string</td>
  <td>Specify the root directory of the checkpoint location. You can either specify the path via option("path", `path`) or load(`path`).</td>
</tr>
</table>

The following configurations are optional:

<table class="table table-striped">
<thead><tr><th>Option</th><th>value</th><th>default</th><th>meaning</th></tr></thead>
<tr>
  <td>batchId</td>
  <td>numeric value</td>
  <td>latest committed batch</td>
  <td>Represents the target batch to read from. This option is used when users want to perform time-travel. The batch should be committed but not yet cleaned up.</td>
</tr>
<tr>
  <td>operatorId</td>
  <td>numeric value</td>
  <td>0</td>
  <td>Represents the target operator to read from. This option is used when the query is using multiple stateful operators.</td>
</tr>
<tr>
  <td>storeName</td>
  <td>string</td>
  <td>DEFAULT</td>
  <td>Represents the target state store name to read from. This option is used when the stateful operator uses multiple state store instances. It is not required except stream-stream join.</td>
</tr>
<tr>
  <td>joinSide</td>
  <td>string ("left" or "right")</td>
  <td>(none)</td>
  <td>Represents the target side to read from. This option is used when users want to read the state from stream-stream join.</td>
</tr>
</table>

### Reading state for Stream-stream join

Structured Streaming implements the stream-stream join feature via leveraging multiple instances of state store internally.
These instances logically compose buffers to store the input rows for left and right.

Since it is more obvious to users to reason about, the data source provides the option 'joinSide' to read the buffered input for specific side of the join.
To enable the functionality to read the internal state store instance directly, we also allow specifying the option 'storeName', with restriction that 'storeName' and 'joinSide' cannot be specified together.

## State metadata source

Before querying the state from existing checkpoint via state data source, users would like to understand the information for the checkpoint, especially about state operator. This includes which operators and state store instances are available in the checkpoint, available range of batch IDs, etc.

Structured Streaming provides a data source named "State metadata source" to provide the state-related metadata information from the checkpoint.

Note: The metadata is constructed when the streaming query is running with Spark 4.0+. The existing checkpoint which has been running with lower Spark version does not have the metadata and will be unable to query/use with this metadata source. It is required to run the streaming query pointing the existing checkpoint in Spark 4.0+ to construct the metadata before querying.

### Creating a State metadata store for Batch Queries

<div class="codetabs">

<div data-lang="python" markdown="1">
{% highlight python %}

df = spark \
.read \
.format("state-metadata") \
.load("<checkpointLocation>")

{% endhighlight %}
</div>

<div data-lang="scala" markdown="1">
{% highlight scala %}

val df = spark
.read
.format("state-metadata")
.load("<checkpointLocation>")

{% endhighlight %}
</div>

<div data-lang="java" markdown="1">
{% highlight java %}

Dataset<Row> df = spark
.read()
.format("state-metadata")
.load("<checkpointLocation>");

{% endhighlight %}
</div>

</div>

Each row in the source has the following schema:

<table class="table table-striped">
<thead><tr><th>Column</th><th>Type</th><th>Note</th></tr></thead>
<tr>
  <td>operatorId</td>
  <td>int</td>
  <td></td>
</tr>
<tr>
  <td>operatorName</td>
  <td>string</td>
  <td></td>
</tr>
<tr>
  <td>stateStoreName</td>
  <td>int</td>
  <td></td>
</tr>
<tr>
  <td>numPartitions</td>
  <td>int</td>
  <td></td>
</tr>
<tr>
  <td>minBatchId</td>
  <td>int</td>
  <td>The minimum batch ID available for querying state. The value could be invalid if the streaming query taking the checkpoint is running, as cleanup would run.</td>
</tr>
<tr>
  <td>maxBatchId</td>
  <td>int</td>
  <td>The maximum batch ID available for querying state. The value could be invalid if the streaming query taking the checkpoint is running, as the query will commit further batches.</td>
</tr>
<tr>
  <td>_numColsPrefixKey</td>
  <td>int</td>
  <td>metadata column (hidden unless specified with SELECT)</td>
</tr>
</table>

One of the major use cases of this data source is to identify the operatorId to query if the query has multiple stateful operators, e.g. stream-stream join followed by deduplication.
The column 'operatorName' helps users to identify the operatorId for given operator.

Additionally, if users want to query about an internal state store instance for a stateful operator (e.g. stream-stream join), the column 'stateStoreName' would be useful to determine the target.
