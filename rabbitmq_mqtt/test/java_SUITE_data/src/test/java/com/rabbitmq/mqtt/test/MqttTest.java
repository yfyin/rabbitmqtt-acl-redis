//  The contents of this file are subject to the Mozilla Public License
//  Version 1.1 (the "License"); you may not use this file except in
//  compliance with the License. You may obtain a copy of the License
//  at http://www.mozilla.org/MPL/
//
//  Software distributed under the License is distributed on an "AS IS"
//  basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See
//  the License for the specific language governing rights and
//  limitations under the License.
//
//  The Original Code is RabbitMQ.
//
//  The Initial Developer of the Original Code is GoPivotal, Inc.
//  Copyright (c) 2007-2016 Pivotal Software, Inc.  All rights reserved.
//

package com.rabbitmq.mqtt.test;

import com.rabbitmq.client.*;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.internal.NetworkModule;
import org.eclipse.paho.client.mqttv3.internal.TCPNetworkModule;
import org.eclipse.paho.client.mqttv3.internal.wire.MqttPingReq;
import org.eclipse.paho.client.mqttv3.internal.wire.MqttWireMessage;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import javax.net.SocketFactory;
import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.*;

/***
 *  MQTT v3.1 tests
 *
 */

public class MqttTest implements MqttCallback {

    private final String host = "localhost";
    private final String brokerUrl = "tcp://" + host + ":" + getPort();
    private String clientId;
    private String clientId2;
    private MqttClient client;
    private MqttClient client2;
    private MqttConnectOptions conOpt;
    private volatile List<MqttMessage> receivedMessages;

    private final byte[] payload = "payload".getBytes();
    private final String topic = "test-topic";
    private final String retainedTopic = "test-retained-topic";
    private int testDelay = 2000;

    private volatile long lastReceipt;
    private volatile boolean expectConnectionFailure;
    private volatile boolean failOnDelivery = false;

    private Connection conn;
    private Channel ch;

    private static int getPort() {
        Object port = System.getProperty("mqtt.port");
        Assert.assertNotNull(port);
        return Integer.parseInt(port.toString());
    }

    private static int getAmqpPort() {
        Object port = System.getProperty("amqp.port");
        assertNotNull(port);
        return Integer.parseInt(port.toString());
    }

    private static String getHost() {
        Object host = System.getProperty("hostname");
        assertNotNull(host);
        return host.toString();
    }
    // override 10s limit
    private class MyConnOpts extends MqttConnectOptions {
        private int keepAliveInterval = 60;
        @Override
        public void setKeepAliveInterval(int keepAliveInterval) {
            this.keepAliveInterval = keepAliveInterval;
        }
        @Override
        public int getKeepAliveInterval() {
            return keepAliveInterval;
        }
    }

    @Before
    public void setUp() throws MqttException {
        clientId = getClass().getSimpleName() + ((int) (10000*Math.random()));
        clientId2 = clientId + "-2";
        client = new MqttClient(brokerUrl, clientId, null);
        client2 = new MqttClient(brokerUrl, clientId2, null);
        conOpt = new MyConnOpts();
        setConOpts(conOpt);
        receivedMessages = Collections.synchronizedList(new ArrayList<MqttMessage>());
        expectConnectionFailure = false;
    }

    @After
    public void tearDown() throws MqttException {
        // clean any sticky sessions
        setConOpts(conOpt);
        client = new MqttClient(brokerUrl, clientId, null);
        try {
            client.connect(conOpt);
            client.disconnect(3000);
        } catch (Exception ignored) {}

        client2 = new MqttClient(brokerUrl, clientId2, null);
        try {
            client2.connect(conOpt);
            client2.disconnect(3000);
        } catch (Exception ignored) {}
    }

    private void setUpAmqp() throws IOException, TimeoutException {
        int port = getAmqpPort();
        ConnectionFactory cf = new ConnectionFactory();
        cf.setHost(host);
        cf.setPort(port);
        conn = cf.newConnection();
        ch = conn.createChannel();
    }

    private void tearDownAmqp() throws IOException {
        if(conn.isOpen()) {
            conn.close();
        }
    }

    private void setConOpts(MqttConnectOptions conOpts) {
        conOpts.setCleanSession(true);
        conOpts.setKeepAliveInterval(60);
        conOpts.setUserName("guest");
        conOpts.setPassword("guest".toCharArray());
        conOpts.setMaxInflight(10000); // sometimes the PublishMultiple test fails with defaults
    }

    @Test
    public void connectFirst() throws MqttException, IOException, InterruptedException {
        NetworkModule networkModule = new TCPNetworkModule(SocketFactory.getDefault(), host, getPort(), "");
        networkModule.start();
        DataInputStream in = new DataInputStream(networkModule.getInputStream());
        OutputStream out = networkModule.getOutputStream();

        MqttWireMessage message = new MqttPingReq();

        try {
            // ---8<---
            // Copy/pasted from write() in MqttOutputStream.java.
            byte[] bytes = message.getHeader();
            byte[] pl = message.getPayload();
            out.write(bytes,0,bytes.length);

            int offset = 0;
            int chunckSize = 1024;
            while (offset < pl.length) {
                int length = Math.min(chunckSize, pl.length - offset);
                out.write(pl, offset, length);
                offset += chunckSize;
            }
            // ---8<---

            // ---8<---
            // Copy/pasted from flush() in MqttOutputStream.java.
            out.flush();
            // ---8<---

            // ---8<---
            // Copy/pasted from readMqttWireMessage() in MqttInputStream.java.
            ByteArrayOutputStream bais = new ByteArrayOutputStream();
            byte first = in.readByte();
            // ---8<---

            fail("Error expected if CONNECT is not first packet");
        } catch (IOException ignored) {}
    }

    @Test public void invalidUser() throws MqttException {
        conOpt.setUserName("invalid-user");
        try {
            client.connect(conOpt);
            fail("Authentication failure expected");
        } catch (MqttException ex) {
            Assert.assertEquals(MqttException.REASON_CODE_FAILED_AUTHENTICATION, ex.getReasonCode());
        }
    }

    // rabbitmq/rabbitmq-mqtt#37: QoS 1, clean session = false
    @Test public void qos1AndCleanSessionUnset()
            throws MqttException, IOException, TimeoutException, InterruptedException {
        testQueuePropertiesWithCleanSessionUnset("qos1-no-clean-session", 1, true, false);
    }

    protected void testQueuePropertiesWithCleanSessionSet(String cid, int qos, boolean durable, boolean autoDelete)
            throws IOException, MqttException, TimeoutException, InterruptedException {
        testQueuePropertiesWithCleanSession(true, cid, qos, durable, autoDelete);
    }

    protected void testQueuePropertiesWithCleanSessionUnset(String cid, int qos, boolean durable, boolean autoDelete)
            throws IOException, MqttException, TimeoutException, InterruptedException {
        testQueuePropertiesWithCleanSession(false, cid, qos, durable, autoDelete);
    }

    protected void testQueuePropertiesWithCleanSession(boolean cleanSession, String cid, int qos,
                                                       boolean durable, boolean autoDelete)
            throws MqttException, IOException, TimeoutException, InterruptedException {
        MqttClient c = new MqttClient(brokerUrl, cid, null);
        MqttConnectOptions opts = new MyConnOpts();
        opts.setUserName("guest");
        opts.setPassword("guest".toCharArray());
        opts.setCleanSession(cleanSession);
        c.connect(opts);

        setUpAmqp();
        Channel tmpCh = conn.createChannel();

        String q = "mqtt-subscription-" + cid + "qos" + String.valueOf(qos);

        c.subscribe(topic, qos);
        // there is no server-sent notification about subscription
        // success so we inject a delay
        Thread.sleep(testDelay);

        // ensure the queue is declared with the arguments we expect
        // e.g. mqtt-subscription-client-3aqos0
        try {
            // first ensure the queue exists
            tmpCh.queueDeclarePassive(q);
            // then assert on properties
            Map<String, Object> args = new HashMap<String, Object>();
            args.put("x-expires", 86400000);
            tmpCh.queueDeclare(q, durable, autoDelete, false, args);
        } finally {
            if(c.isConnected()) {
                c.disconnect(3000);
            }

            Channel tmpCh2 = conn.createChannel();
            tmpCh2.queueDelete(q);
            tmpCh2.close();
            tearDownAmqp();
        }
    }

    @Test public void invalidPassword() throws MqttException {
        conOpt.setUserName("invalid-user");
        conOpt.setPassword("invalid-password".toCharArray());
        try {
            client.connect(conOpt);
            fail("Authentication failure expected");
        } catch (MqttException ex) {
            Assert.assertEquals(MqttException.REASON_CODE_FAILED_AUTHENTICATION, ex.getReasonCode());
        }
    }

    @Test public void emptyPassword() throws MqttException {
        MqttClient c = new MqttClient(brokerUrl, clientId, null);
        MqttConnectOptions opts = new MyConnOpts();
        opts.setUserName("guest");
        opts.setPassword(null);
        try {
            c.connect(opts);
            fail("Authentication failure expected");
        } catch (MqttException ex) {
            Assert.assertEquals(MqttException.REASON_CODE_FAILED_AUTHENTICATION, ex.getReasonCode());
        }
    }


    @Test public void subscribeQos0() throws MqttException, InterruptedException {
        client.connect(conOpt);
        client.setCallback(this);
        client.subscribe(topic, 0);

        publish(client, topic, 0, payload);
        Thread.sleep(testDelay);
        Assert.assertEquals(1, receivedMessages.size());
        Assert.assertEquals(true, Arrays.equals(receivedMessages.get(0).getPayload(), payload));
        Assert.assertEquals(0, receivedMessages.get(0).getQos());
        client.disconnect();
    }

    @Test public void subscribeUnsubscribe() throws MqttException, InterruptedException {
        client.connect(conOpt);
        client.setCallback(this);
        client.subscribe(topic, 0);

        publish(client, topic, 1, payload);
        Thread.sleep(testDelay);
        Assert.assertEquals(1, receivedMessages.size());
        Assert.assertEquals(true, Arrays.equals(receivedMessages.get(0).getPayload(), payload));
        Assert.assertEquals(0, receivedMessages.get(0).getQos());

        client.unsubscribe(topic);
        publish(client, topic, 0, payload);
        Thread.sleep(testDelay);
        Assert.assertEquals(1, receivedMessages.size());
        client.disconnect();
    }

    @Test public void subscribeQos1() throws MqttException, InterruptedException {
        client.connect(conOpt);
        client.setCallback(this);
        client.subscribe(topic, 1);

        publish(client, topic, 0, payload);
        publish(client, topic, 1, payload);
        Thread.sleep(testDelay);

        Assert.assertEquals(2, receivedMessages.size());
        MqttMessage msg1 = receivedMessages.get(0);
        MqttMessage msg2 = receivedMessages.get(1);

        Assert.assertEquals(true, Arrays.equals(msg1.getPayload(), payload));
        Assert.assertEquals(0, msg1.getQos());

        Assert.assertEquals(true, Arrays.equals(msg2.getPayload(), payload));
        Assert.assertEquals(1, msg2.getQos());

        client.disconnect();
    }

    @Test public void subscribeReceivesRetainedMessagesWithMatchingQoS()
            throws MqttException, InterruptedException, UnsupportedEncodingException {
        client.connect(conOpt);
        client.setCallback(this);
        clearRetained(client, retainedTopic);
        client.subscribe(retainedTopic, 1);

        publishRetained(client, retainedTopic, 1, "retain 1".getBytes("UTF-8"));
        publishRetained(client, retainedTopic, 1, "retain 2".getBytes("UTF-8"));
        Thread.sleep(testDelay);

        Assert.assertEquals(2, receivedMessages.size());
        MqttMessage lastMsg = receivedMessages.get(1);

        client.unsubscribe(retainedTopic);
        receivedMessages.clear();
        client.subscribe(retainedTopic, 1);
        Thread.sleep(testDelay);
        Assert.assertEquals(1, receivedMessages.size());
        final MqttMessage retainedMsg = receivedMessages.get(0);
        Assert.assertEquals(new String(lastMsg.getPayload()),
                                   new String(retainedMsg.getPayload()));
    }

    @Test public void subscribeReceivesRetainedMessagesWithDowngradedQoS()
            throws MqttException, InterruptedException, UnsupportedEncodingException {
        client.connect(conOpt);
        client.setCallback(this);
        clearRetained(client, retainedTopic);
        client.subscribe(retainedTopic, 1);

        publishRetained(client, retainedTopic, 1, "retain 1".getBytes("UTF-8"));
        Thread.sleep(testDelay);

        Assert.assertEquals(1, receivedMessages.size());
        MqttMessage lastMsg = receivedMessages.get(0);

        client.unsubscribe(retainedTopic);
        receivedMessages.clear();
        final int subscribeQoS = 0;
        client.subscribe(retainedTopic, subscribeQoS);
        Thread.sleep(testDelay);
        Assert.assertEquals(1, receivedMessages.size());
        final MqttMessage retainedMsg = receivedMessages.get(0);
        Assert.assertEquals(new String(lastMsg.getPayload()),
                            new String(retainedMsg.getPayload()));
        Assert.assertEquals(subscribeQoS, retainedMsg.getQos());
    }

    @Test public void publishWithEmptyMessageClearsRetained()
            throws MqttException, InterruptedException, UnsupportedEncodingException {
        client.connect(conOpt);
        client.setCallback(this);
        clearRetained(client, retainedTopic);
        client.subscribe(retainedTopic, 1);

        publishRetained(client, retainedTopic, 1, "retain 1".getBytes("UTF-8"));
        publishRetained(client, retainedTopic, 1, "retain 2".getBytes("UTF-8"));
        Thread.sleep(testDelay);

        Assert.assertEquals(2, receivedMessages.size());
        client.unsubscribe(retainedTopic);
        receivedMessages.clear();

        clearRetained(client, retainedTopic);
        client.subscribe(retainedTopic, 1);
        Thread.sleep(testDelay);
        Assert.assertEquals(0, receivedMessages.size());
    }

    @Test public void topics() throws MqttException, InterruptedException {
        client.connect(conOpt);
        client.setCallback(this);
        client.subscribe("/+/mid/#");
        String cases[] = {"/pre/mid2", "/mid", "/a/mid/b/c/d", "/frob/mid"};
        List<String> expected = Arrays.asList("/a/mid/b/c/d", "/frob/mid");
        for(String example : cases){
            publish(client, example, 0, example.getBytes());
        }
        Thread.sleep(testDelay);
        Assert.assertEquals(expected.size(), receivedMessages.size());
        for (MqttMessage m : receivedMessages){
            expected.contains(new String(m.getPayload()));
        }
        client.disconnect();
    }

    @Test public void nonCleanSession() throws MqttException, InterruptedException {
        conOpt.setCleanSession(false);
        client.connect(conOpt);
        client.subscribe(topic, 1);
        client.disconnect();

        client2.connect(conOpt);
        publish(client2, topic, 1, payload);
        client2.disconnect();

        client.setCallback(this);
        client.connect(conOpt);

        Thread.sleep(testDelay);
        Assert.assertEquals(1, receivedMessages.size());
        Assert.assertEquals(true, Arrays.equals(receivedMessages.get(0).getPayload(), payload));
        client.disconnect();
    }

    @Test public void sessionRedelivery() throws MqttException, InterruptedException {
        conOpt.setCleanSession(false);
        client.connect(conOpt);
        client.subscribe(topic, 1);
        client.disconnect();

        client2.connect(conOpt);
        publish(client2, topic, 1, payload);
        client2.disconnect();

        failOnDelivery = true;

        // Connection should fail. Messages will be redelivered.
        client.setCallback(this);
        client.connect(conOpt);

        Thread.sleep(testDelay);
        // Message has been delivered but connection has failed.
        Assert.assertEquals(1, receivedMessages.size());
        Assert.assertEquals(true, Arrays.equals(receivedMessages.get(0).getPayload(), payload));

        Assert.assertFalse(client.isConnected());

        receivedMessages.clear();
        failOnDelivery = false;

        client.setCallback(this);
        client.connect(conOpt);

        Thread.sleep(testDelay);
        // Message has been redelivered after session resume
        Assert.assertEquals(1, receivedMessages.size());
        Assert.assertEquals(true, Arrays.equals(receivedMessages.get(0).getPayload(), payload));
        Assert.assertTrue(client.isConnected());
        client.disconnect();

        receivedMessages.clear();

        client.setCallback(this);
        client.connect(conOpt);

        Thread.sleep(testDelay);
        // This time messaage are acknowledged and won't be redelivered
        Assert.assertEquals(0, receivedMessages.size());
    }

    @Test public void cleanSession() throws MqttException, InterruptedException {
        conOpt.setCleanSession(false);
        client.connect(conOpt);
        client.subscribe(topic, 1);
        client.disconnect();

        client2.connect(conOpt);
        publish(client2, topic, 1, payload);
        client2.disconnect();

        conOpt.setCleanSession(true);
        client.connect(conOpt);
        client.setCallback(this);
        client.subscribe(topic, 1);

        Thread.sleep(testDelay);
        Assert.assertEquals(0, receivedMessages.size());
        client.unsubscribe(topic);
        client.disconnect();
    }

    @Test public void multipleClientIds() throws MqttException, InterruptedException {
        client.connect(conOpt);
        client2 = new MqttClient(brokerUrl, clientId, null);
        client2.connect(conOpt);
        Thread.sleep(testDelay);
        Assert.assertFalse(client.isConnected());
        client2.disconnect();
    }

    @Test public void ping() throws MqttException, InterruptedException {
        conOpt.setKeepAliveInterval(1);
        client.connect(conOpt);
        Thread.sleep(3000);
        Assert.assertEquals(true, client.isConnected());
        client.disconnect();
    }

    @Test public void will() throws MqttException, InterruptedException, IOException {
        client2.connect(conOpt);
        client2.subscribe(topic);
        client2.setCallback(this);

        final SocketFactory factory = SocketFactory.getDefault();
        final ArrayList<Socket> sockets = new ArrayList<Socket>();
        SocketFactory testFactory = new SocketFactory() {
            public Socket createSocket(String s, int i) throws IOException {
                Socket sock = factory.createSocket(s, i);
                sockets.add(sock);
                return sock;
            }
            public Socket createSocket(String s, int i, InetAddress a, int i1) throws IOException {
                return null;
            }
            public Socket createSocket(InetAddress a, int i) throws IOException {
                return null;
            }
            public Socket createSocket(InetAddress a, int i, InetAddress a1, int i1) throws IOException {
                return null;
            }
            @Override
            public Socket createSocket() throws IOException {
                Socket sock = new Socket();
                sockets.add(sock);
                return sock;
            }
        };
        conOpt.setSocketFactory(testFactory);
        MqttTopic willTopic = client.getTopic(topic);
        conOpt.setWill(willTopic, payload, 0, false);
        conOpt.setCleanSession(false);
        client.connect(conOpt);

        Assert.assertEquals(1, sockets.size());
        expectConnectionFailure = true;
        sockets.get(0).close();
        Thread.sleep(testDelay);

        Assert.assertEquals(1, receivedMessages.size());
        Assert.assertEquals(true, Arrays.equals(receivedMessages.get(0).getPayload(), payload));
        client2.disconnect();
    }

    @Test public void subscribeMultiple() throws MqttException {
        client.connect(conOpt);
        publish(client, "/topic/1", 1, "msq1-qos1".getBytes());

        client2.connect(conOpt);
        client2.setCallback(this);
        client2.subscribe("/topic/#");
        client2.subscribe("/topic/#");

        publish(client, "/topic/2", 0, "msq2-qos0".getBytes());
        publish(client, "/topic/3", 1, "msq3-qos1".getBytes());
        publish(client, topic, 0, "msq4-qos0".getBytes());
        publish(client, topic, 1, "msq4-qos1".getBytes());

        Assert.assertEquals(2, receivedMessages.size());
        client.disconnect();
        client2.disconnect();
    }

    @Test public void publishMultiple() throws MqttException, InterruptedException {
        int pubCount = 50;
        for (int subQos=0; subQos < 2; subQos++){
            for (int pubQos=0; pubQos < 2; pubQos++){
                client.connect(conOpt);
                client.subscribe(topic, subQos);
                client.setCallback(this);
                long start = System.currentTimeMillis();
                for (int i=0; i<pubCount; i++){
                    publish(client, topic, pubQos, payload);
                }
                Thread.sleep(testDelay);
                Assert.assertEquals(pubCount, receivedMessages.size());
                System.out.println("publish QOS" + pubQos + " subscribe QOS" + subQos +
                                   ", " + pubCount + " msgs took " +
                                   (lastReceipt - start)/1000.0 + "sec");
                client.disconnect();
                receivedMessages.clear();
            }
        }
    }

    @Test public void interopM2A() throws MqttException, IOException, InterruptedException, TimeoutException {
        setUpAmqp();
        String queue = ch.queueDeclare().getQueue();
        ch.queueBind(queue, "amq.topic", topic);

        client.connect(conOpt);
        publish(client, topic, 1, payload);
        client.disconnect();
        Thread.sleep(testDelay);

        GetResponse response = ch.basicGet(queue, true);
        assertTrue(Arrays.equals(payload, response.getBody()));
        assertNull(ch.basicGet(queue, true));
        tearDownAmqp();
    }

    @Test public void interopA2M() throws MqttException, IOException, InterruptedException, TimeoutException {
        client.connect(conOpt);
        client.setCallback(this);
        client.subscribe(topic, 1);

        setUpAmqp();
        ch.basicPublish("amq.topic", topic, MessageProperties.MINIMAL_BASIC, payload);
        tearDownAmqp();
        Thread.sleep(testDelay);

        Assert.assertEquals(1, receivedMessages.size());
        client.disconnect();
    }

    private void publish(MqttClient client, String topicName, int qos, byte[] payload) throws MqttException {
    	publish(client, topicName, qos, payload, false);
    }

    private void publish(MqttClient client, String topicName, int qos, byte[] payload, boolean retained) throws MqttException {
    	MqttTopic topic = client.getTopic(topicName);
   		MqttMessage message = new MqttMessage(payload);
        message.setQos(qos);
        message.setRetained(retained);
    	MqttDeliveryToken token = topic.publish(message);
    	token.waitForCompletion();
    }

    private void publishRetained(MqttClient client, String topicName, int qos, byte[] payload) throws MqttException {
        publish(client, topicName, qos, payload, true);
    }

    private void clearRetained(MqttClient client, String topicName) throws MqttException {
        publishRetained(client, topicName, 1, "".getBytes());
    }

    public void connectionLost(Throwable cause) {
        if (!expectConnectionFailure)
            fail("Connection unexpectedly lost");
    }

    public void messageArrived(String topic, MqttMessage message) throws Exception {
        lastReceipt = System.currentTimeMillis();
        receivedMessages.add(message);
        if(failOnDelivery){
            throw new Exception("failOnDelivery");
        }
    }

    public void deliveryComplete(IMqttDeliveryToken token) {
    }
}
