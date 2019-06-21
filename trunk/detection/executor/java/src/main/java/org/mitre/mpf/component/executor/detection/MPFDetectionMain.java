/******************************************************************************
 * NOTICE                                                                     *
 *                                                                            *
 * This software (or technical data) was produced for the U.S. Government     *
 * under contract, and is subject to the Rights in Data-General Clause        *
 * 52.227-14, Alt. IV (DEC 2007).                                             *
 *                                                                            *
 * Copyright 2019 The MITRE Corporation. All Rights Reserved.                 *
 ******************************************************************************/

/******************************************************************************
 * Copyright 2019 The MITRE Corporation                                       *
 *                                                                            *
 * Licensed under the Apache License, Version 2.0 (the "License");            *
 * you may not use this file except in compliance with the License.           *
 * You may obtain a copy of the License at                                    *
 *                                                                            *
 *    http://www.apache.org/licenses/LICENSE-2.0                              *
 *                                                                            *
 * Unless required by applicable law or agreed to in writing, software        *
 * distributed under the License is distributed on an "AS IS" BASIS,          *
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.   *
 * See the License for the specific language governing permissions and        *
 * limitations under the License.                                             *
 ******************************************************************************/

package org.mitre.mpf.component.executor.detection;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.mitre.mpf.component.api.detection.MPFDetectionComponentBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import javax.jms.*;
import java.io.IOException;

public class MPFDetectionMain {

    private static final Logger LOG = LoggerFactory.getLogger(MPFDetectionMain.class);

    private static final String DEFAULT_ACTIVEMQ_URI = "tcp://localhost:61616";


    private MPFDetectionMain() {
    }


    public static void main(String[] args) throws JMSException {
        if (args.length < 1) {
            IllegalArgumentException exception = new IllegalArgumentException(
                     "Must provide message queue name as the first command line argument.");
            LOG.error(exception.getMessage(), exception);
            throw exception;
        }

        MPFDetectionComponentBase component = null;
        Connection connection = null;

        try (ClassPathXmlApplicationContext context
                     = new ClassPathXmlApplicationContext("applicationContext.xml")) {
            context.registerShutdownHook();

            component = initializeComponent(context);
            connection = getConnection(getBrokerUri(args));

            startWatchingStandardIn(connection, Thread.currentThread());

            String queueName = args[0];
            processMessages(connection, queueName, component);
        }
        catch (Exception e) {
            LOG.error(e.getMessage(), e);
            throw e;
        }
        finally {
            if (component != null) {
                component.close();
            }
            if (connection != null) {
                connection.close();
            }
        }
    }


    private static void processMessages(Connection connection, String queueName, MPFDetectionComponentBase component)
            throws JMSException {
        Session session = connection.createSession(true, Session.SESSION_TRANSACTED);
        MessageConsumer requestConsumer = session.createConsumer(session.createQueue(queueName));
        MPFDetectionMessenger messenger = new MPFDetectionMessenger(component, session);

        Message message;
        // Null message means the connection was closed.
        while ((message = requestConsumer.receive()) != null) {
            messenger.onMessage(message);
        }
        LOG.info("Received null message indicating that the ActiveMQ connection was closed. Shutting down...");
    }


    private static void startWatchingStandardIn(Connection connection, Thread messageProcessingThread) {
        Thread watcherThread = new Thread(() -> watchStandardIn(connection, messageProcessingThread),
                                          "StandardInWatcher");
        // Make the watcher thread a daemon so that it doesn't prevent the JVM from shutting down.
        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    private static void watchStandardIn(Connection connection, Thread messageProcessingThread) {
        try {
            while (true) {
                int inputReadResult = System.in.read();
                if (inputReadResult == 'q') {
                    LOG.info("Received quit command. Initiating shutdown.");
                    break;
                }
                if (inputReadResult == -1) {
                    LOG.info("Standard in was closed. Initiating shutdown.");
                    break;
                }
                LOG.info("Received unexpected input on standard in: '{}'", (char) inputReadResult);
            }
        }
        catch (IOException e) {
            LOG.error("An error occurred while reading from standard in. Initiating shutdown.", e);
        }

        try {
            LOG.info("Closing ActiveMQ connection...");
            // Closing the connection should cause the message processing thread to exit.
            // If the message processing thread is waiting for a message, the receive method will return null.
            // If the message processing thread is currently processing a message, an exception will be thrown the
            // next time it calls a JMS method.
            connection.close();
            LOG.info("ActiveMQ connection closed.");
        }
        catch (JMSException e) {
            LOG.error("An error occurred while trying to close ActiveMQ connection: " + e.getMessage(), e);
        }

        try {
            messageProcessingThread.join(10_000);
            if (messageProcessingThread.isAlive()) {
                LOG.info("Message processing thread did not exit when connection closed, attempting interrupt.");
                messageProcessingThread.interrupt();
            }
        }
        catch (InterruptedException ignored) {
            // Already shutting down
        }
    }


    private static MPFDetectionComponentBase initializeComponent(AbstractApplicationContext context) {
        MPFDetectionComponentBase component = context.getBean("component", MPFDetectionComponentBase.class);
        LOG.info("Found component class {}", component.getClass().getName());

        component.setRunDirectory(System.getenv().getOrDefault("MPF_HOME", "/opt/mpf") + "/plugins");
        component.init();
        return component;
    }

    private static String getBrokerUri(String[] args) {
        if (args.length > 1) {
            return args[1];
        }
        String brokerUri = System.getenv("ACTIVE_MQ_BROKER_URI");
        if (brokerUri != null && !brokerUri.isEmpty()) {
            return brokerUri;
        }
        return DEFAULT_ACTIVEMQ_URI;
    }


    private static Connection getConnection(String brokerUri) throws JMSException {
        LOG.info("Attempting to connect to broker at: {}", brokerUri);
        ConnectionFactory connectionFactory = new ActiveMQConnectionFactory(brokerUri);
        Connection connection = connectionFactory.createConnection();
        connection.start();
        return connection;
    }
}
