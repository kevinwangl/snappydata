# SQL Properties

These properties can be set in the snappy-shell, using the following SQL commands.

| SQL Property | Description | SQL Command |
|--------|--------|--------|
|ColumnBatchSize|The default size of blocks to use for storage in SnappyData column and store. When inserting data into the column storage this is the unit (in bytes) that will be used to split the data into chunks for efficient storage and retrieval. </br> This property can also be set for each table in the `create table` DDL.|snappy.column.batchSize |
|ColumnMaxDeltaRows|The maximum number of rows that can be in the delta buffer of a column table. The size of delta buffer is already limited by `ColumnBatchSize` property, but this allows a lower limit on number of rows for better scan performance. So the delta buffer is rolled into the column store whichever of `ColumnBatchSize` and this property is hit first. It can also be set for each table in the `create table` DDL, else this setting is used for the `create table`|column.maxDeltaRows|
|HashJoinSize|The join would be converted into a hash join if the table is of size less than the `hashJoinSize`. Default value is 100 MB.|snappy.hashJoinSize|
|HashAggregateSize|Aggregation uses optimized hash aggregation plan but one that does not overflow to disk and can cause OOME if the result of aggregation is large. The limit specifies the input data size (with b/k/m/g/t/p suffixes for units) and not the output size. Set this only if there are queries that can return very large number of rows in aggregation results. Default value is set to 0b which means, no limit is set on the size, so the optimized hash aggregation is always used.|snappy.hashAggregateSize|