package com.amazonaws.rhythmcloud;

import com.amazonaws.rhythmcloud.domain.DrumHitReading;
import com.amazonaws.rhythmcloud.domain.DrumHitReadingWithType;
import com.amazonaws.rhythmcloud.io.Kinesis;
import com.amazonaws.rhythmcloud.process.DrumHitReadingWithTypeToTimeStreamPayload;
import com.amazonaws.services.kinesisanalytics.runtime.KinesisAnalyticsRuntime;
import lombok.extern.slf4j.Slf4j;
import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import java.util.Map;
import java.util.Properties;

@Slf4j
public class TemporalAnalyzer {
    public static void main(String[] args) throws Exception {
        try {
            log.info("Starting the rhythm analyzer");
            final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
            /*
             EventTime
                Event time means that the time is determined by the event's individual custom timestamp.
             IngestionTime
                Ingestion time means that the time is determined when the element enters the Flink streaming data flow.
             ProcessingTime
                Processing time for operators means that the operator uses the system clock of the machine to determine the current time of the data stream.
             */
            env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);
            // Flink’s checkpointing enabled, the Flink Kinesis Consumer will consume
            // records from shards in Kinesis streams and periodically checkpoint
            // each shard’s progress. In case of a job failure, Flink will restore the streaming
            // program to the state of the latest complete checkpoint and re-consume
            // the records from Kinesis shards.
            env.enableCheckpointing(5000); // checkpoint every 5000 5 seconds

            Map<String, Properties> properties = KinesisAnalyticsRuntime.getApplicationProperties();

            // Read the system hit stream without the metronome beat
            // and stamp the data with system hit
            SingleOutputStreamOperator<DrumHitReadingWithType> systemHitStream = Kinesis.createSourceFromConfig(
                    Constants.Stream.SYSTEMHIT,
                    properties,
                    env)
                    .filter((FilterFunction<DrumHitReading>) drumHitReading ->
                            (!drumHitReading.getDrum().equalsIgnoreCase("metronome")))
                    .map(hit -> new DrumHitReadingWithType(
                            hit.getSessionId(),
                            hit.getDrum(),
                            hit.getTimestamp(),
                            hit.getVoltage(),
                            Constants.Stream.SYSTEMHIT))
                    .name("System Hit Stream");

            systemHitStream
                    .map(new DrumHitReadingWithTypeToTimeStreamPayload())
                    .addSink(
                            Kinesis.createTimeSinkFromConfig(
                                    Constants.Stream.TIMESTREAM,
                                    properties,
                                    env))
                    .name("Sink to Timestream database");

            env.execute("Temporal Analyzer");
        } catch (Exception err) {
            log.error("Temporal analyzer failed", err);
        }
    }
}
