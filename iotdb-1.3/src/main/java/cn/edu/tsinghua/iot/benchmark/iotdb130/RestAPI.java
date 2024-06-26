package cn.edu.tsinghua.iot.benchmark.iotdb130;

import cn.edu.tsinghua.iot.benchmark.client.operation.Operation;
import cn.edu.tsinghua.iot.benchmark.conf.Config;
import cn.edu.tsinghua.iot.benchmark.conf.ConfigDescriptor;
import cn.edu.tsinghua.iot.benchmark.entity.Batch.IBatch;
import cn.edu.tsinghua.iot.benchmark.entity.Record;
import cn.edu.tsinghua.iot.benchmark.entity.Sensor;
import cn.edu.tsinghua.iot.benchmark.exception.DBConnectException;
import cn.edu.tsinghua.iot.benchmark.measurement.Status;
import cn.edu.tsinghua.iot.benchmark.schema.schemaImpl.DeviceSchema;
import cn.edu.tsinghua.iot.benchmark.tsdb.DBConfig;
import cn.edu.tsinghua.iot.benchmark.tsdb.IDatabase;
import cn.edu.tsinghua.iot.benchmark.tsdb.TsdbException;
import cn.edu.tsinghua.iot.benchmark.workload.query.impl.AggRangeQuery;
import cn.edu.tsinghua.iot.benchmark.workload.query.impl.AggRangeValueQuery;
import cn.edu.tsinghua.iot.benchmark.workload.query.impl.AggValueQuery;
import cn.edu.tsinghua.iot.benchmark.workload.query.impl.GroupByQuery;
import cn.edu.tsinghua.iot.benchmark.workload.query.impl.LatestPointQuery;
import cn.edu.tsinghua.iot.benchmark.workload.query.impl.PreciseQuery;
import cn.edu.tsinghua.iot.benchmark.workload.query.impl.RangeQuery;
import cn.edu.tsinghua.iot.benchmark.workload.query.impl.ValueRangeQuery;
import com.google.gson.Gson;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RestAPI implements IDatabase {
  private final OkHttpClient client = new OkHttpClient();
  private DBConfig dbConfig;
  private final String baseURL;
  private final String authorization = "Basic cm9vdDpyb290";
  protected final String ROOT_SERIES_NAME;
  protected static final Config config = ConfigDescriptor.getInstance().getConfig();

  public RestAPI(DBConfig dbConfig) {
    this.dbConfig = dbConfig;
    String host = dbConfig.getHOST().get(0);
    baseURL = String.format("http://%s:18080", host);
    ROOT_SERIES_NAME = "root";
  }

  private Request constructRequest(String api, String json) {
    RequestBody jsonBody = RequestBody.create(MediaType.parse("application/json"), json);
    return new Request.Builder()
        .url(String.format("%s%s", baseURL, api))
        .header("Authorization", authorization)
        .post(jsonBody)
        .build();
  }

  @Override
  public void init() throws TsdbException {}

  @Override
  public void cleanup() throws TsdbException {
    String json = "{\"sql\":\"delete database root.**\"}";
    Request request = constructRequest("/rest/v2/nonQuery", json);
    try {
      Response response = client.newCall(request).execute();
      response.close();
    } catch (IOException e) {
      System.out.println(e.getMessage());
    }
  }

  @Override
  public void close() throws TsdbException {}

  @Override
  public Double registerSchema(List<DeviceSchema> schemaList) throws TsdbException {
    return null;
  }

  @Override
  public Status insertOneBatch(IBatch batch) throws DBConnectException {
    String json = generatePayload(batch);
    Request request = constructRequest("/rest/v2/insertTablet", json);
    try {
      Response response = client.newCall(request).execute();
      response.close();
      return new Status(true);
    } catch (IOException e) {
      System.out.println(e.getMessage());
      return new Status(false);
    }
  }

  private String generatePayload(IBatch batch) {
    DeviceSchema schema = batch.getDeviceSchema();
    Payload payload = new Payload();
    payload.device = String.format("root.%s", schema.getDevicePath());
    payload.is_aligned = config.isIS_SENSOR_TS_ALIGNMENT();

    List<String> measurements = new ArrayList<>();
    List<String> dataTypes = new ArrayList<>();
    for (Sensor sensor : schema.getSensors()) {
      measurements.add(sensor.getName());
      dataTypes.add(sensor.getSensorType().name);
    }
    payload.measurements = measurements;
    payload.data_types = dataTypes;

    List<Long> timestamps = new ArrayList<>();
    List<List<Object>> values = new ArrayList<>();
    for (Record record : batch.getRecords()) {
      timestamps.add(record.getTimestamp());
      List<Object> row = record.getRecordDataValue();
      for (int j = 0; j < row.size(); j++) {
        if (values.size() <= j) {
          values.add(new ArrayList<>());
        }
        values.get(j).add(row.get(j));
      }
    }
    payload.timestamps = timestamps;
    payload.values = values;

    return new Gson().toJson(payload);
  }

  /**
   * Q1: PreciseQuery SQL: select {sensors} from {devices} where time = {time}
   *
   * @param preciseQuery universal precise query condition parameters
   * @return
   */
  @Override
  public Status preciseQuery(PreciseQuery preciseQuery) {
    String strTime = preciseQuery.getTimestamp() + "";
    String sql = getSimpleQuerySqlHead(preciseQuery.getDeviceSchema()) + " WHERE time = " + strTime;
    return executeQueryAndGetStatus(sql);
    // return executeQueryAndGetStatus(sql, Operation.PRECISE_QUERY);  // 可以优化一下
  }

  @Override
  public Status rangeQuery(RangeQuery rangeQuery) {
    String sql =
        getRangeQuerySql(
            rangeQuery.getDeviceSchema(),
            rangeQuery.getStartTimestamp(),
            rangeQuery.getEndTimestamp());
    return executeQueryAndGetStatus(sql);
  }

  @Override
  public Status valueRangeQuery(ValueRangeQuery valueRangeQuery) {
    String sql = getValueRangeQuerySql(valueRangeQuery);
    return executeQueryAndGetStatus(sql);
  }

  /**
   * Q4: AggRangeQuery SQL: select {AggFun}({sensors}) from {devices} where time >= {startTime} and
   * time <= {endTime}
   *
   * @param aggRangeQuery contains universal aggregation query with time filter parameters
   * @return
   */
  @Override
  public Status aggRangeQuery(AggRangeQuery aggRangeQuery) {
    String aggQuerySqlHead =
            getAggQuerySqlHead(aggRangeQuery.getDeviceSchema(), aggRangeQuery.getAggFun());
    String sql =
            addWhereTimeClause(
                    aggQuerySqlHead, aggRangeQuery.getStartTimestamp(), aggRangeQuery.getEndTimestamp());
    return executeQueryAndGetStatus(sql);
    // return executeQueryAndGetStatus(sql, Operation.AGG_RANGE_QUERY);
  }

  /**
   * Q5: AggValueQuery SQL: select {AggFun}({sensors}) from {devices} where {sensors} > {value}
   *
   * @param aggValueQuery contains universal aggregation query with value filter parameters
   * @return
   */
  @Override
  public Status aggValueQuery(AggValueQuery aggValueQuery) {
    String aggQuerySqlHead =
            getAggQuerySqlHead(aggValueQuery.getDeviceSchema(), aggValueQuery.getAggFun());
    String sql =
            aggQuerySqlHead
                    + " WHERE "
                    + getValueFilterClause(
                    aggValueQuery.getDeviceSchema(), (int) aggValueQuery.getValueThreshold())
                    .substring(4);
    return executeQueryAndGetStatus(sql);
    // return executeQueryAndGetStatus(sql, Operation.AGG_VALUE_QUERY);
  }

  @Override
  public Status aggRangeValueQuery(AggRangeValueQuery aggRangeValueQuery) {
    return null;
  }

  /**
   * Q7: GroupByQuery SQL: select {AggFun}({sensors}) from {devices} group by ([{start}, {end}],
   * {Granularity}ms)
   *
   * @param groupByQuery contains universal group by query condition parameters
   * @return
   */
  @Override
  public Status groupByQuery(GroupByQuery groupByQuery) {
    String aggQuerySqlHead =
            getAggQuerySqlHead(groupByQuery.getDeviceSchema(), groupByQuery.getAggFun());
    String sql =
            addGroupByClause(
                    aggQuerySqlHead,
                    groupByQuery.getStartTimestamp(),
                    groupByQuery.getEndTimestamp(),
                    groupByQuery.getGranularity());
    return executeQueryAndGetStatus(sql);
    // return executeQueryAndGetStatus(sql, Operation.GROUP_BY_QUERY);
  }

  @Override
  public Status latestPointQuery(LatestPointQuery latestPointQuery) {
    String sql = getLatestPointQuerySql(latestPointQuery.getDeviceSchema());
    return executeQueryAndGetStatus(sql);
  }

  @Override
  public Status rangeQueryOrderByDesc(RangeQuery rangeQuery) {
    return null;
  }

  /**
   * Q10: ValueRangeQuery SQL: select {sensors} from {devices} where time >= {startTime} and time <=
   * {endTime} and {sensors} > {value} order by time desc
   *
   * @param valueRangeQuery contains universal range query with value filter parameters
   * @return
   */
  @Override
  public Status valueRangeQueryOrderByDesc(ValueRangeQuery valueRangeQuery) {
    String sql = getValueRangeQuerySql(valueRangeQuery) + " order by time desc";
    return executeQueryAndGetStatus(sql);
    // return executeQueryAndGetStatus(sql, Operation.VALUE_RANGE_QUERY_ORDER_BY_TIME_DESC);
  }

  private Status executeQueryAndGetStatus(String sql) {
    String json = String.format("{\"sql\":\"%s\"}", sql);
    Request request = constructRequest("/rest/v2/query", json);
    try {
      Response response = client.newCall(request).execute();
      String body = response.body().string();
      QueryResult queryResult = new Gson().fromJson(body, QueryResult.class);
      response.close();
      return new Status(true, queryResult.timestamps.size());
    } catch (IOException e) {
      System.out.println(e.getMessage());
      return new Status(false);
    }
  }

  private String getRangeQuerySql(List<DeviceSchema> deviceSchemas, long start, long end) {
    return addWhereTimeClause(getSimpleQuerySqlHead(deviceSchemas), start, end);
  }

  private String getAggQuerySqlHead(List<DeviceSchema> devices, String aggFun) {
    StringBuilder builder = new StringBuilder();
    builder.append("SELECT ");
    List<Sensor> querySensors = devices.get(0).getSensors();
    builder.append(aggFun).append("(").append(querySensors.get(0).getName()).append(")");
    for (int i = 1; i < querySensors.size(); i++) {
      builder
              .append(", ")
              .append(aggFun)
              .append("(")
              .append(querySensors.get(i).getName())
              .append(")");
    }
    return addFromClause(devices, builder);
  }

  private String getValueRangeQuerySql(ValueRangeQuery valueRangeQuery) {
    String rangeQuerySql =
        getRangeQuerySql(
            valueRangeQuery.getDeviceSchema(),
            valueRangeQuery.getStartTimestamp(),
            valueRangeQuery.getEndTimestamp());
    String valueFilterClause =
        getValueFilterClause(
            valueRangeQuery.getDeviceSchema(), (int) valueRangeQuery.getValueThreshold());
    return rangeQuerySql + valueFilterClause;
  }

  private String getLatestPointQuerySql(List<DeviceSchema> devices) {
    StringBuilder builder = new StringBuilder();
    builder.append("SELECT last ");
    List<Sensor> querySensors = devices.get(0).getSensors();
    builder.append(querySensors.get(0).getName());
    for (int i = 1; i < querySensors.size(); i++) {
      builder.append(", ").append(querySensors.get(i).getName());
    }
    return addFromClause(devices, builder);
  }

  private String getValueFilterClause(List<DeviceSchema> deviceSchemas, int valueThreshold) {
    StringBuilder builder = new StringBuilder();
    for (DeviceSchema deviceSchema : deviceSchemas) {
      for (Sensor sensor : deviceSchema.getSensors()) {
        builder
            .append(" AND ")
            .append(getDevicePath(deviceSchema))
            .append(".")
            .append(sensor.getName())
            .append(" > ")
            .append(valueThreshold);
      }
    }
    return builder.toString();
  }

  private String addWhereTimeClause(String prefix, long start, long end) {
    String startTime = start + "";
    String endTime = end + "";
    return prefix + " WHERE time >= " + startTime + " AND time <= " + endTime;
  }

  private String addGroupByClause(String prefix, long start, long end, long granularity) {
    return prefix + " group by ([" + start + "," + end + ")," + granularity + "ms) ";
  }

  protected String getSimpleQuerySqlHead(List<DeviceSchema> devices) {
    StringBuilder builder = new StringBuilder();
    builder.append("SELECT ");
    List<Sensor> querySensors = devices.get(0).getSensors();
    builder.append(querySensors.get(0).getName());
    for (int i = 1; i < querySensors.size(); i++) {
      builder.append(", ").append(querySensors.get(i).getName());
    }
    return addFromClause(devices, builder);
  }

  private String addFromClause(List<DeviceSchema> devices, StringBuilder builder) {
    builder.append(" FROM ").append(getDevicePath(devices.get(0)));
    for (int i = 1; i < devices.size(); i++) {
      builder.append(", ").append(getDevicePath(devices.get(i)));
    }
    return builder.toString();
  }

  protected String getDevicePath(DeviceSchema deviceSchema) {
    StringBuilder name = new StringBuilder(ROOT_SERIES_NAME);
    name.append(".").append(deviceSchema.getGroup());
    for (Map.Entry<String, String> pair : deviceSchema.getTags().entrySet()) {
      name.append(".").append(pair.getValue());
    }
    name.append(".").append(deviceSchema.getDevice());
    return name.toString();
  }

  private class Payload {
    public String device;
    public boolean is_aligned;
    public List<List<Object>> values;
    public List<String> data_types;
    public List<String> measurements;
    public List<Long> timestamps;
  }

  private class QueryResult {
    public List<String> expressions;
    public List<String> column_names;
    public List<Long> timestamps;
    public List<List<Object>> values;
  }
}
