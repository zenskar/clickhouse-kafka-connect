package com.clickhouse.kafka.connect.sink.state.provider;

import com.clickhouse.client.ClickHouseClient;
import com.clickhouse.client.ClickHouseException;
import com.clickhouse.client.ClickHouseNode;
import com.clickhouse.client.ClickHouseNodeSelector;
import com.clickhouse.client.ClickHouseProtocol;
import com.clickhouse.client.ClickHouseResponse;
import com.clickhouse.data.ClickHouseFormat;
import com.clickhouse.data.ClickHouseRecord;
import com.clickhouse.kafka.connect.sink.ClickHouseSinkConfig;
import com.clickhouse.kafka.connect.sink.db.helper.ClickHouseHelperClient;
import com.clickhouse.kafka.connect.sink.state.State;
import com.clickhouse.kafka.connect.sink.state.StateProvider;
import com.clickhouse.kafka.connect.sink.state.StateRecord;
import com.clickhouse.kafka.connect.util.Mask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KeeperStateProvider implements StateProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(KeeperStateProvider.class);
    private ClickHouseNode server = null;
    private int pingTimeOut = 100;


    private ClickHouseHelperClient chc = null;
    private ClickHouseSinkConfig csc = null;

    public KeeperStateProvider(ClickHouseSinkConfig csc) {
        this.csc = csc;

        String hostname = csc.getHostname();
        int port = csc.getPort();
        String database = csc.getDatabase();
        String username = csc.getUsername();
        String password = csc.getPassword();
        boolean sslEnabled = csc.isSslEnabled();
        String jdbcConnectionProperties = csc.getJdbcConnectionProperties();
        int timeout = csc.getTimeout();
        String clientVersion = csc.getClientVersion();
        boolean useClientV2 = clientVersion.equals("V1") ? false : true;
        LOGGER.info(String.format("hostname: [%s] port [%d] database [%s] username [%s] password [%s] sslEnabled [%s] timeout [%d]", hostname, port, database, username, Mask.passwordMask(password), sslEnabled, timeout));

        chc = new ClickHouseHelperClient.ClickHouseClientBuilder(hostname, port, csc.getProxyType(), csc.getProxyHost(), csc.getProxyPort())
                .setDatabase(database)
                .setUsername(username)
                .setPassword(password)
                .sslEnable(sslEnabled)
                .setJdbcConnectionProperties(jdbcConnectionProperties)
                .setTimeout(timeout)
                .setRetry(csc.getRetry())
                .useClientV2(useClientV2)
                .build();

        if (!chc.ping()) {
            LOGGER.error("Unable to ping Clickhouse server.");
            // TODO: exit
        }
        LOGGER.info("Ping is successful.");
        init();
    }

    public KeeperStateProvider(ClickHouseHelperClient chc) {
        if (!chc.ping())
            throw new RuntimeException("ping");
        this.chc = chc;
        init();
    }

    private void init() {
        String createTable = String.format("CREATE TABLE IF NOT EXISTS `%s`%s " +
                "(`key` String, minOffset BIGINT, maxOffset BIGINT, state String)" +
                " ENGINE=KeeperMap('%s') PRIMARY KEY `key`;",
                csc.getZkDatabase(),
                csc.getKeeperOnCluster().isEmpty() ? "" : " ON CLUSTER " + csc.getKeeperOnCluster(),
                csc.getZkPath());
        // TODO: exec instead of query
        if (chc.isUseClientV2()) {
            chc.queryV2(createTable);
        } else {
            ClickHouseResponse r = chc.queryV1(createTable);
            r.close();
        }
    }

    @Override
    public StateRecord getStateRecord(String topic, int partition) {
        String key = String.format("%s-%d", topic, partition);
        String selectStr = String.format("SELECT * FROM `%s` WHERE `key`= '%s'", csc.getZkDatabase(), key);
        try (ClickHouseClient client = ClickHouseClient.builder()
                .options(chc.getDefaultClientOptions())
                .nodeSelector(ClickHouseNodeSelector.of(ClickHouseProtocol.HTTP))
                .build();
             ClickHouseResponse response = client.read(chc.getServer()) // or client.connect(endpoints)
                     .format(ClickHouseFormat.RowBinaryWithNamesAndTypes)
                     .query(selectStr)
                     .executeAndWait()) {
            LOGGER.debug("return size: {}", response.getSummary().getReadRows());
            if ( response.getSummary().getResultRows() == 0) {
                LOGGER.info(String.format("read state record: topic %s partition %s with NONE state", topic, partition));
                return new StateRecord(topic, partition, 0, 0, State.NONE);
            }
            ClickHouseRecord r = response.firstRecord();
            long minOffset = r.getValue(1).asLong();
            long maxOffset = r.getValue(2).asLong();
            State state = State.valueOf(r.getValue(3).asString());
            LOGGER.debug(String.format("read state record: topic %s partition %s with %s state max %d min %d", topic, partition, state, maxOffset, minOffset));
            return new StateRecord(topic, partition, maxOffset, minOffset, state);
        } catch (ClickHouseException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setStateRecord(StateRecord stateRecord) {
        long minOffset = stateRecord.getMinOffset();
        long maxOffset = stateRecord.getMaxOffset();
        String key = stateRecord.getTopicAndPartitionKey();
        String state = stateRecord.getState().toString();
        String insertStr = String.format("INSERT INTO `%s` SETTINGS wait_for_async_insert=1 VALUES ('%s', %d, %d, '%s');", csc.getZkDatabase(), key, minOffset, maxOffset, state);
        if (chc.isUseClientV2()) {
            this.chc.queryV2(insertStr);
        } else {
            ClickHouseResponse response = this.chc.queryV1(insertStr);
            LOGGER.info(String.format("write state record: topic %s partition %s with %s state max %d min %d", stateRecord.getTopic(), stateRecord.getPartition(), state, maxOffset, minOffset));
            LOGGER.debug(String.format("Number of written rows [%d]", response.getSummary().getWrittenRows()));
            response.close();
        }
    }
}
