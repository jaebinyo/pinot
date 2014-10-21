package com.linkedin.pinot.queries;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.linkedin.pinot.common.client.request.RequestConverter;
import com.linkedin.pinot.common.query.QueryExecutor;
import com.linkedin.pinot.common.query.ReduceService;
import com.linkedin.pinot.common.query.gen.AvroQueryGenerator;
import com.linkedin.pinot.common.query.gen.AvroQueryGenerator.TestGroupByAggreationQuery;
import com.linkedin.pinot.common.query.gen.AvroQueryGenerator.TestSimpleAggreationQuery;
import com.linkedin.pinot.common.request.BrokerRequest;
import com.linkedin.pinot.common.request.InstanceRequest;
import com.linkedin.pinot.common.response.BrokerResponse;
import com.linkedin.pinot.common.response.ServerInstance;
import com.linkedin.pinot.common.segment.ReadMode;
import com.linkedin.pinot.common.utils.DataTable;
import com.linkedin.pinot.core.data.manager.InstanceDataManager;
import com.linkedin.pinot.core.data.manager.config.InstanceDataManagerConfig;
import com.linkedin.pinot.core.data.readers.RecordReaderFactory;
import com.linkedin.pinot.core.indexsegment.IndexSegment;
import com.linkedin.pinot.core.indexsegment.columnar.ColumnarSegmentLoader;
import com.linkedin.pinot.core.indexsegment.columnar.creator.ColumnarSegmentCreator;
import com.linkedin.pinot.core.indexsegment.creator.SegmentCreatorFactory;
import com.linkedin.pinot.core.indexsegment.generator.SegmentGeneratorConfiguration;
import com.linkedin.pinot.core.indexsegment.generator.SegmentVersion;
import com.linkedin.pinot.core.query.executor.ServerQueryExecutorV1Impl;
import com.linkedin.pinot.core.query.reduce.DefaultReduceService;
import com.linkedin.pinot.core.time.SegmentTimeUnit;
import com.linkedin.pinot.pql.parsers.PQLCompiler;
import com.linkedin.pinot.segments.v1.creator.SegmentTestUtils;


/**
 * @author Dhaval Patel<dpatel@linkedin.com>
 * Oct 14, 2014
 */

public class QueriesSentinelTest {
  private static final Logger LOGGER = Logger.getLogger(QueriesSentinelTest.class);
  private static ReduceService REDUCE_SERVICE = new DefaultReduceService();

  private static final PQLCompiler REQUEST_COMPILER = new PQLCompiler(new HashMap<String, String[]>());

  private final String AVRO_DATA = "data/mirror-sv.avro";
  private static File INDEX_DIR = new File(QueriesSentinelTest.class.toString());
  private static AvroQueryGenerator AVRO_QUERY_GENERATOR;
  private static InstanceDataManager INSTANCE_DATA_MANAGER;
  private static QueryExecutor QUERY_EXECUTOR;
  private static TestingServerPropertiesBuilder CONFIG_BUILDER;

  @BeforeClass
  public void setup() throws Exception {
    CONFIG_BUILDER = new TestingServerPropertiesBuilder("mirror");

    setupSegmentFor("mirror");
    setUpTestQueries("mirror");

    final PropertiesConfiguration serverConf = CONFIG_BUILDER.build();
    serverConf.setDelimiterParsingDisabled(false);

    final InstanceDataManager instanceDataManager = InstanceDataManager.getInstanceDataManager();
    instanceDataManager.init(new InstanceDataManagerConfig(serverConf.subset("pinot.server.instance")));
    instanceDataManager.start();

    final IndexSegment indexSegment = ColumnarSegmentLoader.load(new File(INDEX_DIR, "segment"), ReadMode.heap);
    instanceDataManager.getResourceDataManager("mirror");
    instanceDataManager.getResourceDataManager("mirror").addSegment(indexSegment);

    QUERY_EXECUTOR = new ServerQueryExecutorV1Impl(false);
    QUERY_EXECUTOR.init(serverConf.subset("pinot.server.query.executor"), instanceDataManager);
  }

  @AfterClass
  public void tearDown() {
    FileUtils.deleteQuietly(INDEX_DIR);
  }

  @Test
  public void testAggregation() throws Exception {
    int counter = 0;
    Map<ServerInstance, DataTable> instanceResponseMap = new HashMap<ServerInstance, DataTable>();
    final List<TestSimpleAggreationQuery> aggCalls = AVRO_QUERY_GENERATOR.giveMeNSimpleAggregationQueries(1000000);
    for (final TestSimpleAggreationQuery aggCall : aggCalls) {
      LOGGER.info("running " + counter + " : " + aggCall.pql);
      BrokerRequest brokerRequest = RequestConverter.fromJSON(REQUEST_COMPILER.compile(aggCall.pql));
      DataTable instanceResponse = QUERY_EXECUTOR.processQuery(new InstanceRequest(counter++, brokerRequest));
      instanceResponseMap.clear();
      instanceResponseMap.put(new ServerInstance("localhost:0000"), instanceResponse);
      BrokerResponse brokerResponse = REDUCE_SERVICE.reduceOnDataTable(brokerRequest, instanceResponseMap);
      LOGGER.info("BrokerResponse is " + brokerResponse.getAggregationResults().get(0));
      LOGGER.info("Result from avro is : " + aggCall.result);
      Assert.assertEquals(Double.parseDouble(brokerResponse.getAggregationResults().get(0).getString("value")),
          aggCall.result);
    }
  }

  @Test
  public void testAggregationGroupBy() throws Exception {
    final List<TestGroupByAggreationQuery> groupByCalls = AVRO_QUERY_GENERATOR.giveMeNGroupByAggregationQueries(100);
    int counter = 0;
    Map<ServerInstance, DataTable> instanceResponseMap = new HashMap<ServerInstance, DataTable>();
    for (final TestGroupByAggreationQuery groupBy : groupByCalls) {
      LOGGER.info("running : " + groupBy.pql);
      BrokerRequest brokerRequest = RequestConverter.fromJSON(REQUEST_COMPILER.compile(groupBy.pql));
      DataTable instanceResponse = QUERY_EXECUTOR.processQuery(new InstanceRequest(counter++, brokerRequest));
      instanceResponseMap.clear();
      instanceResponseMap.put(new ServerInstance("localhost:0000"), instanceResponse);
      BrokerResponse brokerResponse = REDUCE_SERVICE.reduceOnDataTable(brokerRequest, instanceResponseMap);
      LOGGER.info("BrokerResponse is " + brokerResponse.getAggregationResults().get(0));
      LOGGER.info("Result from avro is : " + groupBy.groupResults);
      assertGroupByResults(brokerResponse.getAggregationResults().get(0).getJSONArray("groupByResult"),
          groupBy.groupResults);
    }
  }

  private void assertGroupByResults(JSONArray jsonArray, Map<Object, Double> groupResults) throws JSONException {
    Map<String, Double> groupByResult = new HashMap<String, Double>();
    for (int i = 0; i < jsonArray.length(); ++i) {
      groupByResult.put(jsonArray.getJSONObject(i).getJSONArray("group").toString(), jsonArray.getJSONObject(i)
          .getDouble("value"));
    }
    for (Object key : groupResults.keySet()) {
      String keyString = key.toString();
      double actual = groupByResult.get(keyString);
      double expected = groupResults.get(keyString);
      Assert.assertEquals(actual, expected);
    }
  }

  private void setUpTestQueries(String resource) throws FileNotFoundException, IOException {
    final String filePath = getClass().getClassLoader().getResource(AVRO_DATA).getFile();
    final List<String> dims = new ArrayList<String>();
    dims.add("viewerId");
    dims.add("viewerType");
    dims.add("vieweeId");
    dims.add("viewerCompany");
    dims.add("viewerCountry");
    dims.add("viewerRegionCode");
    dims.add("viewerIndustry");
    dims.add("viewerOccupation");
    dims.add("viewerSchool");
    dims.add("viewerSeniority");
    dims.add("viewerPrivacySetting");
    dims.add("viewerObfuscationType");
    dims.add("vieweePrivacySetting");
    dims.add("weeksSinceEpochSunday");
    dims.add("daysSinceEpoch");
    dims.add("hoursSinceEpoch");

    final List<String> mets = new ArrayList<String>();
    mets.add("count");

    final String time = "minutesSinceEpoch";
    AVRO_QUERY_GENERATOR = new AvroQueryGenerator(new File(filePath), dims, mets, time, resource);
    AVRO_QUERY_GENERATOR.init();
    AVRO_QUERY_GENERATOR.generateSimpleAggregationOnSingleColumnFilters();
  }

  private void setupSegmentFor(String resource) throws Exception {
    final String filePath = getClass().getClassLoader().getResource(AVRO_DATA).getFile();

    if (INDEX_DIR.exists()) {
      FileUtils.deleteQuietly(INDEX_DIR);
    }
    INDEX_DIR.mkdir();

    final SegmentGeneratorConfiguration config =
        SegmentTestUtils.getSegmentGenSpecWithSchemAndProjectedColumns(new File(filePath), new File(INDEX_DIR,
            "segment"), "daysSinceEpoch", SegmentTimeUnit.days, resource, resource);

    final ColumnarSegmentCreator creator =
        (ColumnarSegmentCreator) SegmentCreatorFactory.get(SegmentVersion.v1, RecordReaderFactory.get(config));
    creator.init(config);
    creator.buildSegment();

    System.out.println("built at : " + INDEX_DIR.getAbsolutePath());
  }
}