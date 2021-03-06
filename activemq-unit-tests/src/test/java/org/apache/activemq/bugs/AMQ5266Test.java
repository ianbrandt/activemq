/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.bugs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.QueueConnection;
import javax.jms.Session;
import javax.jms.TextMessage;
import org.apache.activemq.ActiveMQConnection;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.RedeliveryPolicy;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.broker.TransportConnector;
import org.apache.activemq.broker.region.policy.PolicyEntry;
import org.apache.activemq.broker.region.policy.PolicyMap;
import org.apache.activemq.command.ActiveMQQueue;
import org.apache.activemq.store.jdbc.JDBCPersistenceAdapter;
import org.apache.activemq.store.jdbc.adapter.DefaultJDBCAdapter;
import org.apache.derby.jdbc.EmbeddedDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import static org.junit.Assert.assertEquals;

/**
 * Stuck messages test client.
 * <p/>
 * Will kick of publisher and consumer simultaneously, and will usually result in stuck messages on the queue.
 */
public class AMQ5266Test {
    static Logger LOG = LoggerFactory.getLogger(AMQ5266Test.class);
    String activemqURL = "tcp://localhost:61617";
    BrokerService brokerService;
    private EmbeddedDataSource dataSource;

    @Before
    public void startBroker() throws Exception {
        brokerService = new BrokerService();

        dataSource = new EmbeddedDataSource();
        dataSource.setDatabaseName("target/derbyDb");
        dataSource.setCreateDatabase("create");

        JDBCPersistenceAdapter persistenceAdapter = new JDBCPersistenceAdapter();
        persistenceAdapter.setDataSource(dataSource);
        brokerService.setPersistenceAdapter(persistenceAdapter);
        brokerService.setDeleteAllMessagesOnStartup(true);


        PolicyMap policyMap = new PolicyMap();
        PolicyEntry defaultEntry = new PolicyEntry();
        defaultEntry.setEnableAudit(false);
        defaultEntry.setUseCache(false);
        defaultEntry.setMaxPageSize(1000);
        defaultEntry.setOptimizedDispatch(false);
        defaultEntry.setMemoryLimit(1024 * 1024);
        defaultEntry.setExpireMessagesPeriod(0);
        policyMap.setDefaultEntry(defaultEntry);
        brokerService.setDestinationPolicy(policyMap);

        brokerService.getSystemUsage().getMemoryUsage().setLimit(512 * 1024 * 1024);

        TransportConnector transportConnector = brokerService.addConnector("tcp://0.0.0.0:0");
        brokerService.start();
        activemqURL = transportConnector.getPublishableConnectString();
    }

    @After
    public void stopBroker() throws Exception {
        if (brokerService != null) {
            brokerService.stop();
        }
        try {
            dataSource.setShutdownDatabase("shutdown");
            dataSource.getConnection();
        } catch (Exception ignored) {}
    }

    @Test
    public void test() throws Exception {

        String activemqQueues = "activemq,activemq2";//,activemq3,activemq4,activemq5,activemq6,activemq7,activemq8,activemq9";

        int publisherMessagesPerThread = 1000;
        int publisherThreadCount = 5;

        int consumerThreadsPerQueue = 5;
        int consumerBatchSize = 25;
        int consumerWaitForConsumption = 5 * 60 * 1000;

        ExportQueuePublisher publisher = null;
        ExportQueueConsumer consumer = null;

        LOG.info("Publisher will publish " + (publisherMessagesPerThread * publisherThreadCount) + " messages to each queue specified.");
        LOG.info("\nBuilding Publisher...");

        publisher = new ExportQueuePublisher(activemqURL, activemqQueues, publisherMessagesPerThread, publisherThreadCount);

        LOG.info("Building Consumer...");

        consumer = new ExportQueueConsumer(activemqURL, activemqQueues, consumerThreadsPerQueue, consumerBatchSize, publisherMessagesPerThread * publisherThreadCount);


        LOG.info("Starting Publisher...");

        publisher.start();

        LOG.info("Starting Consumer...");

        consumer.start();

        int distinctPublishedCount = 0;


        LOG.info("Waiting For Publisher Completion...");

        publisher.waitForCompletion();

        distinctPublishedCount = publisher.getIDs().size();

        LOG.info("Publisher Complete. Distinct IDs Published: " + distinctPublishedCount);


        long endWait = System.currentTimeMillis() + consumerWaitForConsumption;


        while (!consumer.completed() && System.currentTimeMillis() < endWait) {
            try {
                int secs = (int) (endWait - System.currentTimeMillis()) / 1000;
                LOG.info("Waiting For Consumer Completion. Time left: " + secs + " secs");
                DefaultJDBCAdapter.dumpTables(dataSource.getConnection());
                Thread.sleep(10000);
            } catch (Exception e) {
            }
        }

        LOG.info("\nConsumer Complete. Shutting Down.");

        consumer.shutdown();

        LOG.info("Consumer Stats:");

        for (Map.Entry<String, List<String>> entry : consumer.getIDs().entrySet()) {

            List<String> idList = entry.getValue();

            int distinctConsumed = new TreeSet<String>(idList).size();

            StringBuilder sb = new StringBuilder();
            sb.append("   Queue: " + entry.getKey() +
                    " -> Total Messages Consumed: " + idList.size() +
                    ", Distinct IDs Consumed: " + distinctConsumed);

            int diff = distinctPublishedCount - distinctConsumed;
            sb.append(" ( " + (diff > 0 ? diff : "NO") + " STUCK MESSAGES " + " ) ");
            LOG.info(sb.toString());

            assertEquals("expect to get all messages!", 0, diff);

        }
    }

    public class ExportQueuePublisher {

        private final String amqUser = ActiveMQConnection.DEFAULT_USER;
        private final String amqPassword = ActiveMQConnection.DEFAULT_PASSWORD;
        private ActiveMQConnectionFactory connectionFactory = null;
        private String activemqURL = null;
        private String activemqQueues = null;
        // Collection of distinct IDs that the publisher has published.
        // After a message is published, its UUID will be written to this list for tracking.
        // This list of IDs (or distinct count) will be used to compare to the consumed list of IDs.
        private Set<String> ids = Collections.synchronizedSet(new TreeSet<String>());
        private List<PublisherThread> threads;

        public ExportQueuePublisher(String activemqURL, String activemqQueues, int messagesPerThread, int threadCount) throws Exception {

            this.activemqURL = activemqURL;
            this.activemqQueues = activemqQueues;

            threads = new ArrayList<PublisherThread>();

            // Build the threads and tell them how many messages to publish
            for (int i = 0; i < threadCount; i++) {
                PublisherThread pt = new PublisherThread(messagesPerThread);
                threads.add(pt);
            }
        }

        public Set<String> getIDs() {
            return ids;
        }

        // Kick off threads
        public void start() throws Exception {

            for (PublisherThread pt : threads) {
                pt.start();
            }
        }

        // Wait for threads to complete. They will complete once they've published all of their messages.
        public void waitForCompletion() throws Exception {

            for (PublisherThread pt : threads) {
                pt.join();
                pt.close();
            }
        }

        private Session newSession(QueueConnection queueConnection) throws Exception {
            return queueConnection.createSession(true, Session.SESSION_TRANSACTED);
        }

        private QueueConnection newQueueConnection() throws Exception {

            if (connectionFactory == null) {
                connectionFactory = new ActiveMQConnectionFactory(amqUser, amqPassword, activemqURL);
            }

            // Set the redelivery count to -1 (infinite), or else messages will start dropping
            // after the queue has had a certain number of failures (default is 6)
            RedeliveryPolicy policy = connectionFactory.getRedeliveryPolicy();
            policy.setMaximumRedeliveries(-1);

            QueueConnection amqConnection = connectionFactory.createQueueConnection();
            amqConnection.start();
            return amqConnection;
        }

        private class PublisherThread extends Thread {

            private int count;
            private QueueConnection qc;
            private Session session;
            private MessageProducer mp;

            private PublisherThread(int count) throws Exception {

                this.count = count;

                // Each Thread has its own Connection and Session, so no sync worries
                qc = newQueueConnection();
                session = newSession(qc);

                // In our code, when publishing to multiple queues,
                // we're using composite destinations like below
                Queue q = new ActiveMQQueue(activemqQueues);
                mp = session.createProducer(q);
            }

            public void run() {

                try {

                    // Loop until we've published enough messages
                    while (count-- > 0) {

                        TextMessage tm = session.createTextMessage("test");
                        String id = UUID.randomUUID().toString();
                        tm.setStringProperty("KEY", id);
                        ids.add(id);                            // keep track of the key to compare against consumer

                        mp.send(tm);
                        session.commit();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            // Called by waitForCompletion
            public void close() {

                try {
                    mp.close();
                } catch (Exception e) {
                }

                try {
                    session.close();
                } catch (Exception e) {
                }

                try {
                    qc.close();
                } catch (Exception e) {
                }
            }
        }

    }

    public class ExportQueueConsumer {

        private final String amqUser = ActiveMQConnection.DEFAULT_USER;
        private final String amqPassword = ActiveMQConnection.DEFAULT_PASSWORD;
        private final int totalToExpect;
        private ActiveMQConnectionFactory connectionFactory = null;
        private String activemqURL = null;
        private String activemqQueues = null;
        private String[] queues = null;
        // Map of IDs that were consumed, keyed by queue name.
        // We'll compare these against what was published to know if any got stuck or dropped.
        private Map<String, List<String>> idsByQueue = new HashMap<String, List<String>>();
        private Map<String, List<ConsumerThread>> threads;

        public ExportQueueConsumer(String activemqURL, String activemqQueues, int threadsPerQueue, int batchSize, int totalToExpect) throws Exception {

            this.activemqURL = activemqURL;
            this.activemqQueues = activemqQueues;
            this.totalToExpect = totalToExpect;

            queues = this.activemqQueues.split(",");

            for (int i = 0; i < queues.length; i++) {
                queues[i] = queues[i].trim();
            }

            threads = new HashMap<String, List<ConsumerThread>>();

            // For each queue, create a list of threads and set up the list of ids
            for (String q : queues) {

                List<ConsumerThread> list = new ArrayList<ConsumerThread>();

                idsByQueue.put(q, Collections.synchronizedList(new ArrayList<String>()));

                for (int i = 0; i < threadsPerQueue; i++) {
                    list.add(new ConsumerThread(q, batchSize));
                }

                threads.put(q, list);
            }
        }

        public Map<String, List<String>> getIDs() {
            return idsByQueue;
        }

        // Start the threads
        public void start() throws Exception {

            for (List<ConsumerThread> list : threads.values()) {

                for (ConsumerThread ct : list) {

                    ct.start();
                }
            }
        }

        // Tell the threads to stop
        // Then wait for them to stop
        public void shutdown() throws Exception {

            for (List<ConsumerThread> list : threads.values()) {

                for (ConsumerThread ct : list) {

                    ct.shutdown();
                }
            }

            for (List<ConsumerThread> list : threads.values()) {

                for (ConsumerThread ct : list) {

                    ct.join();
                }
            }
        }

        private Session newSession(QueueConnection queueConnection) throws Exception {
            return queueConnection.createSession(true, Session.SESSION_TRANSACTED);
        }

        private QueueConnection newQueueConnection() throws Exception {

            if (connectionFactory == null) {
                connectionFactory = new ActiveMQConnectionFactory(amqUser, amqPassword, activemqURL);
            }

            // Set the redelivery count to -1 (infinite), or else messages will start dropping
            // after the queue has had a certain number of failures (default is 6)
            RedeliveryPolicy policy = connectionFactory.getRedeliveryPolicy();
            policy.setMaximumRedeliveries(-1);

            QueueConnection amqConnection = connectionFactory.createQueueConnection();
            amqConnection.start();
            return amqConnection;
        }

        public boolean completed() {
            for (List<ConsumerThread> list : threads.values()) {

                for (ConsumerThread ct : list) {

                    if (ct.isAlive()) {
                        LOG.info("thread for {} is still alive.", ct.qName);
                        return false;
                    }
                }
            }
            return true;
        }

        private class ConsumerThread extends Thread {

            private int batchSize;
            private QueueConnection qc;
            private Session session;
            private MessageConsumer mc;
            private List<String> idList;
            private boolean shutdown = false;
            private String qName;

            private ConsumerThread(String queueName, int batchSize) throws Exception {

                this.batchSize = batchSize;

                // Each thread has its own connection and session
                qName = queueName;
                qc = newQueueConnection();
                session = newSession(qc);
                Queue q = session.createQueue(queueName);
                mc = session.createConsumer(q);

                idList = idsByQueue.get(queueName);
            }

            public void run() {

                try {

                    int count = 0;

                    // Keep reading as long as it hasn't been told to shutdown
                    while (!shutdown) {

                        if (idList.size() >= totalToExpect) {
                            LOG.info("Got {} for q: {}", +idList.size(), qName);
                            break;
                        }
                        Message m = mc.receive(4000);

                        if (m != null) {

                            // We received a non-null message, add the ID to our list

                            idList.add(m.getStringProperty("KEY"));

                            count++;

                            // If we've reached our batch size, commit the batch and reset the count

                            if (count == batchSize) {
                                session.commit();
                                count = 0;
                            }
                        } else {

                            // We didn't receive anything this time, commit any current batch and reset the count

                            session.commit();
                            count = 0;

                            // Sleep a little before trying to read after not getting a message

                            try {
                                LOG.info("did not receive on {}, current count: {}", qName, idList.size());
                                //sleep(3000);
                            } catch (Exception e) {
                            }
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {

                    // Once we exit, close everything
                    close();
                }
            }

            public void shutdown() {
                shutdown = true;
            }

            public void close() {

                try {
                    mc.close();
                } catch (Exception e) {
                }

                try {
                    session.close();
                } catch (Exception e) {
                }

                try {
                    qc.close();
                } catch (Exception e) {

                }
            }
        }
    }
}
