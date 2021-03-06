package com.linkedin.thirdeye.bootstrap.aggregation;

import static com.linkedin.thirdeye.bootstrap.aggregation.AggregationJobConstants.AGG_CONFIG_PATH;
import static com.linkedin.thirdeye.bootstrap.aggregation.AggregationJobConstants.AGG_INPUT_AVRO_SCHEMA;
import static com.linkedin.thirdeye.bootstrap.aggregation.AggregationJobConstants.AGG_INPUT_PATH;
import static com.linkedin.thirdeye.bootstrap.aggregation.AggregationJobConstants.AGG_OUTPUT_PATH;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.mapred.AvroKey;
import org.apache.avro.mapreduce.AvroJob;
import org.apache.avro.mapreduce.AvroKeyInputFormat;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkedin.thirdeye.api.DimensionKey;
import com.linkedin.thirdeye.api.MetricSchema;
import com.linkedin.thirdeye.api.MetricTimeSeries;
import com.linkedin.thirdeye.api.MetricType;
import com.linkedin.thirdeye.api.RollupThresholdFunction;
import com.linkedin.thirdeye.api.StarTreeConfig;

/**
 * @author kgopalak <br/>
 *         INPUT: RAW DATA FILES. <br/>
 *         EACH RECORD OF THE FORMAT {DIMENSION, TIME, RECORD} <br/>
 *         MAP OUTPUT: {DIMENSION KEY, TIME, METRIC} <br/>
 *         REDUCE OUTPUT: DIMENSION KEY: SET{TIME_BUCKET, METRIC}
 */
public class AggregatePhaseJob extends Configured {
  private static final Logger LOGGER = LoggerFactory.getLogger(AggregatePhaseJob.class);

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private String name;
  private Properties props;

  enum Constants {

  }

  public AggregatePhaseJob(String name, Properties props) {
    super(new Configuration());
    this.name = name;
    this.props = props;
  }

  public static class AggregationMapper extends
      Mapper<AvroKey<GenericRecord>, NullWritable, BytesWritable, BytesWritable> {
    private AggregationJobConfig config;
    private TimeUnit sourceTimeUnit;
    private TimeUnit aggregationTimeUnit;
    private List<String> dimensionNames;
    private List<String> metricNames;
    private List<MetricType> metricTypes;
    private MetricSchema metricSchema;
    private String[] dimensionValues;
    private RollupThresholdFunction rollupThresholdFunction;

    @Override
    public void setup(Context context) throws IOException, InterruptedException {
      LOGGER.info("AggregatePhaseJob.AggregationMapper.setup()");
      Configuration configuration = context.getConfiguration();
      FileSystem fileSystem = FileSystem.get(configuration);
      Path configPath = new Path(configuration.get(AGG_CONFIG_PATH.toString()));
      try {
        StarTreeConfig starTreeConfig = StarTreeConfig.decode(fileSystem.open(configPath));
        config = AggregationJobConfig.fromStarTreeConfig(starTreeConfig);
        dimensionNames = config.getDimensionNames();
        metricNames = config.getMetricNames();
        metricTypes = config.getMetricTypes();
        metricSchema = new MetricSchema(config.getMetricNames(), metricTypes);
        sourceTimeUnit = TimeUnit.valueOf(config.getTimeUnit());
        dimensionValues = new String[dimensionNames.size()];
        String className = config.getThresholdFuncClassName();
        Map<String, String> params = config.getThresholdFuncParams();
        Constructor<?> constructor = Class.forName(className).getConstructor(Map.class);
        rollupThresholdFunction = (RollupThresholdFunction) constructor.newInstance(params);
      } catch (Exception e) {
        throw new IOException(e);
      }
    }

    @Override
    public void map(AvroKey<GenericRecord> record, NullWritable value, Context context)
        throws IOException, InterruptedException {

      for (int i = 0; i < dimensionNames.size(); i++) {
        String dimensionName = dimensionNames.get(i);
        String dimensionValue = "";
        Object val = record.datum().get(dimensionName);
        if (val != null) {
          dimensionValue = val.toString();
        }
        dimensionValues[i] = dimensionValue;
      }

      DimensionKey key = new DimensionKey(dimensionValues);
      String sourceTimeWindow = record.datum().get(config.getTimeColumnName()).toString();
      long aggregationTimeWindow = -1;
      if (rollupThresholdFunction.getRollupAggregationGranularity() != null) {
        aggregationTimeUnit =
            TimeUnit.valueOf(rollupThresholdFunction.getRollupAggregationGranularity().getUnit()
                .toString());
        aggregationTimeWindow =
            aggregationTimeUnit.convert(Long.parseLong(sourceTimeWindow), sourceTimeUnit);
      }

      MetricTimeSeries series = new MetricTimeSeries(metricSchema);
      for (int i = 0; i < metricNames.size(); i++) {
        String metricName = metricNames.get(i);
        Object object = record.datum().get(metricName);
        String metricValueStr = "0";
        if (object != null) {
          metricValueStr = object.toString();
        }
        try {
          Number metricValue = metricTypes.get(i).toNumber(metricValueStr);
          series.increment(aggregationTimeWindow, metricName, metricValue);

        } catch (NumberFormatException e) {
          throw new NumberFormatException("Exception trying to convert " + metricValueStr + " to "
              + metricTypes.get(i) + " for metricName:" + metricName);
        }
      }
      // byte[] digest = md5.digest(dimensionValues.toString().getBytes());

      byte[] serializedKey = key.toBytes();

      byte[] serializedMetrics = series.toBytes();

      ByteArrayOutputStream baos = new ByteArrayOutputStream();

      baos.write(serializedKey.length);
      baos.write(serializedKey);
      baos.write(serializedMetrics.length);
      baos.write(serializedMetrics);

      context.write(new BytesWritable(serializedKey), new BytesWritable(serializedMetrics));
    }

    @Override
    public void cleanup(Context context) throws IOException, InterruptedException {

    }

  }

  public static class AggregationReducer extends
      Reducer<BytesWritable, BytesWritable, BytesWritable, BytesWritable> {
    private AggregationJobConfig config;
    private List<MetricType> metricTypes;
    private MetricSchema metricSchema;
    AggregationStats aggregationStats;
    String statOutputDir;
    private FileSystem fileSystem;

    @Override
    public void setup(Context context) throws IOException, InterruptedException {
      Configuration configuration = context.getConfiguration();
      fileSystem = FileSystem.get(configuration);
      Path configPath = new Path(configuration.get(AGG_CONFIG_PATH.toString()));
      try {
        StarTreeConfig starTreeConfig = StarTreeConfig.decode(fileSystem.open(configPath));
        config = AggregationJobConfig.fromStarTreeConfig(starTreeConfig);
        metricTypes = config.getMetricTypes();
        metricSchema = new MetricSchema(config.getMetricNames(), metricTypes);
        aggregationStats = new AggregationStats(metricSchema);
        statOutputDir = configuration.get(AGG_OUTPUT_PATH.toString()) + "_stats/";
      } catch (Exception e) {
        throw new IOException(e);
      }
    }

    @Override
    public void reduce(BytesWritable aggregationKey, Iterable<BytesWritable> timeSeriesIterable,
        Context context) throws IOException, InterruptedException {
      MetricTimeSeries out = new MetricTimeSeries(metricSchema);
      // AggregationKey key =
      // AggregationKey.fromBytes(aggregationKey.getBytes());
      for (BytesWritable writable : timeSeriesIterable) {
        MetricTimeSeries series = MetricTimeSeries.fromBytes(writable.copyBytes(), metricSchema);
        out.aggregate(series);
      }
      // record the stats
      aggregationStats.record(out);
      byte[] serializedBytes = out.toBytes();
      context.write(aggregationKey, new BytesWritable(serializedBytes));
    }

    protected void cleanup(Context context) throws IOException, InterruptedException {
      FSDataOutputStream outputStream =
          fileSystem.create(new Path(statOutputDir + "/" + context.getTaskAttemptID() + ".stat"));
      outputStream.write(aggregationStats.toString().getBytes());
      outputStream.close();
    };
  }

  public Job run() throws Exception {
    Job job = Job.getInstance(getConf());
    job.setJobName(name);
    job.setJarByClass(AggregatePhaseJob.class);
    FileSystem fs = FileSystem.get(getConf());
    // Avro schema
    Schema schema =
        new Schema.Parser().parse(fs.open(new Path(
            getAndCheck(AggregationJobConstants.AGG_INPUT_AVRO_SCHEMA.toString()))));
    LOGGER.info("{}", schema);

    // Map config
    job.setMapperClass(AggregationMapper.class);
    AvroJob.setInputKeySchema(job, schema);
    job.setInputFormatClass(AvroKeyInputFormat.class);
    job.setMapOutputKeyClass(BytesWritable.class);
    job.setMapOutputValueClass(BytesWritable.class);

    // Reduce config
    job.setCombinerClass(AggregationReducer.class);
    job.setReducerClass(AggregationReducer.class);
    job.setOutputKeyClass(BytesWritable.class);
    job.setOutputValueClass(BytesWritable.class);
    job.setOutputFormatClass(SequenceFileOutputFormat.class);

    String numReducers = props.getProperty("num.reducers");
    if (numReducers != null) {
      job.setNumReduceTasks(Integer.parseInt(numReducers));
    } else {
      job.setNumReduceTasks(10);
    }
    LOGGER.info("Setting number of reducers : " + job.getNumReduceTasks());

    // aggregation phase config
    Configuration configuration = job.getConfiguration();
    String inputPathDir = getAndSetConfiguration(configuration, AGG_INPUT_PATH);
    getAndSetConfiguration(configuration, AGG_CONFIG_PATH);
    getAndSetConfiguration(configuration, AGG_OUTPUT_PATH);
    getAndSetConfiguration(configuration, AGG_INPUT_AVRO_SCHEMA);
    LOGGER.info("Input path dir: " + inputPathDir);

    FileInputFormat.setInputDirRecursive(job, true);

    for (String inputPath : inputPathDir.split(",")) {
      Path input = new Path(inputPath);
      FileStatus[] listFiles = fs.listStatus(input);
      boolean isNested = false;
      for (FileStatus fileStatus : listFiles) {
        if (fileStatus.isDirectory()) {
          isNested = true;
          LOGGER.info("Adding input:" + fileStatus.getPath());
          FileInputFormat.addInputPath(job, fileStatus.getPath());
        }
      }
      if (!isNested) {
        LOGGER.info("Adding input:" + inputPath);
        FileInputFormat.addInputPath(job, input);
      }
    }
    FileOutputFormat.setOutputPath(job, new Path(getAndCheck(AGG_OUTPUT_PATH.toString())));

    job.waitForCompletion(true);

    return job;
  }

  private String getAndSetConfiguration(Configuration configuration,
      AggregationJobConstants constant) {
    String value = getAndCheck(constant.toString());
    configuration.set(constant.toString(), value);
    return value;
  }

  private String getAndCheck(String propName) {
    String propValue = props.getProperty(propName);
    if (propValue == null) {
      throw new IllegalArgumentException(propName + " required property");
    }
    return propValue;
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      throw new IllegalArgumentException("usage: config.properties");
    }

    Properties props = new Properties();
    props.load(new FileInputStream(args[0]));

    AggregatePhaseJob job = new AggregatePhaseJob("aggregate_avro_job", props);
    job.run();
  }

}
