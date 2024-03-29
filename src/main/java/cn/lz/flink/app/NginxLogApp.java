package cn.lz.flink.app;

import cn.lz.flink.app.model.NginxLog;
import io.krakens.grok.api.Grok;
import io.krakens.grok.api.GrokCompiler;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.flink.api.common.functions.AggregateFunction;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.streaming.api.functions.timestamps.BoundedOutOfOrdernessTimestampExtractor;
import org.apache.flink.streaming.api.functions.windowing.WindowFunction;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
public class NginxLogApp {

    /**
     * 解析Nginx日志到NginxLog
     */
    public static class NginxLogParse extends RichMapFunction<String, NginxLog> {
        private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);
        private Grok grok = null;

        @Override
        public void open(Configuration parameters) throws Exception {
            GrokCompiler grokCompiler = GrokCompiler.newInstance();
            grokCompiler.registerDefaultPatterns();
            grok = grokCompiler.compile("%{COMBINEDAPACHELOG}");
        }

        @Override
        public NginxLog map(String line) throws Exception {
            final Map<String, Object> capture = grok.match(line).capture();

            return NginxLog.builder()
                    .clientIp((String) capture.get("clientip"))
                    .timestamp(LocalDateTime.parse((String) capture.get("timestamp"), DATE_TIME_FORMATTER))
                    .request((String) capture.get("request"))
                    .responseStatus((String) capture.get("response"))
                    .bodyBytesSent((String) capture.get("bytes"))
                    .httpReferer((String) capture.get("referrer"))
                    .httpUserAgent((String) capture.get("agent"))
                    .build();
        }
    }

    /**
     * COUNT 聚合函数实现，每出现一条记录加1
     */
    public static class CountAgg implements AggregateFunction<NginxLog, Long, Long> {

        @Override
        public Long createAccumulator() {
            return 0L;
        }

        @Override
        public Long add(NginxLog value, Long acc) {
            return acc + 1;
        }

        @Override
        public Long getResult(Long acc) {
            return acc;
        }

        @Override
        public Long merge(Long acc1, Long acc2) {
            return acc1 + acc2;
        }
    }

    public static class ResultWindowFunction implements WindowFunction<Long, RequestCount, String, TimeWindow> {

        @Override
        public void apply(String url, TimeWindow window, Iterable<Long> input, Collector<RequestCount> out) throws Exception {
            Long count = input.iterator().next();
            out.collect(RequestCount.of(url, window.maxTimestamp(), count));
        }
    }

    @Data
    public static class RequestCount {
        public String request;
        public LocalDateTime dataTime;
        public long windowEnd;
        public long viewCount;

        public static RequestCount of(String request, long windowEnd, long viewCount) {
            RequestCount result = new RequestCount();
            result.request = request;
            result.windowEnd = windowEnd;
            result.viewCount = viewCount;
            return result;
        }
    }

    /**
     * 求某个窗口中前N名的热门接口地址，key为窗口时间戳，输出为Top N 的结果字符串
     */
    public static class TopNHotItems extends KeyedProcessFunction<Long, RequestCount, String> {
        private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH时 mm分 ss秒");

        private final int topSize;

        public TopNHotItems(int topSize) {
            this.topSize = topSize;
        }

        private ListState<RequestCount> requestState;

        @Override
        public void open(Configuration parameters) throws Exception {
            super.open(parameters);
            ListStateDescriptor<RequestCount> itemsStateDesc = new ListStateDescriptor<>(
                    "request-state",
                    RequestCount.class);
            requestState = getRuntimeContext().getListState(itemsStateDesc);
        }

        @Override
        public void processElement(
                RequestCount input,
                Context context,
                Collector<String> collector) throws Exception {

            // 每条数据都保存到状态中
            requestState.add(input);
            context.timerService().registerEventTimeTimer(input.windowEnd + 1);
        }

        @Override
        public void onTimer(
                long timestamp, OnTimerContext ctx, Collector<String> out) throws Exception {
            List<RequestCount> allItems = new ArrayList<>();
            for (RequestCount item : requestState.get()) {
                allItems.add(item);
            }
            requestState.clear();
            // 按照请求量从大到小排序
            allItems.sort((o1, o2) -> (int) (o2.viewCount - o1.viewCount));

            StringBuilder result = new StringBuilder();
            result.append("====================================\n");
            result.append("时间: ").append(LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp - 1), ZoneId.systemDefault()).format(DATE_TIME_FORMATTER)).append("\n");
            for (int i = 0; i < topSize && i < allItems.size(); i++) {
                RequestCount currentItem = allItems.get(i);
                result.append("No").append(i).append(":")
                        .append("  请求URL=").append(currentItem.getRequest())
                        .append("  请求量=").append(currentItem.getViewCount())
                        .append("\n");
            }
            result.append("====================================\n\n");

            out.collect(result.toString());
        }
    }

    public static void main(String[] args) throws Exception {
        final StreamExecutionEnvironment env =
                StreamExecutionEnvironment.getExecutionEnvironment();

        env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);


        env.readTextFile(Objects.requireNonNull(NginxLogApp.class.getClassLoader()
                .getResource("nginx.log")).getPath())
                .setParallelism(1)

                .map(new NginxLogParse())
                // 事件时间戳抽取器，最多10秒延迟，也就是晚到10秒的数据会被丢弃掉
                .assignTimestampsAndWatermarks(new BoundedOutOfOrdernessTimestampExtractor<NginxLog>(Time.of(10, TimeUnit.SECONDS)) {
                    @Override
                    public long extractTimestamp(NginxLog nginxLog) {
                        return nginxLog.getTimestamp().atZone(ZoneId.systemDefault()).toEpochSecond();
                    }
                })
                .keyBy(NginxLog::getRequest)
                //窗口统计点击量 滑动的窗口 10秒钟一次  统计一小时最高的  比如 [09:00:00, 09:00:10), [09:00:10, 09:00:20), [09:00:20, 09:00:30)…
                .timeWindow(Time.of(10, TimeUnit.SECONDS), Time.of(10, TimeUnit.SECONDS))
                .aggregate(new CountAgg(), new ResultWindowFunction())
                // 计算被请求最多的接口
                .keyBy(RequestCount::getWindowEnd)
                .process(new TopNHotItems(10))
                .print();

        env.execute();
    }
}
